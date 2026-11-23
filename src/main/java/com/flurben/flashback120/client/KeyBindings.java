package com.flurben.flashback120.client;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import com.flurben.flashback120.recording.RecordingManager;
import com.mojang.blaze3d.platform.InputConstants;

/** Keybinds of the mod. All are unbound by default, so they never clash with other mods. */
public final class KeyBindings {

    private static final String CATEGORY = "Flashback";
    private static KeyMapping start, pause, finish;
    private static final KeyMapping[] markers = new KeyMapping[4];

    private KeyBindings() {}

    public static void register() {
        start = KeyBindingHelper.registerKeyBinding(new KeyMapping("Start / finish recording", InputConstants.UNKNOWN.getValue(), CATEGORY));
        pause = KeyBindingHelper.registerKeyBinding(new KeyMapping("Pause / unpause recording", InputConstants.UNKNOWN.getValue(), CATEGORY));
        finish = KeyBindingHelper.registerKeyBinding(new KeyMapping("Cancel recording", InputConstants.UNKNOWN.getValue(), CATEGORY));
        for (int i = 0; i < markers.length; i++) {
            markers[i] = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("Add marker " + (i + 1), InputConstants.UNKNOWN.getValue(), CATEGORY));
        }
    }

    public static void tick(Minecraft mc) {
        while (start.consumeClick()) {
            if (RecordingManager.INSTANCE.isRecording()) RecordingManager.INSTANCE.finish();
            else RecordingManager.INSTANCE.start();
        }
        while (pause.consumeClick()) RecordingManager.INSTANCE.togglePause();
        while (finish.consumeClick()) RecordingManager.INSTANCE.cancel();
        for (int i = 0; i < markers.length; i++) {
            while (markers[i].consumeClick()) RecordingManager.INSTANCE.addMarker(i);
        }
    }
}
