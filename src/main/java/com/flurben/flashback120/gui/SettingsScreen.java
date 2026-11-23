package com.flurben.flashback120.gui;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.flurben.flashback120.Config;

/** The mod's settings (also reachable from Mod Menu). */
public class SettingsScreen extends Screen {

    private final Screen parent;
    private EditBox ffmpeg;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Flashback Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Config c = Config.get();
        int x1 = width / 2 - 155, x2 = width / 2 + 5, y = 40;
        toggle(x1, y, "Auto start recording", () -> c.autoStartRecording, v -> c.autoStartRecording = v);
        toggle(x2, y, "Auto finish recording", () -> c.autoFinishRecording, v -> c.autoFinishRecording = v);
        y += 24;
        toggle(x1, y, "Quicksave", () -> c.quicksave, v -> c.quicksave = v);
        toggle(x2, y, "Recording toasts", () -> c.showRecordingToasts, v -> c.showRecordingToasts = v);
        y += 24;
        toggle(x1, y, "Mark dimension changes", () -> c.markDimensionChanges, v -> c.markDimensionChanges = v);
        toggle(x2, y, "Record hotbar", () -> c.recordHotbar, v -> c.recordHotbar = v);
        y += 24;
        toggle(x1, y, "Recording indicator", () -> c.showRecordingIndicator, v -> c.showRecordingIndicator = v);
        toggle(x2, y, "Camera momentum", () -> c.cameraMomentum, v -> c.cameraMomentum = v);
        y += 24;
        addRenderableWidget(Button.builder(label("Editor UI scale", String.format("%.2f", c.uiScale)), b -> {
            c.uiScale = c.uiScale >= 2.0f ? 0.75f : c.uiScale + 0.25f;
            b.setMessage(label("Editor UI scale", String.format("%.2f (restart)", c.uiScale)));
            Config.save();
        }).bounds(x1, y, 150, 20).build());
        addRenderableWidget(Button.builder(label("Default interpolation", c.defaultInterpolation), b -> {
            String[] all = { "SMOOTH", "LINEAR", "EASE_IN_OUT", "EASE_IN", "EASE_OUT", "HOLD" };
            int i = java.util.Arrays.asList(all).indexOf(c.defaultInterpolation);
            c.defaultInterpolation = all[(i + 1) % all.length];
            b.setMessage(label("Default interpolation", c.defaultInterpolation));
            Config.save();
        }).bounds(x2, y, 150, 20).build());
        y += 32;
        ffmpeg = new EditBox(font, x1, y, 310, 20, Component.literal("ffmpeg"));
        ffmpeg.setMaxLength(512);
        ffmpeg.setValue(c.ffmpegPath);
        addRenderableWidget(ffmpeg);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void toggle(int x, int y, String name, Supplier<Boolean> get, Consumer<Boolean> set) {
        addRenderableWidget(Button.builder(label(name, get.get() ? "ON" : "OFF"), b -> {
            set.accept(!get.get());
            b.setMessage(label(name, get.get() ? "ON" : "OFF"));
            Config.save();
        }).bounds(x, y, 150, 20).build());
    }

    private static Component label(String name, String value) {
        return Component.literal(name + ": " + value);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g);
        g.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        g.drawString(font, "ffmpeg path (for video export)", width / 2 - 155, ffmpeg.getY() - 11, 0xA0A0A0);
        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        String path = ffmpeg.getValue().trim();
        Config.get().ffmpegPath = path.isEmpty() ? "ffmpeg" : path;
        Config.save();
        minecraft.setScreen(parent);
    }
}
