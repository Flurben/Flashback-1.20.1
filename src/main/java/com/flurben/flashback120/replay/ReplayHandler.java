package com.flurben.flashback120.replay;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import org.apache.commons.io.FileUtils;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.editor.CameraRig;
import com.flurben.flashback120.editor.EditHistory;
import com.flurben.flashback120.editor.EditorState;
import com.flurben.flashback120.editor.KeyframeTrack;
import com.flurben.flashback120.editor.TrackType;
import com.flurben.flashback120.editor.Visuals;
import com.flurben.flashback120.format.PacketChunks;
import com.flurben.flashback120.format.ReplayArchive;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.format.ReplayIndex;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.format.ReplayStream;
import com.flurben.flashback120.gui.ReplayEditorScreen;
import com.flurben.flashback120.gui.ReplaySelectScreen;
import com.flurben.flashback120.net.PacketCodec;
import com.flurben.flashback120.net.PacketIds;
import com.flurben.flashback120.recording.HotbarState;
import com.flurben.flashback120.recording.RecordingManager;
import com.flurben.flashback120.replay.net.PacketFilter;
import com.flurben.flashback120.replay.net.ReplayConnection;
import com.flurben.flashback120.util.Notifications;

/**
 * Plays back a replay. There is at most one active instance.
 *
 * <p>
 * Timing: the replay has its own clock ({@link #replayTime}, in ticks). Every frame the game's timer is told how many
 * client ticks to run; ticks that advance the replay ("world ticks") deliver the next recorded tick of packets and let
 * the world update normally, while extra "input ticks" keep the GUI and keyboard responsive with the world frozen (the
 * same mechanism the game uses for its pause menu). The world's partial tick is replaced by the replay's, so pausing
 * and slow motion are perfectly smooth.
 */
public final class ReplayHandler implements PacketFilter.Listener {

    /** About 30 times as much once decoded. */
    private static final int FAST_FORWARD_FLUSH_BYTES = 8 << 20;

    private static ReplayHandler active;
    private static final Visuals DEFAULT_VISUALS = new Visuals();
    /** The game runs at most 10 ticks per frame. */
    private static final int MAX_WORLD_TICKS_PER_FRAME = 10;

    /** Packets whose whole effect is inside the world, which goes away at a login or a dimension change. */
    private static final BitSet WORLD_ONLY = new BitSet();
    /** Packets that are only a sound or a particle at the moment they arrive. */
    private static final BitSet EFFECTS = new BitSet();

    static {
        for (Class<?> c : new Class<?>[] { ClientboundAddEntityPacket.class, ClientboundAddExperienceOrbPacket.class,
            ClientboundAddPlayerPacket.class, ClientboundAnimatePacket.class, ClientboundBlockDestructionPacket.class,
            ClientboundBlockEntityDataPacket.class, ClientboundBlockEventPacket.class, ClientboundBlockUpdatePacket.class,
            ClientboundChunksBiomesPacket.class, ClientboundDamageEventPacket.class, ClientboundEntityEventPacket.class,
            ClientboundExplodePacket.class, ClientboundForgetLevelChunkPacket.class, ClientboundHurtAnimationPacket.class,
            ClientboundLevelChunkWithLightPacket.class, ClientboundLevelEventPacket.class,
            ClientboundLevelParticlesPacket.class, ClientboundLightUpdatePacket.class, ClientboundMapItemDataPacket.class,
            ClientboundMoveEntityPacket.Pos.class, ClientboundMoveEntityPacket.PosRot.class,
            ClientboundMoveEntityPacket.Rot.class, ClientboundRemoveEntitiesPacket.class,
            ClientboundRemoveMobEffectPacket.class, ClientboundRotateHeadPacket.class,
            ClientboundSectionBlocksUpdatePacket.class, ClientboundSetEntityDataPacket.class,
            ClientboundSetEntityLinkPacket.class, ClientboundSetEntityMotionPacket.class,
            ClientboundSetEquipmentPacket.class, ClientboundSetPassengersPacket.class, ClientboundSoundEntityPacket.class,
            ClientboundSoundPacket.class, ClientboundTakeItemEntityPacket.class, ClientboundTeleportEntityPacket.class,
            ClientboundUpdateAttributesPacket.class, ClientboundUpdateMobEffectPacket.class }) {
            int id = PacketCodec.idOf(c);
            if (id >= 0) WORLD_ONLY.set(id);
        }
        EFFECTS.set(PacketIds.SOUND);
        EFFECTS.set(PacketIds.SOUND_ENTITY);
        EFFECTS.set(PacketIds.LEVEL_PARTICLES);
    }

    public static boolean isReplaying() {
        return active != null;
    }

    public static ReplayHandler get() {
        return active;
    }

    public static Visuals visuals() {
        ReplayHandler h = active;
        return h != null ? h.editor.visuals : DEFAULT_VISUALS;
    }

    /** Implemented by the exporter to drive the replay clock frame by frame. */
    public interface ClockDriver {

        /** Returns the replay time (in ticks) for the frame about to be rendered. */
        double nextFrameTime();
    }

    private final Minecraft mc = Minecraft.getInstance();
    public final File archive;
    public final ReplayMeta meta;
    private final File streamFile;
    private final ReplayStream stream;
    public final EditorState editor;

    private ReplayConnection connection;
    private int currentTick = -1;
    private double replayTime;
    private boolean paused = true;
    private float speed = 1.0f;
    private int pendingWorldTicks;
    private double inputAccumulator;
    private long lastFrameNanos;
    private boolean worldTick;
    private boolean seeking;
    private boolean fastForwarding;
    private long deliveredBytes;
    /** While catching up: records before this position are in a world that gets replaced before the target tick. */
    private long replacedBefore;
    /** While going back without replacing the world. */
    private ReplayIndex.Rewind rewind;
    /** The last tick each chunk's blocks changed at, or a later one. */
    private final Map<Long, Integer> chunkChanges = new ConcurrentHashMap<>();

