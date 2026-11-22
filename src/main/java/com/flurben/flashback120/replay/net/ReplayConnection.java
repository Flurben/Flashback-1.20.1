package com.flurben.flashback120.replay.net;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;

import com.flurben.flashback120.Flashback120;
import com.mojang.authlib.GameProfile;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;

/**
 * An in-memory connection that looks like a server connection to the rest of the game. Recorded packets are handed to
 * a normal {@link ClientPacketListener} on the client thread; everything the client tries to send is dropped.
 */
public final class ReplayConnection {

    /** The replay viewer's profile, never the same as anyone in a recording. */
    public static final GameProfile VIEWER_PROFILE = new GameProfile(
        UUID.fromString("f1a5b8c0-0000-4000-8000-f1a5bac0ca3e"), "ReplayViewer");

    private static final Set<String> warned = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static boolean processing;

    public final Connection connection;
    public final EmbeddedChannel channel;
    public final ClientPacketListener listener;

    public ReplayConnection(Minecraft mc) {
        this.connection = new Connection(PacketFlow.CLIENTBOUND);
        this.channel = new EmbeddedChannel(new DropOutbound(), connection);
        connection.setProtocol(ConnectionProtocol.PLAY);
        this.listener = new ClientPacketListener(
            mc,
            null,
            connection,
            null,
            VIEWER_PROFILE,
            mc.getTelemetryManager().createWorldSessionManager(false, null, null));
        connection.setListener(listener);
    }

    /** True while a replayed packet is being handled (client thread only). */
    public static boolean isProcessing() {
        return processing;
    }

    /**
     * Handles a packet right away. One that throws (a mod reacting badly to being replayed, or state that differs
     * from the original session) only logs an error instead of ending the replay.
     */
    public void deliver(Packet<ClientGamePacketListener> packet) {
        processing = true;
        try {
            packet.handle(listener);
        } catch (Throwable t) {
            String key = packet.getClass().getName();
            if (warned.add(key)) Flashback120.LOG.error("Packet {} failed during replay (further errors hidden)", key, t);
        } finally {
            processing = false;
        }
    }

    /** Runs a replay-internal record in order with the recorded packets. */
    public void run(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            if (warned.add("action")) Flashback120.LOG.error("Replay action failed (further errors hidden)", t);
        }
    }

    public void close() {
        try {
            channel.close();
        } catch (Throwable ignored) {}
    }

    /** Swallows every outbound write so nothing piles up in the embedded channel. */
    private static final class DropOutbound extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {}
    }
}
