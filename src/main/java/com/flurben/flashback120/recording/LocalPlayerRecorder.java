package com.flurben.flashback120.recording;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddPlayerPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.net.PacketCodec;
import com.mojang.datafixers.util.Pair;

/**
 * The server never sends a player its own movement, arm swings, equipment or block breaking, so these are made every
 * tick from the local player and written into the recording. During playback the recording player is a normal remote
 * player entity with its original entity id, so every packet the server sent about it applies as usual.
 */
public final class LocalPlayerRecorder {

    private final Recorder recorder;

    private LocalPlayer lastPlayer;
    private boolean forceSpawn = true;
    private final ItemStack[] equipment = new ItemStack[EquipmentSlot.values().length];
    private boolean wasSwinging;
    private int lastSwingTime;
    private BlockPos lastBreakPos;
    private int lastBreakStage = -1;
    private double lx, ly, lz;
    private float lyaw, lpitch, lhead;
    private boolean lground;
    private int ticksSinceMove;
    private int lastHotbarHash;
    private String lastDimension;

    public LocalPlayerRecorder(Recorder recorder) {
        this.recorder = recorder;
    }

    public void forceRespawn() {
        forceSpawn = true;
    }

    public void tick(Minecraft mc) throws IOException {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        String dimension = p.level().dimension().location().toString();
        if (p != lastPlayer || forceSpawn) {
            if (lastPlayer != null && lastDimension != null && !dimension.equals(lastDimension)) {
                recorder.onDimensionChanged(dimension);
            }
            lastPlayer = p;
            forceSpawn = false;
            spawn(p);
        }
        lastDimension = dimension;
        int id = p.getId();

        // Movement (every tick when moving, at least once per second otherwise)
        ticksSinceMove++;
        if (p.getX() != lx || p.getY() != ly
            || p.getZ() != lz
            || p.getYRot() != lyaw
            || p.getXRot() != lpitch
            || p.getYHeadRot() != lhead
            || p.onGround() != lground
            || ticksSinceMove >= 20) {
            writeMove(p);
        }

        // Equipment (the server only tells the other players)
        List<Pair<EquipmentSlot, ItemStack>> changed = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack cur = p.getItemBySlot(slot);
            ItemStack last = equipment[slot.ordinal()];
            if (last == null || !ItemStack.matches(cur, last)) {
                equipment[slot.ordinal()] = cur.copy();
                changed.add(Pair.of(slot, cur.copy()));
            }
        }
        if (!changed.isEmpty()) packet(new ClientboundSetEquipmentPacket(id, changed));

        // Arm swing
        if (p.swinging && (!wasSwinging || p.swingTime < lastSwingTime)) {
            int action = p.swingingArm == InteractionHand.OFF_HAND ? ClientboundAnimatePacket.SWING_OFF_HAND
                : ClientboundAnimatePacket.SWING_MAIN_HAND;
            packet(new ClientboundAnimatePacket(p, action));
        }
        wasSwinging = p.swinging;
        lastSwingTime = p.swingTime;

        // Block breaking progress (the server does not echo our own breaking animation)
        MultiPlayerGameMode gameMode = mc.gameMode;
        if (gameMode != null) {
            int stage = gameMode.isDestroying ? Math.min(9, (int) (gameMode.destroyProgress * 10.0F)) : -1;
            BlockPos pos = gameMode.destroyBlockPos.immutable();
            if (stage != lastBreakStage || !pos.equals(lastBreakPos)) {
                if (lastBreakStage >= 0 && lastBreakPos != null && !pos.equals(lastBreakPos)) {
                    packet(new ClientboundBlockDestructionPacket(id, lastBreakPos, -1));
                }
                if (stage >= 0 || lastBreakStage >= 0) packet(new ClientboundBlockDestructionPacket(id, pos, stage));
                lastBreakStage = stage;
                lastBreakPos = pos;
            }
        }

        if (Config.get().recordHotbar) {
            byte[] hotbar = HotbarState.capture(p);
            int h = Arrays.hashCode(hotbar);
            if (h != lastHotbarHash) {
                lastHotbarHash = h;
                recorder.writeRaw(ReplayFormat.REC_HOTBAR, hotbar);
            }
        }
    }

    private void spawn(LocalPlayer p) throws IOException {
        int id = p.getId();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new DataOutputStream(bos).writeInt(id);
        recorder.writeRaw(ReplayFormat.REC_LOCAL_PLAYER, bos.toByteArray());

        packet(new ClientboundAddPlayerPacket(p));
        packet(new ClientboundRotateHeadPacket(p, (byte) Mth.floor(p.getYHeadRot() * 256.0F / 360.0F)));
        List<SynchedEntityData.DataValue<?>> data = new ArrayList<>();
        for (SynchedEntityData.DataItem<?> item : p.getEntityData().itemsById.values()) data.add(item.value());
        if (!data.isEmpty()) packet(new ClientboundSetEntityDataPacket(id, data));
        var attributes = p.getAttributes().getSyncableAttributes();
        if (!attributes.isEmpty()) packet(new ClientboundUpdateAttributesPacket(id, attributes));
        List<Pair<EquipmentSlot, ItemStack>> all = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack cur = p.getItemBySlot(slot);
            equipment[slot.ordinal()] = cur.copy();
            all.add(Pair.of(slot, cur.copy()));
        }
        packet(new ClientboundSetEquipmentPacket(id, all));
        wasSwinging = false;
        lastBreakStage = -1;
        lastBreakPos = null;
        lastHotbarHash = 0;
        writeMove(p);
    }

    private void writeMove(LocalPlayer p) throws IOException {
        ticksSinceMove = 0;
        lx = p.getX();
        ly = p.getY();
        lz = p.getZ();
        lyaw = p.getYRot();
        lpitch = p.getXRot();
        lhead = p.getYHeadRot();
        lground = p.onGround();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(48);
        DataOutputStream dos = new DataOutputStream(bos);
        dos.writeInt(p.getId());
        dos.writeDouble(lx);
        dos.writeDouble(ly);
        dos.writeDouble(lz);
        dos.writeFloat(lyaw);
        dos.writeFloat(lpitch);
        dos.writeFloat(lhead);
        dos.writeBoolean(lground);
        recorder.writeRaw(ReplayFormat.REC_LOCAL_MOVE, bos.toByteArray());
    }

    private void packet(Packet<?> packet) {
        try {
            byte[] data = PacketCodec.encode(packet);
            if (data != null) recorder.writeRaw(ReplayFormat.REC_PACKET, data);
        } catch (Throwable t) {
            Flashback120.LOG.debug("Failed to write local player packet {}", packet.getClass().getSimpleName(), t);
        }
    }
}
