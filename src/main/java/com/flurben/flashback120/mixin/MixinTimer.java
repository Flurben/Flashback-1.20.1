package com.flurben.flashback120.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Timer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.replay.ReplayHandler;

/** Lets the replay clock decide how many ticks run per frame and the world's partial tick. */
@Mixin(Timer.class)
public class MixinTimer {

    @Inject(method = "advanceTime", at = @At("RETURN"), cancellable = true)
    private void flashback120$replayClock(long time, CallbackInfoReturnable<Integer> cir) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || (Object) this != mc.timer) return;
        cir.setReturnValue(handler.onTimerUpdate((Timer) (Object) this, cir.getReturnValue()));
    }
}
