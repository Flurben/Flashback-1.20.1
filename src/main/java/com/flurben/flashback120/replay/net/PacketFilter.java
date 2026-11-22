package com.flurben.flashback120.replay.net;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

import com.flurben.flashback120.net.PacketIds;
import com.flurben.flashback120.net.VarInts;
import com.flurben.flashback120.replay.ReplayConstants;

/**
 * Byte-level filtering of recorded packets before they are decoded. Packets that only make sense for the player who
 * recorded (inventory, health, abilities, screens) are dropped; packets that set up the client player are rewritten so
 * the viewer becomes a free camera with its own entity id.
 */
public final class PacketFilter {

    public static final int GAMEMODE_SPECTATOR = 3;

    /** Mod channels ignored during playback (from the config). */
    public static volatile Set<String> ignoredChannels = Collections.emptySet();

    public interface Listener {

        /** The recording's login packet, with the entity id of the recording player. */
        void onJoinGame(int originalEntityId);

        /** A teleport of the recording player (dropped, but useful to place the camera). */
        void onPlayerPosLook(double x, double y, double z, float yaw, float pitch);
    }

    private PacketFilter() {}

    /** Returns the packet bytes to deliver (possibly modified), or null to drop the packet. */
    public static byte[] filter(byte[] data, Listener listener) {
        int id = VarInts.peekId(data);
        int off = VarInts.idLength(data);
        if (id == PacketIds.BUNDLE_DELIMITER || id == PacketIds.KEEP_ALIVE
            || id == PacketIds.PING
            || id == PacketIds.SET_HEALTH
            || id == PacketIds.SET_CARRIED_ITEM
            || id == PacketIds.SET_EXPERIENCE
            || id == PacketIds.OPEN_SCREEN
            || id == PacketIds.CONTAINER_CLOSE
            || id == PacketIds.CONTAINER_CONTENT
            || id == PacketIds.CONTAINER_DATA
            || id == PacketIds.CONTAINER_SLOT
            || id == PacketIds.HORSE_SCREEN
            || id == PacketIds.OPEN_SIGN_EDITOR
            || id == PacketIds.OPEN_BOOK
            || id == PacketIds.MERCHANT_OFFERS
            || id == PacketIds.AWARD_STATS
            || id == PacketIds.RECIPE
            || id == PacketIds.UPDATE_RECIPES
            || id == PacketIds.UPDATE_ADVANCEMENTS
            || id == PacketIds.SELECT_ADVANCEMENTS_TAB
            || id == PacketIds.COMMAND_SUGGESTIONS
            || id == PacketIds.COMMANDS
            || id == PacketIds.CHAT_COMPLETIONS
            || id == PacketIds.PLAYER_ABILITIES
            || id == PacketIds.DISCONNECT
            || id == PacketIds.RESOURCE_PACK
            || id == PacketIds.SET_CAMERA
            || id == PacketIds.PLAYER_LOOK_AT
            || id == PacketIds.COMBAT_END
            || id == PacketIds.COMBAT_ENTER
            || id == PacketIds.COMBAT_KILL
            || id == PacketIds.COOLDOWN
            || id == PacketIds.GHOST_RECIPE
            || id == PacketIds.BLOCK_CHANGED_ACK
            || id == PacketIds.TAG_QUERY
            || id == PacketIds.MOVE_VEHICLE
            || id == PacketIds.DELETE_CHAT) {
            return null;
        }
        if (id == PacketIds.LOGIN) {
            // int entity id, boolean hardcore, byte game mode, byte previous game mode
            if (data.length < off + 7) return null;
            byte[] copy = data.clone();
            int original = readInt(copy, off);
            writeInt(copy, off, ReplayConstants.CAMERA_ENTITY_ID);
            copy[off + 5] = (byte) GAMEMODE_SPECTATOR;
            copy[off + 6] = (byte) -1;
            if (listener != null) listener.onJoinGame(original);
            return copy;
        }
        if (id == PacketIds.RESPAWN) {
            // two resource keys, long seed, byte game mode, byte previous game mode
            int pos = skipString(data, off);
            if (pos < 0) return data;
            pos = skipString(data, pos);
            if (pos < 0 || data.length < pos + 10) return data;
            byte[] copy = data.clone();
            copy[pos + 8] = (byte) GAMEMODE_SPECTATOR;
            copy[pos + 9] = (byte) -1;
            return copy;
        }
        if (id == PacketIds.PLAYER_POSITION) {
            // belongs to the recording player, not the camera
            if (listener != null && data.length >= off + 8 * 3 + 8) {
                double x = Double.longBitsToDouble(readLong(data, off));
                double y = Double.longBitsToDouble(readLong(data, off + 8));
                double z = Double.longBitsToDouble(readLong(data, off + 16));
                float yaw = Float.intBitsToFloat(readInt(data, off + 24));
                float pitch = Float.intBitsToFloat(readInt(data, off + 28));
                int flags = data.length > off + 32 ? data[off + 32] : 0;
                if (flags == 0) listener.onPlayerPosLook(x, y, z, yaw, pitch);
            }
            return null;
        }
        if (id == PacketIds.GAME_EVENT) {
            if (data.length <= off) return null;
            int reason = data[off] & 0xFF;
            // keep weather (1, 2, 7, 8) and the arrow hit sound (6)
            return reason == 1 || reason == 2 || reason == 6 || reason == 7 || reason == 8 ? data : null;
        }
        if (id == PacketIds.CUSTOM_PAYLOAD) {
            String channel = readString(data, off);
            if (channel == null) return data;
            if (ignoredChannels.contains(channel)) return null;
            return data;
        }
        return data;
    }

    /** The position after a varint-prefixed string, or -1. */
    static int skipString(byte[] data, int off) {
        int len = 0;
        int shift = 0;
        int i = off;
        while (i < data.length) {
            int b = data[i++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) return -1;
        }
        if (len < 0 || i + len > data.length) return -1;
        return i + len;
    }

    static String readString(byte[] data, int off) {
        int end = skipString(data, off);
        if (end < 0) return null;
        int start = off;
        while ((data[start] & 0x80) != 0) start++;
        start++;
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    public static int readInt(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    public static long readLong(byte[] d, int o) {
        return ((long) readInt(d, o) << 32) | (readInt(d, o + 4) & 0xFFFFFFFFL);
    }

    static void writeInt(byte[] d, int o, int v) {
        d[o] = (byte) (v >>> 24);
        d[o + 1] = (byte) (v >>> 16);
        d[o + 2] = (byte) (v >>> 8);
        d[o + 3] = (byte) v;
    }

    /** Unused by the filter itself, kept for the packet ids it was written against. */
    static int peek(byte[] data) {
        return VarInts.peekId(data);
    }
}
