package com.flurben.flashback120.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.replay.ReplayHandler;

/** "Particles" visual toggle: new particles do not spawn. */
@Mixin(ParticleEngine.class)
public class MixinParticleEngine {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void flashback120$particles(Particle particle, CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().renderParticles) ci.cancel();
    }
}
