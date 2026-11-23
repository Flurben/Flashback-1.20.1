package com.flurben.flashback120.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.replay.ReplayHandler;

/** "Boss bar" visual toggle. */
@Mixin(BossHealthOverlay.class)
public class MixinBossHealthOverlay {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flashback120$boss(GuiGraphics graphics, CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().showBossBar) ci.cancel();
    }
}
