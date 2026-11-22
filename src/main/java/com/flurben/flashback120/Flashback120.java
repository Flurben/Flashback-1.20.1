package com.flurben.flashback120;

import java.io.File;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of Flashback 1.20.1, an independent replay mod for Fabric 1.20.1 based on FlashbackNH. */
public final class Flashback120 implements ClientModInitializer {

    public static final String MOD_ID = "flashback120";
    public static final String NAME = "Flashback 1.20.1";
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static String version() {
        return FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("dev");
    }

    /** {@code <game dir>/flashback120}: replays, caches and the editor's own files. */
    public static File rootDir() {
        File dir = FabricLoader.getInstance().getGameDir().resolve("flashback120").toFile();
        dir.mkdirs();
        return dir;
    }

    @Override
    public void onInitializeClient() {
        LOG.info("{} {} starting", NAME, version());
    }
}
