package com.flurben.flashback120.mixin;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.export.Exporter;

/** Exports are not held to the frame limit. The option itself is left alone, so options.txt keeps the user's value. */
@Mixin(Minecraft.class)
public class MixinMinecraftFramerate {

    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void flashback120$unlimited(CallbackInfoReturnable<Integer> cir) {
        if (Exporter.unlimitedFramerate()) cir.setReturnValue(260);
    }
}
