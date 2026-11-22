package com.flurben.flashback120.format;

import java.nio.ByteBuffer;
import java.util.Arrays;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

import com.flurben.flashback120.net.PacketCodec;
import com.flurben.flashback120.net.PacketIds;
import com.flurben.flashback120.net.VarInts;

/** Reads which chunks a recorded 1.20.1 packet changes, from its wire format and mostly without decoding it. */
public final class PacketChunks {

    private PacketChunks() {}

    /** Packets whose whole effect stays in the chunks they name. */
    public static boolean changesChunks(int id) {
        return hasChunkData(id) || id == PacketIds.EXPLODE;
    }

    /** Packets that set what one or more chunks contain. */
    public static boolean hasChunkData(int id) {
        return id == PacketIds.LEVEL_CHUNK || id == PacketIds.FORGET_CHUNK
            || id == PacketIds.SECTION_BLOCKS
            || id == PacketIds.BLOCK_UPDATE
            || id == PacketIds.LIGHT_UPDATE
            || id == PacketIds.BLOCK_ENTITY_DATA
            || id == PacketIds.CHUNKS_BIOMES;
    }

    public static boolean isExplosion(int id) {
        return id == PacketIds.EXPLODE;
    }

    public static boolean isUnload(byte[] packet) {
        return VarInts.peekId(packet) == PacketIds.FORGET_CHUNK;
    }

    /** The chunks a packet of one of the {@link #changesChunks} kinds changes, or null when it cannot be read. */
    public static long[] of(byte[] packet) {
        try {
            int id = VarInts.peekId(packet);
            ByteBuffer in = ByteBuffer.wrap(packet);
            in.position(VarInts.idLength(packet));
            if (id == PacketIds.LEVEL_CHUNK || id == PacketIds.FORGET_CHUNK) {
                return new long[] { ReplayIndex.chunkKey(in.getInt(), in.getInt()) };
            } else if (id == PacketIds.BLOCK_UPDATE || id == PacketIds.BLOCK_ENTITY_DATA) {
                long pos = in.getLong();
                return new long[] { ReplayIndex.chunkKey(blockX(pos) >> 4, blockZ(pos) >> 4) };
            } else if (id == PacketIds.SECTION_BLOCKS) {
                long section = in.getLong();
                int x = (int) (section >> 42);
                int z = (int) (section << 22 >> 42);
                return new long[] { ReplayIndex.chunkKey(x, z) };
            } else if (id == PacketIds.LIGHT_UPDATE) {
                int x = readVarInt(in);
                int z = readVarInt(in);
                return new long[] { ReplayIndex.chunkKey(x, z) };
            } else if (id == PacketIds.CHUNKS_BIOMES) {
                int count = readVarInt(in);
                if (count < 0 || count > 100000) return null;
                long[] chunks = new long[count];
                for (int i = 0; i < count; i++) {
                    long pos = in.getLong();
                    chunks[i] = ReplayIndex.chunkKey((int) pos, (int) (pos >>> 32));
                    int len = readVarInt(in);
                    in.position(in.position() + len);
                }
                return chunks;
            } else if (id == PacketIds.EXPLODE) {
                return explosion(in);
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The dimension a login or respawn packet puts the player in, or null. */
    public static String dimensionOf(byte[] packet) {
        try {
            Packet<?> p = PacketCodec.decode(packet);
            if (p instanceof ClientboundLoginPacket login) return login.dimension().location().toString();
            if (p instanceof ClientboundRespawnPacket respawn) return respawn.getDimension().location().toString();
        } catch (Throwable ignored) {}
        return null;
    }

    public static int blockX(long pos) {
        return (int) (pos >> 38);
    }

    public static int blockY(long pos) {
        return (int) (pos << 52 >> 52);
    }

    public static int blockZ(long pos) {
        return (int) (pos << 26 >> 38);
    }

    private static int readVarInt(ByteBuffer in) {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = in.get() & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
            shift += 7;
            if (shift > 35) throw new IllegalArgumentException("VarInt too big");
        }
    }

    /** An explosion's blocks are bytes relative to its center. */
    private static long[] explosion(ByteBuffer in) {
        double cx = in.getDouble();
        in.getDouble();
        double cz = in.getDouble();
        in.getFloat();
        int centerX = (int) Math.floor(cx);
        int centerZ = (int) Math.floor(cz);
        int count = readVarInt(in);
        if (count < 0 || count * 3 > in.remaining()) return null;
        long[] chunks = new long[count + 1];
        chunks[0] = ReplayIndex.chunkKey(centerX >> 4, centerZ >> 4);
        for (int i = 1; i <= count; i++) {
            int x = centerX + in.get();
            in.get();
            chunks[i] = ReplayIndex.chunkKey(x >> 4, (centerZ + in.get()) >> 4);
        }
        Arrays.sort(chunks);
        int unique = 0;
        for (int i = 0; i < chunks.length; i++) {
            if (i == 0 || chunks[i] != chunks[i - 1]) chunks[unique++] = chunks[i];
        }
        return Arrays.copyOf(chunks, unique);
    }
}
