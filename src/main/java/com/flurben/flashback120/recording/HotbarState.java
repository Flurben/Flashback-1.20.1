package com.flurben.flashback120.recording;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** First-person HUD information of the recording player (hotbar, health, food, armor, air, experience). */
public final class HotbarState {

    public int selectedSlot;
    public float health;
    public float maxHealth;
    public float absorption;
    public int food;
    public float saturation;
    public int armor;
    public int air;
    public int maxAir;
    public int xpLevel;
    public float xpProgress;
    public final ItemStack[] hotbar = new ItemStack[9];
    public ItemStack offhand = ItemStack.EMPTY;

    public HotbarState() {
        java.util.Arrays.fill(hotbar, ItemStack.EMPTY);
    }

    public static byte[] capture(Player p) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(p.getInventory().selected);
            out.writeFloat(p.getHealth());
            out.writeFloat(p.getMaxHealth());
            out.writeFloat(p.getAbsorptionAmount());
            out.writeInt(p.getFoodData().getFoodLevel());
            out.writeFloat(p.getFoodData().getSaturationLevel());
            out.writeInt(p.getArmorValue());
            out.writeInt(p.getAirSupply());
            out.writeInt(p.getMaxAirSupply());
            out.writeInt(p.experienceLevel);
            out.writeFloat(p.experienceProgress);
            CompoundTag root = new CompoundTag();
            ListTag list = new ListTag();
            for (int i = 0; i < 9; i++) {
                CompoundTag tag = new CompoundTag();
                tag.putByte("Slot", (byte) i);
                p.getInventory().getItem(i).save(tag);
                list.add(tag);
            }
            root.put("items", list);
            root.put("offhand", p.getOffhandItem().save(new CompoundTag()));
            NbtIo.write(root, out);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static HotbarState read(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            HotbarState s = new HotbarState();
            s.selectedSlot = in.readByte();
            s.health = in.readFloat();
            s.maxHealth = in.readFloat();
            s.absorption = in.readFloat();
            s.food = in.readInt();
            s.saturation = in.readFloat();
            s.armor = in.readInt();
            s.air = in.readInt();
            s.maxAir = in.readInt();
            s.xpLevel = in.readInt();
            s.xpProgress = in.readFloat();
            CompoundTag root = NbtIo.read(in);
            ListTag list = root.getList("items", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                int slot = tag.getByte("Slot");
                if (slot >= 0 && slot < 9) s.hotbar[slot] = ItemStack.of(tag);
            }
            s.offhand = ItemStack.of(root.getCompound("offhand"));
            return s;
        } catch (Exception e) {
            return null;
        }
    }
}
