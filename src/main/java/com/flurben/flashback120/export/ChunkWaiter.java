package com.flurben.flashback120.export;

import java.lang.reflect.Method;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import com.flurben.flashback120.Flashback120;

/**
 * Tells whether all visible chunks have finished building, so exported frames never contain holes. Supports the
 * vanilla renderer and Sodium (read-only reflection, no Sodium dependency).
 */
public final class ChunkWaiter {

    private static boolean sodiumChecked;
    private static Method sodiumInstance;
    private static Method sodiumComplete;

    private ChunkWaiter() {}

    public static boolean isComplete() {
        Object sodium = sodiumRenderer();
        if (sodium != null) {
            try {
                return (Boolean) sodiumComplete.invoke(sodium);
            } catch (Throwable t) {
                Flashback120.LOG.debug("Sodium completion check failed", t);
                sodiumComplete = null;
            }
        }
        try {
            return Minecraft.getInstance().levelRenderer.hasRenderedAllChunks();
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object sodiumRenderer() {
        if (!sodiumChecked) {
            sodiumChecked = true;
            if (FabricLoader.getInstance().isModLoaded("sodium")) {
                try {
                    Class<?> c = Class.forName("me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer");
                    sodiumInstance = c.getMethod("instanceNullable");
                    sodiumComplete = c.getMethod("isTerrainRenderComplete");
                    Flashback120.LOG.info("Sodium detected: export waits for its chunk builds");
                } catch (Throwable t) {
                    Flashback120.LOG.info("Sodium found but its renderer could not be checked: {}", t.toString());
                    sodiumInstance = null;
                }
            }
        }
        if (sodiumInstance == null || sodiumComplete == null) return null;
        try {
            return sodiumInstance.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
