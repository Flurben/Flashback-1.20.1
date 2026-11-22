package com.flurben.flashback120.net;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;

import com.mojang.authlib.GameProfile;

import io.netty.buffer.Unpooled;

/** Builds the wire bytes of packets that have no constructor usable on the client. */
public final class PacketBuilder {

    private PacketBuilder() {}

    public static byte[] build(int id, Consumer<FriendlyByteBuf> payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(256));
        try {
            buf.writeVarInt(id);
            payload.accept(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } finally {
            buf.release();
        }
    }

    /** One entry of a player list packet. */
    public record InfoEntry(GameProfile profile, boolean listed, int latency, int gameMode,
        net.minecraft.network.chat.Component displayName) {

        public static InfoEntry of(PlayerInfo info, boolean listed) {
            return new InfoEntry(
                info.getProfile(),
                listed,
                info.getLatency(),
                info.getGameMode() == null ? 0 : info.getGameMode().getId(),
                info.getTabListDisplayName());
        }
    }

    /** A player list packet adding these players, without chat sessions. */
    public static byte[] playerInfo(Collection<InfoEntry> entries) {
        EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME);
        return build(PacketIds.PLAYER_INFO_UPDATE, buf -> {
            buf.writeEnumSet(actions, ClientboundPlayerInfoUpdatePacket.Action.class);
            buf.writeCollection(List.copyOf(entries), (b, e) -> {
                b.writeUUID(e.profile().getId());
                String name = e.profile().getName();
                b.writeUtf(name.length() > 16 ? name.substring(0, 16) : name, 16);
                b.writeGameProfileProperties(e.profile().getProperties());
                b.writeVarInt(e.gameMode());
                b.writeBoolean(e.listed());
                b.writeVarInt(e.latency());
                b.writeNullable(e.displayName(), FriendlyByteBuf::writeComponent);
            });
        });
    }
}
