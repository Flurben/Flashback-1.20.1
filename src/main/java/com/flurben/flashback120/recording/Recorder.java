package com.flurben.flashback120.recording;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.Connection;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.format.RecordWriter;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.net.PacketIds;
import com.flurben.flashback120.net.PacketCodec;
import com.flurben.flashback120.util.Notifications;
import com.mojang.authlib.GameProfile;

/** A single in-progress recording. */
public final class Recorder {

    public static final String WORK_STREAM = ReplayFormat.ENTRY_STREAM;
    public static final String WORK_META = ReplayFormat.ENTRY_META;
    public static final String WORK_THUMB = ReplayFormat.ENTRY_THUMBNAIL;

    private final Minecraft mc;
    private final Connection connection;
    private final boolean fromJoin;
    private final File workDir;
    private final RecordQueue queue;
    private final ReplayMeta meta = new ReplayMeta();
    private final LocalPlayerRecorder localPlayer;
    private final Object lock = new Object();

    private final List<byte[]> preStartBuffer = new ArrayList<>();
    private CaptureHandler handler;
    private volatile boolean started;
    private volatile boolean paused;
    private boolean finished;
    private long lastMetaSave;
    private int pendingThumbnailTicks = 100; // after the "started recording" toast is gone
    private int failures;

    public Recorder(Minecraft mc, Connection connection, boolean fromJoin, File recordingsRoot) throws IOException {
        this.mc = mc;
        this.connection = connection;
        this.fromJoin = fromJoin;
        this.workDir = new File(recordingsRoot, UUID.randomUUID().toString());
        if (!workDir.mkdirs()) throw new IOException("Unable to create " + workDir);
        this.queue = new RecordQueue(new RecordWriter(new File(workDir, WORK_STREAM), false));
        this.localPlayer = new LocalPlayerRecorder(this);

        GameProfile profile = mc.getUser().getGameProfile();
        meta.mcVersion = SharedConstants.getCurrentVersion().getName();
        meta.modVersion = Flashback120.version();
        meta.recordedBy = profile.getName();
        meta.recordedByUuid = profile.getId() == null ? "" : profile.getId().toString();
        meta.singleplayer = connection.isMemoryConnection();
        IntegratedServer server = mc.getSingleplayerServer();
        ServerData serverData = mc.getCurrentServer();
        if (server != null) {
            meta.worldName = server.getWorldData().getLevelName();
        } else if (serverData != null) {
            meta.serverAddress = serverData.ip;
            meta.worldName = serverData.name;
        }
        meta.name = meta.worldName == null || meta.worldName.isEmpty() ? "Replay" : meta.worldName;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            meta.mods.add(mod.getMetadata().getId() + "@" + mod.getMetadata().getVersion().getFriendlyString());
        }
    }

    public File getWorkDir() {
        return workDir;
    }

    public ReplayMeta getMeta() {
        return meta;
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isPaused() {
        return paused;
    }

    public int getTicks() {
        return queue.getTicks();
    }

    public Connection getConnection() {
        return connection;
    }

    /** Installs the capture handler on the network thread. */
    public void attach() throws Exception {
        Callable<Void> task = () -> {
            handler = CaptureHandler.create(Recorder.this, connection);
            handler.install(connection.channel.pipeline());
            return null;
        };
        if (connection.channel.eventLoop().inEventLoop()) {
            task.call();
        } else {
            Future<Void> f = connection.channel.eventLoop().submit(task);
            f.get(10, TimeUnit.SECONDS);
        }
    }

    /** Called from the network thread for every clientbound packet. */
    void capture(byte[] data) {
        synchronized (lock) {
            if (finished) return;
            if (!started) {
                preStartBuffer.add(data);
                return;
            }
            if (paused) return;
            queue.add(ReplayFormat.REC_PACKET, data);
        }
    }

    void writeRaw(byte type, byte[] data) {
        synchronized (lock) {
            if (finished) return;
            queue.add(type, data);
        }
    }

    /** Starts writing. Called on the client thread once the player is in a world. */
    private void begin() {
        int joinIndex = -1;
        synchronized (lock) {
            if (fromJoin) {
                for (int i = 0; i < preStartBuffer.size(); i++) {
                    if (PacketCodec.peekId(preStartBuffer.get(i)) == PacketIds.LOGIN) {
                        joinIndex = i;
                        break;
                    }
                }
            }
        }
        if (joinIndex < 0) {
            // Packets that arrived before the handler and wait for the client thread must be in the snapshot
            while (mc.pollTask()) {}
        }
        synchronized (lock) {
            if (joinIndex >= 0) {
                // We saw the whole login: the stream itself is an exact record of the world
                for (int i = joinIndex; i < preStartBuffer.size(); i++) {
                    queue.add(ReplayFormat.REC_PACKET, preStartBuffer.get(i));
                }
            } else {
                addSnapshot();
                for (byte[] data : preStartBuffer) queue.add(ReplayFormat.REC_PACKET, data);
            }
            preStartBuffer.clear();
            started = true;
            localPlayer.forceRespawn();
        }
        saveMeta();
        Flashback120.LOG.info("Recording started ({})", joinIndex >= 0 ? "from join" : "snapshot");
    }

    private void addSnapshot() {
        final long start = System.nanoTime();
        WorldSnapshot snapshot = WorldSnapshot.create(mc);
        queue.addAll(snapshot.records);
        Flashback120.LOG.info("World snapshot held up the game for {} ms", (System.nanoTime() - start) / 1_000_000L);
        queue.addAction(() -> Flashback120.LOG
            .info("World snapshot finished after {} ms", (System.nanoTime() - start) / 1_000_000L));
    }

    /** Client tick (end). */
    public void tick() {
        if (finished) return;
        if (!started) {
            if (mc.level != null && mc.player != null) {
                try {
                    begin();
                } catch (Throwable e) {
                    Flashback120.LOG.error("Unable to start recording", e);
                    Notifications.show("Flashback", "Unable to start recording: " + e);
                    RecordingManager.INSTANCE.cancel();
                }
            }
            return;
        }
        if (paused || mc.isPaused() || mc.level == null) return;
        try {
            localPlayer.tick(mc);
            synchronized (lock) {
                queue.addTick();
            }
        } catch (Throwable e) {
            if (failures++ < 5) Flashback120.LOG.error("Recording tick failed", e);
        }
        long now = System.currentTimeMillis();
        if (now - lastMetaSave > 10000) saveMeta();
        if (pendingThumbnailTicks > 0) pendingThumbnailTicks--;
    }

    public boolean wantsThumbnail() {
        return started && pendingThumbnailTicks == 0 && !new File(workDir, WORK_THUMB).exists();
    }

    public void saveThumbnail(byte[] png) {
        try (FileOutputStream out = new FileOutputStream(new File(workDir, WORK_THUMB))) {
            out.write(png);
        } catch (IOException e) {
            Flashback120.LOG.debug("Unable to save thumbnail", e);
        }
        pendingThumbnailTicks = -1;
    }

    void onDimensionChanged(String newDimension) {
        if (Config.get().markDimensionChanges) {
            ReplayMeta.Marker m = new ReplayMeta.Marker(getTicks(), 0xFFAA00AA, "Changed dimension: " + newDimension);
            synchronized (meta) {
                meta.markers.add(m);
            }
        }
    }

    public void addMarker(int color, String description, boolean withPosition) {
        ReplayMeta.Marker m = new ReplayMeta.Marker(getTicks(), color, description);
        if (withPosition && mc.player != null) {
            m.hasPosition = true;
            m.x = mc.player.getX();
            m.y = mc.player.getY();
            m.z = mc.player.getZ();
            m.dimensionKey = mc.player.level().dimension().location().toString();
        }
        synchronized (meta) {
            meta.markers.add(m);
        }
    }

    public void setPaused(boolean pause) {
        if (pause == paused || !started) return;
        if (pause) {
            paused = true;
            return;
        }
        while (mc.pollTask()) {}
        synchronized (lock) {
            if (mc.level != null && mc.player != null) {
                queue.add(ReplayFormat.REC_CLEAR_WORLD, new byte[0]);
                addSnapshot();
            }
            localPlayer.forceRespawn();
            paused = false;
        }
    }

    public void saveMeta() {
        lastMetaSave = System.currentTimeMillis();
        String json;
        synchronized (meta) {
            meta.durationTicks = queue.getTicks();
            json = meta.toJson();
        }
        try (FileOutputStream out = new FileOutputStream(new File(workDir, WORK_META))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Flashback120.LOG.debug("Unable to save metadata", e);
        }
    }

    /** Stops recording and closes files. The work directory is left for packaging or recovery. */
    public void stop() {
        synchronized (lock) {
            if (finished) return;
            finished = true;
        }
        try {
            if (connection.channel != null && connection.channel.isOpen()) {
                connection.channel.eventLoop().execute(() -> CaptureHandler.uninstall(connection.channel.pipeline()));
            }
        } catch (Throwable ignored) {}
        queue.close();
        saveMeta();
    }
}
