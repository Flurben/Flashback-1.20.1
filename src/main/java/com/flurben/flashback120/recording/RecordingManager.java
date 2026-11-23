package com.flurben.flashback120.recording;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;

import org.apache.commons.io.FileUtils;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.format.ReplayArchive;
import com.flurben.flashback120.format.ReplayFormat;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.gui.SaveReplayScreen;
import com.flurben.flashback120.replay.ReplayHandler;
import com.flurben.flashback120.util.Notifications;

/** Owns the active recording and reacts to connection events. */
public final class RecordingManager {

    public static final RecordingManager INSTANCE = new RecordingManager();
    public static final int[] MARKER_COLORS = { 0xFFD94040, 0xFF40A0E0, 0xFF50D050, 0xFFE0C040 };

    private volatile Recorder current;

    private RecordingManager() {}

    public static File rootDir() {
        return Flashback120.rootDir();
    }

    public static File recordingDir() {
        File f = new File(rootDir(), "recording");
        f.mkdirs();
        return f;
    }

    public static File replaysDir() {
        File f = new File(rootDir(), "replays");
        f.mkdirs();
        return f;
    }

    public Recorder getCurrent() {
        return current;
    }

    public boolean isRecording() {
        return current != null;
    }

    // ---------------------------------------------------------------- events

    /** The connection to a server (or the integrated server) was made, before the game starts. */
    public void onConnected(Connection connection) {
        if (!Config.get().autoStartRecording || current != null || ReplayHandler.isReplaying()) return;
        if (connection == null || connection.channel == null) return;
        try {
            Recorder r = new Recorder(Minecraft.getInstance(), connection, true, recordingDir());
            r.attach();
            current = r;
        } catch (Throwable t) {
            Flashback120.LOG.error("Unable to auto-start recording", t);
        }
    }

    public void onClientTick() {
        Recorder r = current;
        if (r == null) return;
        Minecraft mc = Minecraft.getInstance();
        boolean disconnected = !r.getConnection().isConnected() || (r.isStarted() && mc.level == null);
        if (disconnected) {
            if (r.isStarted()) finish();
            else cancel();
            return;
        }
        boolean wasStarted = r.isStarted();
        r.tick();
        if (!wasStarted && r.isStarted() && Config.get().showRecordingToasts) {
            Notifications.show("Flashback", "Started recording");
        }
    }

    // ---------------------------------------------------------------- controls

    public void start() {
        Minecraft mc = Minecraft.getInstance();
        if (current != null) {
            Notifications.show("Already Recording", "Cannot start a new recording while recording");
            return;
        }
        if (ReplayHandler.isReplaying() || mc.getConnection() == null || mc.level == null) return;
        Connection connection = mc.getConnection().getConnection();
        try {
            Recorder r = new Recorder(mc, connection, false, recordingDir());
            r.attach();
            current = r;
            r.tick(); // begin immediately (snapshot)
            if (Config.get().showRecordingToasts) Notifications.show("Flashback", "Started recording");
        } catch (Throwable t) {
            Flashback120.LOG.error("Unable to start recording", t);
            Notifications.show("Flashback", "Unable to start recording: " + t.getMessage());
        }
    }

    public void togglePause() {
        Recorder r = current;
        if (r == null) return;
        r.setPaused(!r.isPaused());
        if (Config.get().showRecordingToasts) {
            Notifications.show("Flashback", r.isPaused() ? "Paused recording" : "Unpaused recording");
        }
    }

    public void cancel() {
        Recorder r = current;
        if (r == null) return;
        current = null;
        r.stop();
        try {
            FileUtils.deleteDirectory(r.getWorkDir());
        } catch (IOException e) {
            Flashback120.LOG.warn("Unable to delete {}", r.getWorkDir(), e);
        }
        if (Config.get().showRecordingToasts && r.isStarted()) Notifications.show("Flashback", "Cancelled recording");
    }

    /** Stops the recording and either quicksaves it or asks for a name. */
    public void finish() {
        Recorder r = current;
        if (r == null) {
            Notifications.show("Not Recording", "Cannot finish recording when not recording");
            return;
        }
        current = null;
        r.stop();
        if (!r.isStarted() || r.getTicks() == 0) {
            try {
                FileUtils.deleteDirectory(r.getWorkDir());
            } catch (IOException ignored) {}
            return;
        }
        if (Config.get().showRecordingToasts) Notifications.show("Flashback", "Finished recording");
        ReplayMeta meta = r.getMeta();
        if (Config.get().quicksave) {
            saveAsync(r.getWorkDir(), meta, defaultName(meta));
        } else {
            SaveReplayScreen.open(r.getWorkDir(), meta);
        }
    }

    public void addMarker(int index) {
        Recorder r = current;
        if (r == null || !r.isStarted()) {
            Notifications.show("Not Recording", "Markers can only be added while recording");
            return;
        }
        r.addMarker(MARKER_COLORS[Math.max(0, Math.min(3, index))], "", true);
        Notifications.show("Flashback", "Added marker #" + (index + 1));
    }

    public static String defaultName(ReplayMeta meta) {
        String base = meta.worldName == null || meta.worldName.isEmpty() ? "Replay" : meta.worldName;
        return base + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(new Date(meta.createdAt));
    }

    public static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (s.isEmpty()) s = "Replay";
        if (s.length() > 100) s = s.substring(0, 100);
        return s;
    }

    /** Packages a finished work directory into a replay archive on a background thread. */
    public static void saveAsync(final File workDir, final ReplayMeta meta, final String name) {
        meta.name = name;
        Thread t = new Thread(() -> {
            try {
                File target = uniqueFile(replaysDir(), sanitize(name), ".zip");
                Map<String, Object> extra = new HashMap<>();
                File thumb = new File(workDir, ReplayFormat.ENTRY_THUMBNAIL);
                if (thumb.exists()) extra.put(ReplayFormat.ENTRY_THUMBNAIL, thumb);
                long start = System.nanoTime();
                ReplayArchive.write(target, meta, new File(workDir, ReplayFormat.ENTRY_STREAM), extra);
                Flashback120.LOG.info("Saved {} in {} ms: {} bytes", target.getName(),
                    (System.nanoTime() - start) / 1_000_000L, target.length());
                FileUtils.deleteDirectory(workDir);
                Notifications.show("Replay saved", target.getName());
            } catch (Throwable e) {
                Flashback120.LOG.error("Unable to save replay", e);
                Notifications.show("Error Saving Replay", String.valueOf(e.getMessage()), 8000);
            }
        }, "Flashback replay save");
        t.start();
    }

    public static File uniqueFile(File dir, String base, String ext) {
        File f = new File(dir, base + ext);
        int i = 1;
        while (f.exists()) f = new File(dir, base + " (" + (i++) + ")" + ext);
        return f;
    }

    /** Work directories left behind by a crash. */
    public List<File> findRecoverable() {
        List<File> out = new ArrayList<>();
        File[] dirs = recordingDir().listFiles();
        if (dirs == null) return out;
        Recorder r = current;
        for (File d : dirs) {
            if (!d.isDirectory()) continue;
            if (r != null && d.equals(r.getWorkDir())) continue;
            if (new File(d, ReplayFormat.ENTRY_STREAM).exists()) out.add(d);
        }
        return out;
    }
}
