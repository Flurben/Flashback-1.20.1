package com.flurben.flashback120.net;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * Encodes and decodes clientbound play packets the way they travel on the wire (without length prefix or
 * compression): a varint packet id followed by the packet's own payload.
 */
public final class PacketCodec {

    private static final Map<Class<?>, Integer> IDS = new HashMap<>();

    private PacketCodec() {}

    /** The clientbound play id of a packet class, or -1 when it has none. */
    public static synchronized int idOf(Class<?> packetClass) {
        Integer id = IDS.get(packetClass);
        if (id == null) {
            id = ConnectionProtocol.PLAY.flows.get(PacketFlow.CLIENTBOUND).getId(packetClass);
            IDS.put(packetClass, id);
        }
        return id;
    }

    /**
     * The wire bytes of a packet, or null when it cannot be sent as one packet (bundles are written as their parts by
     * the caller).
     */
    public static byte[] encode(Packet<?> packet) {
        if (packet instanceof BundlePacket) return null;
        int id = idOf(packet.getClass());
        if (id < 0) return null;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            buf.writeVarInt(id);
            packet.write(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } finally {
            buf.release();
        }
    }

    @SuppressWarnings("unchecked")
    public static Packet<ClientGamePacketListener> decode(byte[] data) {
        ByteBuf raw = Unpooled.wrappedBuffer(data);
        FriendlyByteBuf buf = new FriendlyByteBuf(raw);
        int id = buf.readVarInt();
        Packet<?> packet = ConnectionProtocol.PLAY.createPacket(PacketFlow.CLIENTBOUND, id, buf);
        if (packet == null) throw new IllegalArgumentException("Unknown packet id " + id);
        return (Packet<ClientGamePacketListener>) packet;
    }

    public static int peekId(byte[] data) {
        return VarInts.peekId(data);
    }

    public static int idLength(byte[] data) {
        return VarInts.idLength(data);
    }
}
