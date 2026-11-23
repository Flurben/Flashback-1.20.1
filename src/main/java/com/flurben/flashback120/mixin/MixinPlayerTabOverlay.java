package com.flurben.flashback120.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.replay.ReplayHandler;

/** "Player list" visual toggle. */
@Mixin(PlayerTabOverlay.class)
public class MixinPlayerTabOverlay {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flashback120$tab(GuiGraphics graphics, int width, Scoreboard scoreboard, Objective objective,
        CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().showPlayerList) ci.cancel();
    }
}
