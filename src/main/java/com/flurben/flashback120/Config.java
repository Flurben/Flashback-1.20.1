package com.flurben.flashback120;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import net.fabricmc.loader.api.FabricLoader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** The mod's settings, kept as {@code config/flashback120.json}. Fields are read directly by the rest of the mod. */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Config INSTANCE = new Config();

    // Recording
    public boolean autoStartRecording = false;
    public boolean autoFinishRecording = true;
    public boolean quicksave = false;
    public boolean showRecordingToasts = true;
    public boolean markDimensionChanges = true;
    public boolean recordHotbar = true;
    public boolean showRecordingIndicator = true;

    // Replay
    public float cameraBaseSpeed = 10.0f;
    public boolean cameraMomentum = true;
    public boolean showCameraPath = true;
    public String defaultInterpolation = "SMOOTH";
    public int skipSeconds = 5;
    public float uiScale = 1.0f;

    // Export
    public String ffmpegPath = "ffmpeg";
    public boolean exportAsyncReadback = true;
    public int exportPngCompression = 4;
    public String exportFilename = "%replay%-%date%-%time%";
    public int exportWarmupFrames = 30;

    // Advanced
    public Set<String> ignoredCustomPayloads = new LinkedHashSet<>();
    public boolean blockReplayGuis = true;

    private Config() {}

    public static Config get() {
        return INSTANCE;
    }

    private static File file() {
        return FabricLoader.getInstance().getConfigDir().resolve("flashback120.json").toFile();
    }

    public static void load() {
        File f = file();
        if (f.exists()) {
            try {
                Config c = GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Config.class);
                if (c != null) {
                    if (c.ignoredCustomPayloads == null) c.ignoredCustomPayloads = new LinkedHashSet<>();
                    if (c.ffmpegPath == null || c.ffmpegPath.isEmpty()) c.ffmpegPath = "ffmpeg";
                    if (c.exportFilename == null || c.exportFilename.isEmpty()) c.exportFilename = "%replay%-%date%-%time%";
                    if (c.defaultInterpolation == null) c.defaultInterpolation = "SMOOTH";
                    INSTANCE = c;
                }
            } catch (Exception e) {
                Flashback120.LOG.warn("Unable to read {}, using defaults", f, e);
            }
        }
        save();
    }

    public static void save() {
        try {
            File f = file();
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), GSON.toJson(INSTANCE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Flashback120.LOG.warn("Unable to save config", e);
        }
    }
}