    private int localPlayerId = Integer.MIN_VALUE;
    private HotbarState hotbar;
    private int spectatingId = Integer.MIN_VALUE;
    private double[] pendingCameraPos;
    private boolean cameraPlaced;
    private String cameraDimension;

    private long recordedWorldTime = -1;
    private int recordedWorldTimeTick;
    private float recordedRain, recordedThunder;
    private boolean timeOverridden, weatherOverridden;

    private ClockDriver clockDriver;
    private boolean applyKeyframes = true;
    private boolean editorDirty;
    private float currentFov = Float.NaN;
    private float currentRoll;
    private float shakeYaw, shakePitch;
    private int decodeFailures;

    /** Undo and redo of what is edited in this replay. */
    public final EditHistory history = new EditHistory();
    public final CameraController cameraController = new CameraController();
    private int ignoredDisconnects;

    /** Whether camera presets move the camera while paused, so they can be seen while editing them. */
    private boolean rigPreview;
    /** The smoothed target of the camera preset in use: x, y, z of its feet, heading, height. */
    private double[] rigTarget;
    private CameraRig rigTargetOf;
    private double rigTargetTime;

    private ReplayHandler(File archive, ReplayMeta meta, File streamFile, ReplayStream stream, EditorState editor) {
        this.archive = archive;
        this.meta = meta;
        this.streamFile = streamFile;
        this.stream = stream;
        this.editor = editor;
        history.reset(editor);
    }

    // ------------------------------------------------------------------ lifecycle

    /** Opens a replay. Must be called from the client thread while no world is loaded. */
    public static void open(File archive) throws IOException {
        Minecraft mc = Minecraft.getInstance();
        if (active != null) throw new IllegalStateException("A replay is already open");
        if (mc.level != null) throw new IllegalStateException("Leave the world before opening a replay");
        if (RecordingManager.INSTANCE.isRecording()) RecordingManager.INSTANCE.finish();

        long openStart = System.nanoTime();
        ReplayMeta meta = ReplayArchive.readMeta(archive);
        File cacheDir = new File(RecordingManager.rootDir(), "cache");
        cacheDir.mkdirs();
        File streamFile = new File(cacheDir, "playback-" + System.nanoTime() + ".fbnh");
        if (!ReplayArchive.extractEntry(archive, ReplayFormat.ENTRY_STREAM, streamFile)) {
            throw new IOException("Replay has no stream");
        }
        ReplayStream stream = ReplayStream.open(streamFile);
        long indexedAt = System.nanoTime();
        if (stream.getTickCount() == 0) {
            stream.close();
            throw new IOException("Replay is empty");
        }

        byte[] ed = ReplayArchive.readEntry(archive, ReplayFormat.ENTRY_EDITOR);
        EditorState editor = ed != null ? EditorState.fromJson(new String(ed, StandardCharsets.UTF_8))
            : EditorState.createDefault();

        ReplayHandler handler = new ReplayHandler(archive, meta, streamFile, stream, editor);
        warnAboutMods(meta);
        active = handler;
        CameraEntity.forgetLast();
        PacketFilter.ignoredChannels = new HashSet<>(com.flurben.flashback120.Config.get().ignoredCustomPayloads);
        try {
            handler.startConnection();
            handler.fastForwardTo(0);
            if (mc.level == null || mc.player == null) {
                throw new IOException("Replay did not create a world (corrupt or unsupported recording)");
            }
            handler.placeInitialCamera();
        } catch (Throwable t) {
            handler.close(false);
            throw t instanceof IOException ? (IOException) t : new IOException(t);
        }
        handler.lastFrameNanos = System.nanoTime();
        Flashback120.LOG.info("Opened replay in {} ms ({} ms extracting and indexing {} bytes)",
            (handler.lastFrameNanos - openStart) / 1_000_000L, (indexedAt - openStart) / 1_000_000L, streamFile.length());
        mc.setScreen(new ReplayEditorScreen());
    }

    /** Mods that were in the recording but are not installed may leave blocks, items or entities missing. */
    private static void warnAboutMods(ReplayMeta meta) {
        Set<String> installed = new HashSet<>();
        FabricLoader.getInstance().getAllMods().forEach(m -> installed.add(m.getMetadata().getId()));
        List<String> missing = new ArrayList<>();
        for (String mod : meta.mods) {
            String id = mod.contains("@") ? mod.substring(0, mod.indexOf('@')) : mod;
            if (!installed.contains(id) && !id.startsWith("fabric") && !id.equals("java")) missing.add(id);
        }
        if (!missing.isEmpty()) {
            Flashback120.LOG.warn("Mods from the recording that are not installed: {}", missing);
            Notifications.show("Missing mods", missing.size() + " mods of the recording are not installed", 8000);
        }
    }

    /** Leaves the replay and returns to the replay list. */
    public void exit() {
        close(true);
        mc.setScreen(returnScreen());
    }

