package com.flurben.flashback120.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.flurben.flashback120.replay.ReplayHandler;

/** "Chat" visual toggle. */
@Mixin(ChatComponent.class)
public class MixinChatComponent {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void flashback120$chat(GuiGraphics graphics, int tick, int mouseX, int mouseY, CallbackInfo ci) {
        if (ReplayHandler.isReplaying() && !ReplayHandler.visuals().showChat) ci.cancel();
    }
}
