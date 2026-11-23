package com.flurben.flashback120.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.replay.ReplayHandler;

/** A replay has no server to be disconnected from: the replay goes on instead. */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "onDisconnect", at = @At("HEAD"), cancellable = true)
    private void flashback120$ignoreDisconnect(Component reason, CallbackInfo ci) {
        if (ReplayHandler.ignoresDisconnect(this)) ci.cancel();
    }
}
