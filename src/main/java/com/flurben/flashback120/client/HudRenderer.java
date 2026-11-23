package com.flurben.flashback120.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.export.Exporter;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.recording.HotbarState;
import com.flurben.flashback120.recording.Recorder;
import com.flurben.flashback120.recording.RecordingManager;
import com.flurben.flashback120.replay.ReplayHandler;

/** In-game overlays: the recording indicator and the recorded first-person HUD while spectating the recorder. */
public final class HudRenderer {

    private static final ResourceLocation WIDGETS = new ResourceLocation("textures/gui/widgets.png");
    private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");

    private HudRenderer() {}

    public static void render(GuiGraphics g, float partialTicks) {
        renderRecordingIndicator(g);
        renderRecordedHotbar(g);
    }

    public static void renderRecordingIndicator(GuiGraphics g) {
        Recorder r = RecordingManager.INSTANCE.getCurrent();
        if (r == null || !r.isStarted() || !Config.get().showRecordingIndicator) return;
        Minecraft mc = Minecraft.getInstance();
        boolean blink = (System.currentTimeMillis() / 600) % 2 == 0;
        String text = (r.isPaused() ? "PAUSED " : "REC ") + ReplayMeta.formatTicks(r.getTicks());
        g.fill(2, 2, 14 + mc.font.width(text), 13, 0x80000000);
        if (r.isPaused() || blink) g.fill(4, 5, 9, 10, r.isPaused() ? 0xFFE0C040 : 0xFFE03030);
        g.drawString(mc.font, text, 12, 4, 0xFFFFFF);
    }

    /** The recording player's hotbar, health, armor, food and experience, shown while in their head. */
    public static void renderRecordedHotbar(GuiGraphics g) {
        ReplayHandler h = ReplayHandler.get();
        Minecraft mc = Minecraft.getInstance();
        if (h == null || !h.editor.visuals.showRecordedHotbar || !mc.options.getCameraType().isFirstPerson()) return;
        if (Exporter.isExporting()) return;
        Entity view = mc.getCameraEntity();
        if (!h.isSpectating() || view == null || view.getId() != h.getLocalPlayerId()) return;
        HotbarState s = h.getHotbar();
        if (s == null) return;
        int w = g.guiWidth(), height = g.guiHeight();
        int x = w / 2 - 91;
        g.blit(WIDGETS, x, height - 22, 0, 0, 182, 22);
        g.blit(WIDGETS, x - 1 + Math.max(0, Math.min(8, s.selectedSlot)) * 20, height - 23, 0, 22, 24, 22);

        int xpY = height - 29;
        g.blit(ICONS, x, xpY, 0, 64, 182, 5);
        int filled = (int) (s.xpProgress * 183.0F);
        if (filled > 0) g.blit(ICONS, x, xpY, 0, 69, filled, 5);
        int heartsY = height - 39;
        int health = (int) Math.ceil(s.health);
        for (int i = 0; i < 10; i++) {
            int hx = x + i * 8;
            g.blit(ICONS, hx, heartsY, 16, 0, 9, 9);
            if (i * 2 + 1 < health) g.blit(ICONS, hx, heartsY, 52, 0, 9, 9);
            else if (i * 2 + 1 == health) g.blit(ICONS, hx, heartsY, 61, 0, 9, 9);
        }
        if (s.armor > 0) {
            for (int i = 0; i < 10; i++) {
                int ax = x + i * 8;
                if (i * 2 + 1 < s.armor) g.blit(ICONS, ax, heartsY - 10, 34, 9, 9, 9);
                else if (i * 2 + 1 == s.armor) g.blit(ICONS, ax, heartsY - 10, 25, 9, 9, 9);
                else g.blit(ICONS, ax, heartsY - 10, 16, 9, 9, 9);
            }
        }
        for (int i = 0; i < 10; i++) {
            int fx = x + 182 - 9 - i * 8;
            g.blit(ICONS, fx, heartsY, 16, 27, 9, 9);
            if (i * 2 + 1 < s.food) g.blit(ICONS, fx, heartsY, 52, 27, 9, 9);
            else if (i * 2 + 1 == s.food) g.blit(ICONS, fx, heartsY, 61, 27, 9, 9);
        }
        if (s.xpLevel > 0) {
            String lvl = String.valueOf(s.xpLevel);
            int lx = (w - mc.font.width(lvl)) / 2;
            g.drawString(mc.font, lvl, lx + 1, xpY - 6, 0, false);
            g.drawString(mc.font, lvl, lx - 1, xpY - 6, 0, false);
            g.drawString(mc.font, lvl, lx, xpY - 5, 0, false);
            g.drawString(mc.font, lvl, lx, xpY - 7, 0, false);
            g.drawString(mc.font, lvl, lx, xpY - 6, 0x80FF20, false);
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = s.hotbar[i];
            if (stack == null || stack.isEmpty()) continue;
            int ix = x + 3 + i * 20, iy = height - 19;
            g.renderItem(stack, ix, iy);
            g.renderItemDecorations(mc.font, stack, ix, iy);
        }
        if (!s.offhand.isEmpty()) {
            g.blit(WIDGETS, x - 29, height - 23, 24, 22, 29, 24);
            g.renderItem(s.offhand, x - 26, height - 19);
            g.renderItemDecorations(mc.font, s.offhand, x - 26, height - 19);
        }
    }
}
