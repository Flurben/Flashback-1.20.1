package com.flurben.flashback120.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.replay.ReplayHandler;

/** Render filter: hidden entities are skipped, including their shadows and in shader shadow passes. */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void flashback120$hide(Entity entity, Frustum frustum, double x, double y, double z,
        CallbackInfoReturnable<Boolean> cir) {
        ReplayHandler handler = ReplayHandler.get();
        if (handler != null && handler.isHidden(entity)) cir.setReturnValue(false);
    }
}
