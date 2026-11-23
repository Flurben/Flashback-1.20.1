package com.flurben.flashback120.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.export.Exporter;

/** Toasts are drawn even with the HUD hidden, and must not end up in exported videos. */
@Mixin(ToastComponent.class)
public class MixinToastComponent {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flashback120$noToastsInExports(GuiGraphics graphics, CallbackInfo ci) {
        if (Exporter.isExporting()) ci.cancel();
    }
}
