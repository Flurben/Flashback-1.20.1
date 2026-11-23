package com.flurben.flashback120.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.flurben.flashback120.replay.ReplayHandler;

/** The replay's pause menu. */
public class ReplayMenuScreen extends Screen {

    private final Screen editor;

    public ReplayMenuScreen(Screen editor) {
        super(Component.literal("Replay"));
        this.editor = editor;
    }

    @Override
    protected void init() {
        int cx = width / 2, y = height / 4 + 24;
        addRenderableWidget(Button.builder(Component.literal("Back to Replay"), b -> minecraft.setScreen(editor))
            .bounds(cx - 100, y, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Export Video..."), b -> minecraft.setScreen(new ExportScreen(editor)))
            .bounds(cx - 100, y + 24, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Options..."), b -> minecraft.setScreen(new OptionsScreen(this, minecraft.options)))
            .bounds(cx - 100, y + 48, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Mod Settings..."), b -> minecraft.setScreen(new SettingsScreen(this)))
            .bounds(cx + 2, y + 48, 98, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Exit Replay"), b -> {
            ReplayHandler h = ReplayHandler.get();
            if (h != null) h.exit();
            else minecraft.setScreen(null);
        }).bounds(cx - 100, y + 84, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g);
        g.drawCenteredString(font, "Replay: " + (ReplayHandler.get() != null ? ReplayHandler.get().meta.name : ""),
            width / 2, height / 4, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(editor);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
