package com.flurben.flashback120.mixin;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.export.Exporter;

/** Export frame hooks: each frame is read back once the game has rendered it completely. */
@Mixin(value = GameRenderer.class, priority = 2000)
public class MixinGameRendererExport {

    @Inject(method = "render", at = @At("HEAD"))
    private void flashback120$exportFrameStart(float partialTicks, long nanos, boolean renderLevel, CallbackInfo ci) {
        Exporter e = Exporter.get();
        if (e != null) e.onFrameStart();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void flashback120$exportFrame(float partialTicks, long nanos, boolean renderLevel, CallbackInfo ci) {
        Exporter e = Exporter.get();
        if (e != null) e.onFrameRendered();
    }
}