    private void close(boolean saveEditor) {
        if (saveEditor) saveEditorAsync();
        clockDriver = null;
        try {
            mc.setCameraEntity(mc.player);
            if (mc.level != null) mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(
                net.minecraft.network.chat.Component.literal("Closing replay")));
            if (connection != null) connection.close();
        } catch (Throwable t) {
            Flashback120.LOG.error("Error while closing replay world", t);
        }
        active = null;
        VisualsApplier.restore();
        try {
            stream.close();
        } catch (IOException ignored) {}
        FileUtils.deleteQuietly(streamFile);
        CameraEntity.forgetLast();
    }

    public void markEditorDirty() {
        editorDirty = true;
        history.changed();
    }

    public void saveEditorAsync() {
        if (!editorDirty) return;
        editorDirty = false;
        final byte[] json = editor.toJson().getBytes(StandardCharsets.UTF_8);
        final File file = archive;
        Thread t = new Thread(() -> {
            try {
                Map<String, byte[]> map = new HashMap<>();
                map.put(ReplayFormat.ENTRY_EDITOR, json);
                synchronized (ReplayHandler.class) {
                    ReplayArchive.update(file, map);
                }
            } catch (Throwable e) {
                Flashback120.LOG.error("Unable to save editor state", e);
            }
        }, "Flashback editor save");
        t.start();
    }

    private void startConnection() {
        chunkChanges.clear();
        if (mc.player instanceof CameraEntity camera) camera.remember();
        if (connection != null) connection.close();
        mc.player = null; // a fresh camera is created with the new world
        mc.setCameraEntity(null);
        connection = new ReplayConnection(mc);
        currentTick = -1;
        localPlayerId = Integer.MIN_VALUE;
        hotbar = null;
        recordedWorldTime = -1;
        timeOverridden = false;
        weatherOverridden = false;
    }

    private void placeInitialCamera() {
        if (CameraEntity.hasLast) return;
        CameraEntity camera = getCamera();
        if (camera == null) return;
        Entity local = localPlayerId != Integer.MIN_VALUE ? mc.level.getEntity(localPlayerId) : null;
        if (local != null) {
            placeBehind(camera, local.getX(), local.getEyeY(), local.getZ(), local.getYRot(), local.getXRot());
        } else if (pendingCameraPos != null) {
            camera.setCameraPosition(pendingCameraPos[0], pendingCameraPos[1] + 1.62, pendingCameraPos[2],
                (float) pendingCameraPos[3], (float) pendingCameraPos[4]);
        }
        cameraPlaced = true;
    }

    // ------------------------------------------------------------------ accessors

    public CameraEntity getCamera() {
        return mc.player instanceof CameraEntity camera ? camera : null;
    }

    public int getCurrentTick() {
        return Math.max(0, currentTick);
    }

    public double getReplayTime() {
        return replayTime;
    }

    public int getTotalTicks() {
        return stream.getTickCount();
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        if (!paused && replayTime >= getTotalTicks() - 1) {
            seek(editor.inPoint >= 0 ? editor.inPoint : 0);
        }
        this.paused = paused;
    }

    public float getSpeed() {
        return speed;
    }

    public void setSpeed(float speed) {
        this.speed = Mth.clamp(speed, 0.05f, 64f);
    }

    public boolean isSeeking() {
        return seeking;
    }

    public boolean isApplyKeyframes() {
        return applyKeyframes;
    }

    public void setApplyKeyframes(boolean apply) {
        this.applyKeyframes = apply;
    }

    public int getLocalPlayerId() {
        return localPlayerId;
    }

    public HotbarState getHotbar() {
        return hotbar;
    }

    public boolean isWorldTick() {
        return worldTick;
    }

    public boolean isExporting() {
        return clockDriver != null;
    }

    public void setClockDriver(ClockDriver driver) {
        this.clockDriver = driver;
        this.lastFrameNanos = System.nanoTime();
    }

    public float getFovOverride() {
        return currentFov;
    }

    public float getRoll() {
        return currentRoll;
    }

    public float getShakeYaw() {
        return shakeYaw;
    }

    public float getShakePitch() {
        return shakePitch;
    }

    public List<ReplayMeta.Marker> getAllMarkers() {
        List<ReplayMeta.Marker> all = new ArrayList<>(meta.markers);
        all.addAll(editor.markers);
        Collections.sort(all, (a, b) -> a.tick - b.tick);
        return all;
    }

    // ------------------------------------------------------------------ spectating

    public void spectate(Entity entity) {
        if (!(entity instanceof LivingEntity) || entity == mc.player) {
            stopSpectating();
            return;
        }
        spectatingId = entity.getId();
        mc.setCameraEntity(entity);
    }

    public void stopSpectating() {
        if (spectatingId != Integer.MIN_VALUE) {
            Entity e = mc.getCameraEntity();
            CameraEntity camera = getCamera();
            if (camera != null && e != null && e != camera) {
                camera.setCameraPosition(e.getX(), e.getEyeY(), e.getZ(), e.getYRot(), e.getXRot());
            }
        }
        spectatingId = Integer.MIN_VALUE;
        mc.setCameraEntity(mc.player);
    }

    public Entity getSpectating() {
        if (spectatingId == Integer.MIN_VALUE || mc.level == null) return null;
        return mc.level.getEntity(spectatingId);
    }

    public boolean isSpectating() {
        return spectatingId != Integer.MIN_VALUE;
    }

    /** Whether the camera is in the recording player's head, which shows the recorded HUD. */
    public boolean isSpectatingRecorder() {
        return spectatingId != Integer.MIN_VALUE && spectatingId == localPlayerId;
    }

    // ------------------------------------------------------------------ seeking

    /** Jumps to the given tick. Backwards jumps keep the world where they can, and otherwise reload the replay. */
    public void seek(int tick) {
        seek(tick, clockDriver != null);
    }

    /** Jumps to the given tick and, unless that is ahead, gets there by loading the world from scratch. */
    public void seekReloading(int tick) {
        seek(tick, true);
    }

    private void seek(int tick, boolean reload) {
        tick = Mth.clamp(tick, 0, getTotalTicks() - 1);
        seeking = true;
        long seekStart = System.nanoTime();
        Screen screen = mc.screen;
        try {
            boolean reconnect = reload ? tick <= currentTick : tick < currentTick;
            boolean kept = reconnect && !reload && rewindKeepingWorld(tick);
            if (kept) reconnect = false;
            if (reconnect) startConnection();
            fastForwardTo(tick);
            rewind = null;
            Flashback120.LOG.debug("Seek to {} took {} ms{}", tick, (System.nanoTime() - seekStart) / 1_000_000L,
                kept ? " (kept the world)" : reconnect ? " (reloaded)" : "");
            replayTime = tick;
            pendingWorldTicks = 0;
            if (mc.player == null || mc.level == null) {
                throw new IllegalStateException("Replay world was not created while seeking");
            }
            if (spectatingId != Integer.MIN_VALUE) {
                Entity e = getSpectating();
                if (e instanceof LivingEntity) mc.setCameraEntity(e);
            }
        } catch (Throwable t) {
            Flashback120.LOG.error("Seek failed", t);
            Notifications.show("Flashback", "Seek failed: " + t.getMessage(), 6000);
            if (mc.level == null || mc.player == null) {
                close(true);
                mc.setScreen(returnScreen());
                return;
            }
        } finally {
            rewind = null;
            seeking = false;
            lastFrameNanos = System.nanoTime();
        }
        if (screen instanceof ReplayEditorScreen && mc.screen != screen) mc.setScreen(screen);
    }

    /**
     * Prepares going back to an earlier tick in the current world, which is much faster than replacing it and also
     * keeps what the renderer has built for it. Everything that can differ is reset and comes back from the records,
     * except for chunks that are known to be the same at both ticks.
     *
     * @return false if this cannot give the same result as loading the tick from scratch
     */
    private boolean rewindKeepingWorld(int tick) {
        ClientLevel level = mc.level;
        if (level == null || connection == null || !(mc.player instanceof CameraEntity camera)) return false;
        List<Long> changed = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : chunkChanges.entrySet()) {
            if (e.getValue() > tick) changed.add(e.getKey());
        }
        ReplayIndex.Rewind plan = stream.getIndex().planRewind(currentTick, tick, changed);
        if (plan == null) return false;

        camera.remember();
        removeReplayedEntities(level);
        for (long key : plan.chunks) {
            chunkChanges.remove(key);
            int x = ReplayIndex.chunkX(key), z = ReplayIndex.chunkZ(key);
            if (level.getChunkSource().hasChunk(x, z)) {
                connection.deliver(new ClientboundForgetLevelChunkPacket(x, z));
            }
        }
        mc.particleEngine.clearParticles();
        mc.getSoundManager().stop();
        mc.levelRenderer.destroyingBlocks.clear();
        mc.levelRenderer.destructionProgress.clear();
        hotbar = null;
        recordedWorldTime = -1;
        timeOverridden = false;
        if (plan.boundary.rebuildsAll) {
            Scoreboard scoreboard = level.getScoreboard();
            for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) scoreboard.removeObjective(objective);
            for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) scoreboard.removePlayerTeam(team);
            connection.listener.playerInfoMap.clear();
            connection.listener.listedPlayers.clear();
            mc.gui.getBossOverlay().reset();
            level.getLevelData().setRaining(false);
            level.setRainLevel(0);
            level.setThunderLevel(0);
            recordedRain = 0;
            recordedThunder = 0;
            weatherOverridden = false;
        }

        rewind = plan;
        currentTick = plan.boundary.tick;
        try {
            stream.readTickFrom(currentTick, plan.boundary.position, this::handleRecord);
        } catch (IOException e) {
            Flashback120.LOG.error("Failed to read tick {}", currentTick, e);
        }
        return true;
    }

    /** Takes effect at once, unlike the removal a packet asks for. */
    private void removeReplayedEntities(ClientLevel level) {
        List<Entity> all = new ArrayList<>();
        for (Entity e : level.entitiesForRendering()) all.add(e);
        for (Entity e : all) {
            if (e == mc.player) continue;
            level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    public void seekRelative(int ticks) {
        seek(getCurrentTick() + ticks);
    }

    /** Delivers and processes every tick up to and including {@code target}, without running world ticks. */
    private void fastForwardTo(int target) {
        target = Math.min(target, getTotalTicks() - 1);
        ReplayIndex.Boundary boundary = stream.getIndex().boundaryAt(target);
        replacedBefore = boundary != null && boundary.tick > currentTick ? boundary.position : 0;
        fastForwarding = true;
        try {
            while (currentTick < target) {
                currentTick++;
                deliverTick(currentTick);
            }
        } finally {
            fastForwarding = false;
            replacedBefore = 0;
        }
        settleEntities();
        if (!cameraPlaced && mc.level != null) placeInitialCamera();
        followDimensionChange();
        if (mc.screen instanceof ReceivingLevelScreen) mc.setScreen(null);
    }

    /** Coordinates mean nothing in another dimension, so the camera moves back to the recording player. */
    private void followDimensionChange() {
        CameraEntity camera = getCamera();
        if (camera == null) return;
        String dimension = camera.level().dimension().location().toString();
        if (cameraDimension == null) cameraDimension = dimension;
        if (dimension.equals(cameraDimension)) return;
        cameraDimension = dimension;
        Entity local = localPlayerId != Integer.MIN_VALUE ? mc.level.getEntity(localPlayerId) : null;
        if (local != null) {
            placeBehind(camera, local.getX(), local.getEyeY(), local.getZ(), local.getYRot(), local.getXRot());
        } else if (pendingCameraPos != null) {
            double[] p = pendingCameraPos;
            placeBehind(camera, p[0], p[1] + 1.62, p[2], (float) p[3], (float) p[4]);
        }
        cameraPlaced = true;
    }

    /** A little behind a position, looking the same way, rather than inside the player's head. */
    private static void placeBehind(CameraEntity camera, double x, double y, double z, float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double back = 3.0D;
        camera.setCameraPosition(
            x + Math.sin(yawRad) * Math.cos(pitchRad) * back,
            y + Math.sin(pitchRad) * back,
            z - Math.cos(yawRad) * Math.cos(pitchRad) * back,
            yaw,
            pitch);
    }

    /** After a jump, put interpolating entities at their final positions. */
    private void settleEntities() {
        ClientLevel level = mc.level;
        if (level == null) return;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof CameraEntity) continue;
            try {
                if (e instanceof LivingEntity l) {
                    if (l.lerpSteps > 0) {
                        e.setPos(l.lerpX, l.lerpY, l.lerpZ);
                        e.setYRot((float) l.lerpYRot);
                        e.setXRot((float) l.lerpXRot);
                        l.lerpSteps = 0;
                    }
                    if (l.lerpHeadSteps > 0) {
                        l.setYHeadRot((float) l.lyHeadRot);
                        l.lerpHeadSteps = 0;
                    }
                    l.yHeadRotO = l.getYHeadRot();
                    l.yBodyRotO = l.yBodyRot;
                }
                e.setOldPosAndRot();
            } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ packet delivery

    private void deliverTick(int tick) {
        try {
            stream.readTick(tick, this::handleRecord);
        } catch (IOException e) {
            Flashback120.LOG.error("Failed to read tick {}", tick, e);
        }
    }

    private void handleRecord(ReplayStream.Record rec) {
        if (fastForwarding && (deliveredBytes += rec.data.length) >= FAST_FORWARD_FLUSH_BYTES) {
            deliveredBytes = 0;
        }
        if (rec.position < replacedBefore && isInWorldOnly(rec)) return;
        switch (rec.type) {
            case ReplayFormat.REC_PACKET: {
                if (fastForwarding && isOnlyAnEffect(rec.data)) return;
                if (rewind != null && !neededForRewind(rec.data)) return;
                byte[] data = PacketFilter.filter(rec.data, this);
                if (data == null) return;
                int id = PacketCodec.peekId(data);
                if (id == PacketIds.RESPAWN) pendingCameraPos = null;
                if (id == PacketIds.SET_TIME) trackTime(data);
                else if (id == PacketIds.GAME_EVENT) trackWeather(data);
                Packet<ClientGamePacketListener> packet;
                try {
                    packet = PacketCodec.decode(data);
                } catch (Throwable t) {
                    if (decodeFailures++ < 20) {
                        Flashback120.LOG.warn("Unable to decode recorded packet 0x{}", Integer.toHexString(id), t);
                    }
                    return;
                }
                connection.deliver(packet);
                return;
            }
            case ReplayFormat.REC_LOCAL_PLAYER:
                localPlayerId = PacketFilter.readInt(rec.data, 0);
                return;
            case ReplayFormat.REC_LOCAL_MOVE: {
                final byte[] d = rec.data;
                connection.run(() -> applyLocalMove(d));
                return;
            }
            case ReplayFormat.REC_CLEAR_WORLD:
                // what a rewind starts from, and the point of a rewind is to keep the world
                if (rewind == null) connection.run(this::clearWorld);
                return;
            case ReplayFormat.REC_HOTBAR: {
                final byte[] d = rec.data;
                connection.run(() -> hotbar = HotbarState.read(d));
                return;
            }
            default:
                // unknown record type from a newer version: ignore
        }
    }

    /** Whether a record only affects what goes away with the world. */
    private static boolean isInWorldOnly(ReplayStream.Record rec) {
        switch (rec.type) {
            case ReplayFormat.REC_LOCAL_MOVE:
                return true;
            case ReplayFormat.REC_PACKET:
                int id = PacketCodec.peekId(rec.data);
                return id >= 0 && WORLD_ONLY.get(id);
            default:
                return false;
        }
    }

    /** Sounds and particles of ticks that are skipped over. A jukebox stays: its song plays on past that tick. */
    private static boolean isOnlyAnEffect(byte[] data) {
        int id = PacketCodec.peekId(data);
        if (id >= 0 && EFFECTS.get(id)) return true;
        return id == PacketIds.LEVEL_EVENT
            && PacketFilter.readInt(data, PacketCodec.idLength(data)) != LevelEvent.SOUND_PLAY_JUKEBOX_SONG;
    }

    /** Whether a packet is needed to get back to an earlier tick when {@link #rewind} keeps the world. */
    private boolean neededForRewind(byte[] data) {
        int id = PacketCodec.peekId(data);
        if (id == PacketIds.LOGIN || id == PacketIds.RESPAWN) return false;
        if (PacketChunks.changesChunks(id)) {
            long[] chunks = PacketChunks.of(data);
            if (chunks == null) return true;
            for (long c : chunks) if (rewind.chunks.contains(c)) return true;
            return false;
        }
        // a piston changes blocks on its own, and those are the same already in chunks that are kept
        if (id == PacketIds.BLOCK_EVENT && isPistonEvent(data)) {
            int off = PacketCodec.idLength(data);
            long pos = PacketFilter.readLong(data, off);
            return rewind.chunks.contains(ReplayIndex.chunkKey(PacketChunks.blockX(pos) >> 4, PacketChunks.blockZ(pos) >> 4));
        }
        return true;
    }

    private static boolean isPistonEvent(byte[] data) {
        int off = PacketCodec.idLength(data) + 10;
        if (data.length <= off) return false;
        int value = 0, shift = 0;
        for (int i = off; i < data.length && i < off + 5; i++) {
            int b = data[i] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return value == BuiltInRegistries.BLOCK.getId(Blocks.PISTON)
            || value == BuiltInRegistries.BLOCK.getId(Blocks.STICKY_PISTON);
    }

    private void trackTime(byte[] data) {
        int off = PacketCodec.idLength(data);
        if (data.length < off + 16) return;
        recordedWorldTime = Math.abs(PacketFilter.readLong(data, off + 8));
        recordedWorldTimeTick = currentTick;
    }

    private void trackWeather(byte[] data) {
        int off = PacketCodec.idLength(data);
        if (data.length < off + 5) return;
        int reason = data[off] & 0xFF;
        float value = Float.intBitsToFloat(PacketFilter.readInt(data, off + 1));
        if (reason == 1) recordedRain = 0;
        else if (reason == 2) recordedRain = 1;
        else if (reason == 7) recordedRain = value;
        else if (reason == 8) recordedThunder = value;
    }

    @Override
    public void onJoinGame(int originalEntityId) {
        if (localPlayerId == Integer.MIN_VALUE) localPlayerId = originalEntityId;
    }

    @Override
    public void onPlayerPosLook(double x, double y, double z, float yaw, float pitch) {
        pendingCameraPos = new double[] { x, y, z, yaw, pitch };
    }

    private void applyLocalMove(byte[] d) {
        if (mc.level == null) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
            int id = in.readInt();
            double x = in.readDouble(), y = in.readDouble(), z = in.readDouble();
            float yaw = in.readFloat(), pitch = in.readFloat(), head = in.readFloat();
            boolean onGround = in.readBoolean();
            Entity e = mc.level.getEntity(id);
            if (e == null || e instanceof CameraEntity) return;
            e.syncPacketPositionCodec(x, y, z);
            if (fastForwarding || seeking) {
                e.moveTo(x, y, z, yaw, pitch);
                e.setYHeadRot(head);
            } else {
                e.lerpTo(x, y, z, yaw, pitch, 1, false);
                e.lerpHeadTo(head, 1);
            }
            e.setOnGround(onGround);
        } catch (IOException ignored) {}
    }

    /** A recording continued after a pause: the snapshot that follows replaces what is there. */
    private void clearWorld() {
        ClientLevel level = mc.level;
        if (level == null) return;
        removeReplayedEntities(level);
    }

    // ------------------------------------------------------------------ hooks called from mixins

    /** Called after the game's timer advanced each frame. Returns the number of ticks to run. */
    public int onTimerUpdate(Timer timer, int vanillaTicks) {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0 : (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        dt = Math.max(0, Math.min(dt, 0.25));
        int total = getTotalTicks();
        if (seeking) return 0;

        if (clockDriver != null) {
            replayTime = Math.max(0, Math.min(clockDriver.nextFrameTime(), total - 1));
        } else if (!paused) {
            replayTime += dt * 20.0 * effectiveSpeed();
            if (replayTime >= total - 1) {
                replayTime = total - 1;
                paused = true;
            }
        }

        int target = (int) Math.floor(replayTime);
        if (target < currentTick) {
            // the clock moved backwards (export start or keyframe preview): reload
            double keep = replayTime;
            seek(target);
            replayTime = keep;
        }
        int needed = target - currentTick;
        if (needed > MAX_WORLD_TICKS_PER_FRAME) {
            fastForwardTo(target - 1);
            needed = target - currentTick;
        }
        pendingWorldTicks = Math.max(0, needed);

        int inputTicks = 0;
        if (clockDriver == null) {
            inputAccumulator += dt * 20.0;
            inputTicks = (int) inputAccumulator;
            inputAccumulator -= inputTicks;
            inputTicks = Math.min(inputTicks, 5);
        }
        float partial = (float) (replayTime - target);
        timer.partialTick = Mth.clamp(partial, 0.0f, 0.9999f);
        return Math.max(pendingWorldTicks, inputTicks);
    }

    private double effectiveSpeed() {
        KeyframeTrack speedTrack = editor.track(TrackType.SPEED);
        if (applyKeyframes && speedTrack.isActive()) {
            double[] v = speedTrack.evaluate(replayTime);
            if (v != null) return Math.max(0.01, v[0]) * speed;
        }
        return speed;
    }

    /** Called at the start of every client tick. Returns true if this tick advances the replay. */
    public boolean onTickStart() {
        worldTick = false;
        if (seeking || mc.level == null) return false;
        if (pendingWorldTicks > 0 && currentTick < getTotalTicks() - 1) {
            pendingWorldTicks--;
            currentTick++;
            deliverTick(currentTick);
            worldTick = true;
        }
        return worldTick;
    }

    public void onTickEnd() {
        worldTick = false;
        if (mc.level != null) followDimensionChange();
        if (mc.screen instanceof ReceivingLevelScreen) mc.setScreen(new ReplayEditorScreen());
    }

    /** Called every frame before the world is rendered. */
    public void onRenderFrame(float partialTicks) {
        if (mc.level == null) return;
        CameraEntity camera = getCamera();
        if (camera == null) return;
        double time = replayTime;

        // Spectating
        if (spectatingId != Integer.MIN_VALUE) {
            Entity e = getSpectating();
            if (e instanceof LivingEntity && !e.isRemoved()) {
                if (mc.getCameraEntity() != e) mc.setCameraEntity(e);
                camera.setPos(e.getX(), e.getY(), e.getZ());
                camera.snapInterpolation();
            } else if (mc.getCameraEntity() != camera) {
                mc.setCameraEntity(camera);
            }
        } else if (mc.getCameraEntity() != camera) {
            mc.setCameraEntity(camera);
        }

        // Camera path
        KeyframeTrack camTrack = editor.track(TrackType.CAMERA);
        boolean driving = spectatingId == Integer.MIN_VALUE && (clockDriver != null || (applyKeyframes && !paused));
        boolean followPath = driving && camTrack.isActive();
        CameraRig rig = spectatingId == Integer.MIN_VALUE && (driving || (rigPreview && applyKeyframes)) ? rigAt(time)
            : null;
        double[] rigCamera = rig == null ? null : evaluateRig(rig, time, partialTicks);
        if (rigCamera != null) {
            double weight = rig.weight(time);
            if (camTrack.isActive() && weight < 1) {
                double[] path = camTrack.evaluate(time);
                for (int i = 0; i < 6; i++) {
                    double from = path[i], to = rigCamera[i];
                    if (i >= 3) to = from + Mth.wrapDegrees(to - from);
                    rigCamera[i] = from + (to - from) * weight;
                }
            }
            camera.setCameraPosition(rigCamera[0], rigCamera[1], rigCamera[2], (float) rigCamera[3], (float) rigCamera[4]);
            currentRoll = (float) rigCamera[5];
            cameraController.reset();
        } else if (followPath) {
            double[] v = camTrack.evaluate(time);
            camera.setCameraPosition(v[0], v[1], v[2], (float) v[3], (float) v[4]);
            currentRoll = (float) v[5];
            cameraController.reset();
        } else {
            if (spectatingId == Integer.MIN_VALUE && clockDriver == null) cameraController.update(camera);
            currentRoll = editor.visuals.cameraRoll;
        }

        // FOV
        KeyframeTrack fovTrack = editor.track(TrackType.FOV);
        if (fovTrack.isActive() && (clockDriver != null || applyKeyframes)) {
            currentFov = (float) fovTrack.evaluate(time)[0];
        } else if (editor.visuals.overrideFov) {
            currentFov = editor.visuals.fov;
        } else {
            currentFov = Float.NaN;
        }

        // Camera shake
        KeyframeTrack shakeTrack = editor.track(TrackType.SHAKE);
        double shake = 0;
        if (shakeTrack.isActive() && (clockDriver != null || applyKeyframes)) shake = shakeTrack.evaluate(time)[0];
        else if (editor.visuals.cameraShake) shake = 1;
        if (shake > 0 && spectatingId == Integer.MIN_VALUE) {
            double t = time / 20.0 * editor.visuals.shakeFrequency;
            float amp = (float) (shake * editor.visuals.shakeAmplitude);
            // applied as a view-space rotation in the camera setup, so it never accumulates into the camera itself
            shakeYaw = (float) (Math.sin(t * 7.1) * 0.6 + Math.sin(t * 13.7) * 0.4) * amp;
            shakePitch = (float) (Math.sin(t * 5.3 + 1.3) * 0.6 + Math.sin(t * 11.9 + 0.7) * 0.4) * amp;
        } else {
            shakeYaw = 0;
            shakePitch = 0;
        }

        // Time of day
        KeyframeTrack timeTrack = editor.track(TrackType.TIME);
        long overrideTime = -1;
        if (timeTrack.isActive() && (clockDriver != null || applyKeyframes)) {
            overrideTime = (long) timeTrack.evaluate(time)[0];
        } else if (editor.visuals.overrideTime) {
            overrideTime = editor.visuals.time;
        }
        if (overrideTime >= 0) {
            mc.level.setDayTime(((overrideTime % 24000) + 24000) % 24000);
            timeOverridden = true;
        } else if (timeOverridden) {
            timeOverridden = false;
            if (recordedWorldTime >= 0) {
                boolean cycle = mc.level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
                long t = recordedWorldTime + (cycle ? Math.max(0, currentTick - recordedWorldTimeTick) : 0);
                mc.level.setDayTime(t);
            }
        }

        // Weather
        KeyframeTrack weatherTrack = editor.track(TrackType.WEATHER);
        Visuals.Weather weather = editor.visuals.weather;
        if (weatherTrack.isActive() && (clockDriver != null || applyKeyframes)) {
            double[] w = weatherTrack.evaluate(time);
            mc.level.setRainLevel((float) Mth.clamp(w[0], 0, 1));
            mc.level.setThunderLevel((float) Mth.clamp(w[1], 0, 1));
            weatherOverridden = true;
        } else if (weather != Visuals.Weather.RECORDED) {
            float rain = weather == Visuals.Weather.CLEAR ? 0 : 1;
            float thunder = weather == Visuals.Weather.THUNDER ? 1 : 0;
            mc.level.setRainLevel(rain);
            mc.level.setThunderLevel(thunder);
            weatherOverridden = true;
        } else if (weatherOverridden) {
            weatherOverridden = false;
            mc.level.setRainLevel(recordedRain);
            mc.level.setThunderLevel(recordedThunder);
        }

        VisualsApplier.apply(editor.visuals, clockDriver != null);
        // the light map is only marked for an update on world ticks, which do not run while paused
        mc.gameRenderer.lightTexture().updateLightTexture = true;
    }

    /** True if the entity should not be rendered (render filter / hidden entities). */
    public boolean isHidden(Entity entity) {
        Visuals v = editor.visuals;
        if (entity instanceof CameraEntity) return true;
        if (entity instanceof Player) {
            if (!v.renderPlayers) return true;
        } else if (!v.renderEntities) {
            return true;
        }
        return !v.hiddenEntities.isEmpty() && v.hiddenEntities.contains(entityKey(entity));
    }

    public static String entityKey(Entity entity) {
        if (entity instanceof Player p) return p.getUUID().toString();
        return "#" + entity.getId();
    }

    public void toggleHidden(Entity entity) {
        String key = entityKey(entity);
        if (!editor.visuals.hiddenEntities.remove(key)) editor.visuals.hiddenEntities.add(key);
        markEditorDirty();
    }

    /** Called when a block of a chunk really changed. */
    public static void onChunkChanged(int chunkX, int chunkZ) {
        ReplayHandler h = active;
        if (h != null) h.chunkChanges.merge(ReplayIndex.chunkKey(chunkX, chunkZ), h.currentTick, Math::max);
    }

    /** The camera preset in use at a tick: the latest one to start that covers it. */
    public CameraRig rigAt(double tick) {
        CameraRig found = null;
        for (CameraRig r : editor.rigs) {
            if (r.covers(tick) && (found == null || r.start >= found.start)) found = r;
        }
        return found;
    }

    public void setRigPreview(boolean preview) {
        rigPreview = preview;
    }

    /** The entity a camera preset follows, or null while it is not in the world. */
    public Entity findEntity(String key) {
        if (mc.level == null || key == null || key.isEmpty()) return null;
        if (key.charAt(0) == '#') {
            try {
                return mc.level.getEntity(Integer.parseInt(key.substring(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            UUID uuid = UUID.fromString(key);
            for (AbstractClientPlayer p : mc.level.players()) if (p.getUUID().equals(uuid)) return p;
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /** The target's feet, heading and height at this frame: x, y, z, yaw, height. */
    private static double[] targetPose(Entity e, float partialTicks) {
        double yaw = e.getViewYRot(partialTicks);
        if (e instanceof LivingEntity l) yaw = Mth.rotLerp(partialTicks, l.yBodyRotO, l.yBodyRot);
        return new double[] { Mth.lerp(partialTicks, e.xo, e.getX()), Mth.lerp(partialTicks, e.yo, e.getY()),
            Mth.lerp(partialTicks, e.zo, e.getZ()), yaw, e.getBbHeight() };
    }

    /** The camera of a preset at a time, or null when its target was never seen. */
    private double[] evaluateRig(CameraRig rig, double time, float partialTicks) {
        Entity e = findEntity(rig.target);
        double[] pose = e != null && !e.isRemoved() ? targetPose(e, partialTicks) : null;
        double dt = (time - rigTargetTime) / 20.0;
        if (rigTargetOf != rig || rigTarget == null || dt < 0 || dt > 2) {
            if (pose != null) rigTarget = pose;
            else if (rigTargetOf != rig) rigTarget = null;
        } else if (pose != null) {
            double a = rig.smoothing <= 0 ? 1 : 1 - Math.exp(-dt / rig.smoothing);
            for (int i = 0; i < 5; i++) {
                double delta = pose[i] - rigTarget[i];
                if (i == 3) delta = Mth.wrapDegrees(delta);
                rigTarget[i] += delta * a;
            }
        }
        rigTargetOf = rig;
        rigTargetTime = time;
        if (rigTarget == null) return null;
        return rig.evaluate(time, rigTarget[0], rigTarget[1], rigTarget[2], rigTarget[3], rigTarget[4]);
    }

    /**
     * Sets a preset up so that it starts with the camera where it is now, looking at its target. Returns false when
     * the target is not in the world.
     */
    public boolean fitRigToCamera(CameraRig rig) {
        Entity e = findEntity(rig.target);
        CameraEntity camera = getCamera();
        if (e == null || camera == null) return false;
        double[] t = targetPose(e, 1.0F);
        double camX = camera.eyeX(), camY = camera.eyeY(), camZ = camera.eyeZ();
        float camYaw = camera.getYRot(), camPitch = camera.getXRot();
        double dx = camX - t[0], dy = camY - t[1], dz = camZ - t[2];
        if (dx * dx + dy * dy + dz * dz < 1) {
            // the camera is on the target (it was spectating it): start a little behind and above it instead
            double h = Math.toRadians(t[3]);
            dx = Math.sin(h) * 5;
            dy = 2.5;
            dz = -Math.cos(h) * 5;
            camX = t[0] + dx;
            camY = t[1] + dy;
            camZ = t[2] + dz;
            double[] look = CameraRig.lookAt(camX, camY, camZ, t[0], t[1] + t[4] * rig.aimHeight, t[2]);
            camYaw = (float) look[0];
            camPitch = (float) look[1];
        }
        switch (rig.type) {
            case ORBIT:
                rig.radius = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
                rig.height = dy;
                rig.startAngle = Math.toDegrees(Math.atan2(-dx, dz));
                break;
            case FOLLOW:
                if (rig.turnWithTarget) {
                    double h = Math.toRadians(t[3]);
                    double fx = -Math.sin(h), fz = Math.cos(h);
                    rig.offsetForward = dx * fx + dz * fz;
                    rig.offsetRight = dx * -fz + dz * fx;
                } else {
                    rig.offsetRight = dx;
                    rig.offsetForward = dz;
                }
                rig.offsetUp = dy;
                break;
            default:
                rig.x = camX;
                rig.y = camY;
                rig.z = camZ;
                break;
        }
        rig.yaw = Mth.wrapDegrees(camYaw - (rig.type == CameraRig.Type.FOLLOW && rig.turnWithTarget ? t[3] : 0));
        rig.pitch = camPitch;
        rig.roll = editor.visuals.cameraRoll;
        rigTargetOf = null;
        return true;
    }

    /** Whether a disconnect for this listener is ignored, as it belongs to a replay. */
    public static boolean ignoresDisconnect(Object listener) {
        ReplayHandler h = active;
        if (h == null || h.connection == null || h.connection.listener != listener) return false;
        if (h.ignoredDisconnects++ < 5) Flashback120.LOG.warn("Ignored a disconnect in the replay");
        return true;
    }

    /** Called when the client world disappears without us closing it (e.g. a crash in a mod). */
    public static void onWorldGone() {
        ReplayHandler h = active;
        if (h != null && !h.seeking && h.mc.level == null) {
            Flashback120.LOG.warn("Replay world unloaded unexpectedly, closing replay");
            h.close(true);
        }
    }

    public static Screen returnScreen() {
        return new ReplaySelectScreen(new TitleScreen());
    }
}
