package com.flurben.flashback120.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/** Short messages shown as toasts in the top right corner. Safe to call from any thread. */
public final class Notifications {

    private Notifications() {}

    public static void show(String title, String text) {
        show(title, text, 3500);
    }

    public static void show(String title, String text, long durationMs) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        mc.execute(() -> SystemToast.addOrUpdate(
            mc.getToasts(),
            SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
            Component.literal(title),
            text == null || text.isEmpty() ? null : Component.literal(text)));
    }
}
