package com.flurben.flashback120.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.recording.Recorder;
import com.flurben.flashback120.recording.RecordingManager;
import com.mojang.blaze3d.platform.NativeImage;

/** Takes the thumbnail of a recording from the rendered world, before the HUD is drawn. */
public final class Thumbnails {

    private Thumbnails() {}

    public static void afterWorldRender() {
        Recorder r = RecordingManager.INSTANCE.getCurrent();
        Minecraft mc = Minecraft.getInstance();
        if (r == null || !r.wantsThumbnail() || mc.screen != null) return;
        try (NativeImage full = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int w = 320, h = Math.max(1, full.getHeight() * 320 / Math.max(1, full.getWidth()));
            try (NativeImage small = new NativeImage(w, h, false)) {
                full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), small);
                r.saveThumbnail(small.asByteArray());
            }
        } catch (Throwable t) {
            Flashback120.LOG.debug("Unable to take a thumbnail", t);
            r.saveThumbnail(new byte[0]);
        }
    }
}
