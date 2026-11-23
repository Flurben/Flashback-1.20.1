package com.flurben.flashback120.gui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.apache.commons.io.FileUtils;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.format.ReplayStream;
import com.flurben.flashback120.recording.RecordingManager;

/** Offers to recover recordings left behind by a crash. */
public class RecoveryScreen extends Screen {

    private final Screen parent;
    private final List<File> dirs;

    public RecoveryScreen(Screen parent) {
        super(Component.literal("Recover Recordings"));
        this.parent = parent;
        this.dirs = RecordingManager.INSTANCE.findRecoverable();
    }

    @Override
    protected void init() {
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.literal("Recover"), b -> {
            for (File dir : dirs) recover(dir);
            minecraft.setScreen(parent);
        }).bounds(cx - 154, height / 2 + 10, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> {
            for (File dir : dirs) FileUtils.deleteQuietly(dir);
            minecraft.setScreen(parent);
        }).bounds(cx - 50, height / 2 + 10, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Skip"), b -> minecraft.setScreen(parent))
            .bounds(cx + 54, height / 2 + 10, 100, 20).build());
    }

    /** Packages a work directory as a replay: the stream reader ignores the record a crash cut off. */
    private static void recover(File dir) {
        try {
            File meta = new File(dir, ReplayFormat.ENTRY_META);
            ReplayMeta m = meta.exists() ? ReplayMeta.fromJson(new String(Files.readAllBytes(meta.toPath()), StandardCharsets.UTF_8))
                : new ReplayMeta();
            try (ReplayStream stream = ReplayStream.open(new File(dir, ReplayFormat.ENTRY_STREAM))) {
                m.durationTicks = stream.getTickCount();
            }
            RecordingManager.saveAsync(dir, m, RecordingManager.defaultName(m) + " (recovered)");
        } catch (Throwable t) {
            Flashback120.LOG.warn("Recording in {} is not recoverable", dir, t);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTicks) {
        renderBackground(g);
        int cx = width / 2, y = height / 2 - 40;
        g.drawCenteredString(font, "Flashback: Recovery", cx, y, 0xFFFFFF);
        g.drawCenteredString(font, dirs.size() + " recording(s) were not finished, most likely because the game closed.",
            cx, y + 16, 0xA0A0A0);
        g.drawCenteredString(font, "Recover them as replays?", cx, y + 28, 0xA0A0A0);
        super.render(g, mouseX, mouseY, partialTicks);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }
}
