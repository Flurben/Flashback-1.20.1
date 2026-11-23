package com.flurben.flashback120.mixin;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.recording.RecordingManager;

/** Starts automatic recordings once the login is done, before the server sends the world. */
@Mixin(ClientHandshakePacketListenerImpl.class)
public class MixinClientHandshakePacketListenerImpl {

    @Shadow
    @Final
    private Connection connection;

    @Inject(method = "handleGameProfile", at = @At("TAIL"))
    private void flashback120$loggedIn(ClientboundGameProfilePacket packet, CallbackInfo ci) {
        RecordingManager.INSTANCE.onConnected(connection);
    }
}
