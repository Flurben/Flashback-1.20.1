package com.flurben.flashback120.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.replay.ReplayHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

/**
 * Camera FOV, roll and shake for replays. They go through the regular camera setup, so shader packs see the same
 * projection and view as the vanilla renderer.
 */
@Mixin(GameRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "render", at = @At("HEAD"))
    private void flashback120$frame(float partialTicks, long nanos, boolean renderLevel, CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null) handler.onRenderFrame(partialTicks);
    }

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void flashback120$fov(Camera camera, float partialTicks, boolean useFovSetting, CallbackInfoReturnable<Double> cir) {
        if (!useFovSetting) return;
        ReplayHandler handler = ReplayHandler.get();
        if (handler == null) return;
        float fov = handler.getFovOverride();
        if (!Float.isNaN(fov)) cir.setReturnValue((double) Math.max(1.0f, Math.min(179.0f, fov)));
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        shift = At.Shift.AFTER))
    private void flashback120$roll(float partialTicks, long nanos, PoseStack poseStack, CallbackInfo ci) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler == null || handler.isSpectating()) return;
        float roll = handler.getRoll();
        if (roll != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(roll));
        float shakePitch = handler.getShakePitch(), shakeYaw = handler.getShakeYaw();
        if (shakePitch != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(shakePitch));
        if (shakeYaw != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(shakeYaw));
    }
}
