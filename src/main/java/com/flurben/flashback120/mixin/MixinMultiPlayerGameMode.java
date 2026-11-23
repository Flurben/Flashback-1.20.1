package com.flurben.flashback120.mixin;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.stats.StatsCounter;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.replay.CameraEntity;
import com.flurben.flashback120.replay.ReplayHandler;

/** In a replay the client player is the free camera. */
@Mixin(MultiPlayerGameMode.class)
public class MixinMultiPlayerGameMode {

    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ClientPacketListener connection;

    @Inject(method = "createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/stats/StatsCounter;Lnet/minecraft/client/ClientRecipeBook;ZZ)Lnet/minecraft/client/player/LocalPlayer;",
        at = @At("HEAD"), cancellable = true)
    private void flashback120$camera(ClientLevel level, StatsCounter stats, ClientRecipeBook book, boolean a, boolean b,
        CallbackInfoReturnable<LocalPlayer> cir) {
        if (ReplayHandler.isReplaying()) cir.setReturnValue(new CameraEntity(minecraft, level, connection, stats, book));
    }
}
