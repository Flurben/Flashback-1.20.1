package com.flurben.flashback120.recording;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.net.PacketCodec;
import com.flurben.flashback120.net.PacketIds;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

/**
 * Netty handlers that copy every clientbound play packet into the active {@link Recorder}.
 *
 * <p>
 * On a remote connection the handler sits right after the frame splitter (and after decompression, when the server
 * compresses), so it copies the exact wire bytes, which is lossless for every packet including mod packets. On the
 * integrated server (memory channel) packets travel as objects, so they are serialized with their own {@code write},
 * just like the integrated server would do for a LAN player.
 */
public abstract class CaptureHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "flashback120_capture";

    protected final Recorder recorder;

    protected CaptureHandler(Recorder recorder) {
        this.recorder = recorder;
    }

    public static CaptureHandler create(Recorder recorder, Connection connection) {
        return connection.isMemoryConnection() ? new ObjectCapture(recorder) : new RawCapture(recorder);
    }

    /** Inserts the handler into the pipeline. Must run on the channel's event loop. */
    public void install(ChannelPipeline pipeline) {
        if (pipeline.get(NAME) != null) pipeline.remove(NAME);
        if (this instanceof RawCapture && pipeline.get("decompress") != null) {
            pipeline.addAfter("decompress", NAME, this);
        } else if (this instanceof RawCapture && pipeline.get("splitter") != null) {
            pipeline.addAfter("splitter", NAME, this);
        } else {
            pipeline.addBefore("packet_handler", NAME, this);
        }
    }

    public static void uninstall(ChannelPipeline pipeline) {
        try {
            if (pipeline.get(NAME) != null) pipeline.remove(NAME);
        } catch (Exception ignored) {}
    }

    /** Packets that never matter in a replay. */
    static boolean isIgnoredId(int id) {
        return id == PacketIds.KEEP_ALIVE || id == PacketIds.PING
            || id == PacketIds.AWARD_STATS
            || id == PacketIds.COMMAND_SUGGESTIONS
            || id == PacketIds.COMMANDS
            || id == PacketIds.BLOCK_CHANGED_ACK
            || id == PacketIds.CHAT_COMPLETIONS
            || id == PacketIds.TAG_QUERY
            || id == PacketIds.UPDATE_RECIPES
            || id == PacketIds.RECIPE
            || id == PacketIds.UPDATE_ADVANCEMENTS
            || id == PacketIds.SELECT_ADVANCEMENTS_TAB
            || id == PacketIds.BUNDLE_DELIMITER;
    }

    static final class RawCapture extends CaptureHandler {

        RawCapture(Recorder recorder) {
            super(recorder);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                if (msg instanceof ByteBuf buf && ctx.channel()
                    .attr(Connection.ATTRIBUTE_PROTOCOL)
                    .get() == ConnectionProtocol.PLAY) {
                    int len = buf.readableBytes();
                    if (len > 0) {
                        byte[] data = new byte[len];
                        buf.getBytes(buf.readerIndex(), data);
                        if (!isIgnoredId(PacketCodec.peekId(data))) recorder.capture(data);
                    }
                }
            } catch (Throwable t) {
                Flashback120.LOG.error("Failed to capture packet", t);
            }
            ctx.fireChannelRead(msg);
        }
    }

    static final class ObjectCapture extends CaptureHandler {

        ObjectCapture(Recorder recorder) {
            super(recorder);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet<?> packet) capture(packet);
            ctx.fireChannelRead(msg);
        }

        private void capture(Packet<?> packet) {
            if (packet instanceof BundlePacket<?> bundle) {
                for (Packet<?> part : bundle.subPackets()) capture(part);
                return;
            }
            try {
                byte[] data = PacketCodec.encode(packet);
                if (data != null && !isIgnoredId(PacketCodec.peekId(data))) recorder.capture(data);
            } catch (Throwable t) {
                Flashback120.LOG.error("Failed to capture packet {}", packet.getClass().getName(), t);
            }
        }
    }
}
