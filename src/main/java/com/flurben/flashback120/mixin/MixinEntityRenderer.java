package com.flurben.flashback120.mixin;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.export.Exporter;
import com.flurben.flashback120.replay.ReplayHandler;
import com.mojang.blaze3d.vertex.PoseStack;

/** "Nametags" visual toggle and the export option to hide them. */
@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true)
    private void flashback120$nametag(Entity entity, Component name, PoseStack poseStack, MultiBufferSource buffers,
        int light, CallbackInfo ci) {
        if (!ReplayHandler.isReplaying()) return;
        if (!ReplayHandler.visuals().renderNametags || Exporter.hidesNametags()) ci.cancel();
    }
}
