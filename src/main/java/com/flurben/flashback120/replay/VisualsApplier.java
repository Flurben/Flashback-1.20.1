package com.flurben.flashback120.replay;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;

import com.flurben.flashback120.editor.Visuals;

/**
 * Applies visual settings that map onto game state (clouds) and restores the original state when the replay closes.
 * The sky, entity, particle and HUD toggles are applied by small render hooks that read {@link ReplayHandler#visuals()}.
 */
public final class VisualsApplier {

    private static CloudStatus savedClouds;

    private VisualsApplier() {}

    public static void apply(Visuals v, boolean exporting) {
        Minecraft mc = Minecraft.getInstance();
        if (savedClouds == null) savedClouds = mc.options.cloudStatus().get();
        CloudStatus wanted = v.renderClouds ? savedClouds : CloudStatus.OFF;
        if (mc.options.cloudStatus().get() != wanted) mc.options.cloudStatus().set(wanted);
    }

    public static void restore() {
        Minecraft mc = Minecraft.getInstance();
        if (savedClouds != null) {
            mc.options.cloudStatus().set(savedClouds);
            savedClouds = null;
        }
    }
}
