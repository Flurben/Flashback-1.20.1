package com.flurben.flashback120.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.flurben.flashback120.replay.ReplayHandler;

/** Notes which chunks of a replay changed, so going back in time can keep the ones that did not. */
@Mixin(LevelChunk.class)
public class MixinLevelChunk {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void flashback120$changed(BlockPos pos, BlockState state, boolean moved, CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && level.isClientSide && ReplayHandler.isReplaying()) {
            ReplayHandler.onChunkChanged(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }
}
