package com.flurben.flashback120.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.scores.Objective;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.client.HudRenderer;
import com.flurben.flashback120.replay.ReplayHandler;

/**
 * The HUD in a replay: the camera's own spectator HUD and crosshair are never shown, the recorded HUD replaces them
 * while the camera is in the recording player's head, and the scoreboard follows the visuals settings.
 */
@Mixin(Gui.class)
public class MixinGui {

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void flashback120$hotbar(float partialTicks, GuiGraphics graphics, CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void flashback120$crosshair(GuiGraphics graphics, CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void flashback120$health(GuiGraphics graphics, CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void flashback120$xp(GuiGraphics graphics, int x, CallbackInfo ci) {
        if (ReplayHandler.isReplaying()) ci.cancel();
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void flashback120$scoreboard(GuiGraphics graphics, Objective objective, CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().showScoreboard) ci.cancel();
    }

    /** Exports have no HUD. The game's own "hide GUI" option is left alone, as it also hides nametags. */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flashback120$noHudInExports(GuiGraphics graphics, float partialTicks, CallbackInfo ci) {
        if (com.flurben.flashback120.export.Exporter.isExporting()) ci.cancel();
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void flashback120$recordedHud(GuiGraphics graphics, float partialTicks, CallbackInfo ci) {
        HudRenderer.render(graphics, partialTicks);
    }
}
