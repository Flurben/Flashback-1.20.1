package com.flurben.flashback120.recording;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.tags.TagNetworkSerialization;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Score;
import net.minecraft.world.scores.Scoreboard;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.net.PacketBuilder;
import com.flurben.flashback120.net.PacketCodec;
import com.mojang.datafixers.util.Pair;

/**
 * The state of the client world as records, for recordings that start after joining (or continue after a pause): what
 * the server sent so far, rebuilt from what the client knows. Chunks are serialized on the client thread, which is the
 * only thread allowed to read them, and their bytes are written out later by the recording's writer thread.
 */
public final class WorldSnapshot {

    public static final class Rec {

        public final byte type;
        public final byte[] data;
        public final Future<List<Rec>> later;

        public Rec(byte type, byte[] data) {
            this.type = type;
            this.data = data;
            this.later = null;
        }

        Rec(Future<List<Rec>> later) {
            this.type = -1;
            this.data = null;
            this.later = later;
        }
    }

    public final List<Rec> records = new ArrayList<>();

    private WorldSnapshot() {}

    private void packet(Packet<?> packet) {
        try {
            byte[] data = PacketCodec.encode(packet);
            if (data != null) records.add(new Rec(ReplayFormat.REC_PACKET, data));
        } catch (Throwable t) {
            Flashback120.LOG.debug("Unable to write snapshot packet {}", packet.getClass().getSimpleName(), t);
        }
    }

    private void raw(byte[] data) {
        if (data != null) records.add(new Rec(ReplayFormat.REC_PACKET, data));
    }

    public static WorldSnapshot create(Minecraft mc) {
        WorldSnapshot s = new WorldSnapshot();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        ClientPacketListener connection = mc.getConnection();
        if (level == null || player == null || connection == null) return s;

        // Login: the registries the server sent and the player's dimension
        RegistryAccess.Frozen remote = connection.registryAccess.getLayer(
            net.minecraft.client.multiplayer.ClientRegistryLayer.REMOTE);
        GameType mode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : GameType.SURVIVAL;
        GameType previous = mc.gameMode != null ? mc.gameMode.getPreviousPlayerMode() : null;
        s.packet(new ClientboundLoginPacket(
            player.getId(),
            level.getLevelData().isHardcore(),
            mode,
            previous,
            connection.levels(),
            remote,
            level.dimensionTypeId(),
            level.dimension(),
            level.getBiomeManager().biomeZoomSeed,
            0,
            mc.options.getEffectiveRenderDistance(),
            level.getServerSimulationDistance(),
            player.isReducedDebugInfo(),
            player.shouldShowDeathScreen(),
            level.isDebug(),
            level.getLevelData().isFlat,
            player.getLastDeathLocation(),
            player.getPortalCooldown()));

        // Enabled features and tags
        s.packet(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(level.enabledFeatures())));
        Map<ResourceKey<? extends Registry<?>>, TagNetworkSerialization.NetworkPayload> tags = new HashMap<>();
        connection.registryAccess().registries().forEach(entry -> {
            try {
                tags.put(entry.key(), TagNetworkSerialization.serializeToNetwork(entry.value()));
            } catch (Throwable ignored) {}
        });
        s.packet(new ClientboundUpdateTagsPacket(tags));

        // Player list
        Map<UUID, PacketBuilder.InfoEntry> info = new LinkedHashMap<>();
        for (PlayerInfo p : connection.getOnlinePlayers()) {
            info.put(p.getProfile().getId(), PacketBuilder.InfoEntry.of(p, connection.getListedOnlinePlayers().contains(p)));
        }
        for (AbstractClientPlayer p : level.players()) {
            if (!info.containsKey(p.getUUID())) {
                info.put(p.getUUID(), new PacketBuilder.InfoEntry(p.getGameProfile(), false, 0, 0, null));
            }
        }
        if (!info.isEmpty()) s.raw(PacketBuilder.playerInfo(info.values()));

