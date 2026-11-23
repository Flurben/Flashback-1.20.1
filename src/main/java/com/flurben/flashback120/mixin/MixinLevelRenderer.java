package com.flurben.flashback120.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.replay.ReplayHandler;
import com.mojang.blaze3d.vertex.PoseStack;

/** "Sky" visual toggle. */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void flashback120$sky(PoseStack poseStack, Matrix4f projection, float partialTicks, Camera camera,
        boolean foggy, Runnable fog, CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().renderSky) {
            fog.run();
            ci.cancel();
        }
    }
}
