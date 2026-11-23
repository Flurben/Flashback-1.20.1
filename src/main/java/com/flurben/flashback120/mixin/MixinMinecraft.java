package com.flurben.flashback120.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.replay.ReplayHandler;

/**
 * Replay tick gating: ticks that do not advance the replay run with the world frozen, exactly like the singleplayer
 * pause menu freezes it, while input and screens keep working. Interaction with the world is disabled in replays.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(method = "tick", at = @At("HEAD"))
    private void flashback120$tickStart(CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null) ((Minecraft) (Object) this).pause = !handler.onTickStart();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void flashback120$tickEnd(CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null) {
            handler.onTickEnd();
            ((Minecraft) (Object) this).pause = false;
        }
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void flashback120$noAttack(CallbackInfoReturnable<Boolean> cir) {
        if (ReplayHandler.isReplaying()) cir.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void flashback120$noUse(CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
    private void flashback120$noPick(CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void flashback120$noMining(boolean leftClick, CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "clearLevel(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    private void flashback120$levelGone(CallbackInfo ci) {
        ReplayHandler.onWorldGone();
    }
}