        // Tab list, boss bars and scoreboard
        Component header = mc.gui.getTabList().header, footer = mc.gui.getTabList().footer;
        if (header != null || footer != null) {
            s.packet(new ClientboundTabListPacket(
                header != null ? header : Component.empty(),
                footer != null ? footer : Component.empty()));
        }
        for (LerpingBossEvent event : mc.gui.getBossOverlay().events.values()) {
            s.packet(ClientboundBossEventPacket.createAddPacket(event));
        }
        Scoreboard scoreboard = level.getScoreboard();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            s.packet(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        for (Objective objective : scoreboard.getObjectives()) {
            s.packet(new ClientboundSetObjectivePacket(objective, 0));
            for (Score score : scoreboard.getPlayerScores(objective)) {
                s.packet(new ClientboundSetScorePacket(
                    ServerScoreboard.Method.CHANGE, objective.getName(), score.getOwner(), score.getScore()));
            }
        }
        for (int slot = 0; slot < Scoreboard.DISPLAY_SLOTS; slot++) {
            Objective objective = scoreboard.getDisplayObjective(slot);
            if (objective != null) s.packet(new ClientboundSetDisplayObjectivePacket(slot, objective));
        }

        // Level state
        s.packet(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        s.packet(new ClientboundSetTimePacket(
            level.getGameTime(), level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        s.packet(new ClientboundSetDefaultSpawnPositionPacket(level.getSharedSpawnPos(), level.getSharedSpawnAngle()));
        if (level.isRaining()) {
            s.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0));
            s.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1)));
            s.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1)));
        }

        // Chunks, nearest first. The packets copy the chunk data now; their bytes are made on the writer thread.
        List<Packet<?>> chunkPackets = new ArrayList<>();
        AtomicReferenceArray<LevelChunk> chunks = level.getChunkSource().storage.chunks;
        int cx = player.getBlockX() >> 4, cz = player.getBlockZ() >> 4;
        List<LevelChunk> sorted = new ArrayList<>();
        for (int i = 0; i < chunks.length(); i++) {
            LevelChunk chunk = chunks.get(i);
            if (chunk != null) sorted.add(chunk);
        }
        sorted.sort((a, b) -> Integer.compare(distance(a, cx, cz), distance(b, cx, cz)));
        for (LevelChunk chunk : sorted) {
            try {
                chunkPackets.add(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
            } catch (Throwable t) {
                Flashback120.LOG.debug("Unable to snapshot chunk {}", chunk.getPos(), t);
            }
        }
        s.records.add(new Rec(CompletableFuture.supplyAsync(() -> {
            List<Rec> out = new ArrayList<>(chunkPackets.size());
            for (Packet<?> p : chunkPackets) {
                byte[] data = PacketCodec.encode(p);
                if (data != null) out.add(new Rec(ReplayFormat.REC_PACKET, data));
            }
            return out;
        })));

        // Entities other than the recording player (which LocalPlayerRecorder writes)
        for (Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            s.entity(e);
        }
        for (Entity e : level.entitiesForRendering()) {
            if (e == player) continue;
            if (e.isVehicle()) s.packet(new ClientboundSetPassengersPacket(e));
            if (e instanceof Mob mob && mob.getLeashHolder() != null) {
                s.packet(new ClientboundSetEntityLinkPacket(mob, mob.getLeashHolder()));
            }
        }

        // Maps
        for (Map.Entry<String, MapItemSavedData> entry : level.getAllMapData().entrySet()) {
            try {
                int id = Integer.parseInt(entry.getKey().substring(entry.getKey().indexOf('_') + 1));
                MapItemSavedData data = entry.getValue();
                List<MapDecoration> decorations = new ArrayList<>();
                data.getDecorations().forEach(decorations::add);
                byte[] colors = data.colors.clone();
                s.packet(new ClientboundMapItemDataPacket(id, data.scale, data.locked, decorations,
                    new MapItemSavedData.MapPatch(0, 0, 128, 128, colors)));
            } catch (Throwable ignored) {}
        }
        return s;
    }

    private static int distance(LevelChunk chunk, int cx, int cz) {
        int dx = chunk.getPos().x - cx, dz = chunk.getPos().z - cz;
        return dx * dx + dz * dz;
    }

    private void entity(Entity e) {
        try {
            if (e instanceof Player p) {
                packet(new ClientboundAddPlayerPacket(p));
            } else {
                packet(e.getAddEntityPacket());
            }
            packet(new ClientboundRotateHeadPacket(e, (byte) Mth.floor(e.getYHeadRot() * 256.0F / 360.0F)));
            var data = e.getEntityData().getNonDefaultValues();
            if (data != null && !data.isEmpty()) packet(new ClientboundSetEntityDataPacket(e.getId(), data));
            if (e.getDeltaMovement().lengthSqr() > 0) packet(new ClientboundSetEntityMotionPacket(e));
            if (e instanceof LivingEntity living) {
                Collection<net.minecraft.world.entity.ai.attributes.AttributeInstance> attributes =
                    living.getAttributes().getSyncableAttributes();
                if (!attributes.isEmpty()) packet(new ClientboundUpdateAttributesPacket(e.getId(), attributes));
                List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    ItemStack stack = living.getItemBySlot(slot);
                    if (!stack.isEmpty()) equipment.add(Pair.of(slot, stack.copy()));
                }
                if (!equipment.isEmpty()) packet(new ClientboundSetEquipmentPacket(e.getId(), equipment));
                for (MobEffectInstance effect : living.getActiveEffects()) {
                    packet(new ClientboundUpdateMobEffectPacket(e.getId(), effect));
                }
            }
        } catch (Throwable t) {
            Flashback120.LOG.debug("Unable to snapshot entity {}", e, t);
        }
    }
}
