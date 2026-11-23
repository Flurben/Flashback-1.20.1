package com.flurben.flashback120.gui;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.apache.commons.io.FileUtils;

import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.recording.RecordingManager;

/** Asks for the name of a finished recording, or whether to throw it away. */
public class SaveReplayScreen extends Screen {

    private final Screen parent;
    private final File workDir;
    private final ReplayMeta meta;
    private EditBox name;

    private SaveReplayScreen(Screen parent, File workDir, ReplayMeta meta) {
        super(Component.literal("Save Replay"));
        this.parent = parent;
        this.workDir = workDir;
        this.meta = meta;
    }

    /** Shows the screen on the client thread, over whatever screen is open. */
    public static void open(File workDir, ReplayMeta meta) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new SaveReplayScreen(mc.screen, workDir, meta)));
    }

    @Override
    protected void init() {
        String old = name != null ? name.getValue() : RecordingManager.defaultName(meta);
        name = new EditBox(font, width / 2 - 150, height / 2 - 14, 300, 20, Component.literal("Name"));
        name.setMaxLength(100);
        name.setValue(old);
        addRenderableWidget(name);
        setInitialFocus(name);
        addRenderableWidget(Button.builder(Component.literal("Save Replay"), b -> save())
            .bounds(width / 2 - 150, height / 2 + 12, 148, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete Recording"), b -> {
            try {
                FileUtils.deleteDirectory(workDir);
            } catch (IOException ignored) {}
            minecraft.setScreen(parent);
        }).bounds(width / 2 + 2, height / 2 + 12, 148, 20).build());
    }

    private void save() {
        String n = name.getValue().trim();
        RecordingManager.saveAsync(workDir, meta, n.isEmpty() ? RecordingManager.defaultName(meta) : n);
        minecraft.setScreen(parent);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
            save();
            return true;
        }
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g);
        g.drawCenteredString(font, "Save Replay", width / 2, height / 2 - 50, 0xFFFFFF);
        g.drawCenteredString(font, "Length " + ReplayMeta.formatTicks(meta.durationTicks), width / 2, height / 2 - 34, 0xA0A0A0);
        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
