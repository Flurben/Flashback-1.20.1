package com.flurben.flashback120.dev;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.editor.Interpolation;
import com.flurben.flashback120.editor.TrackType;
import com.flurben.flashback120.export.ExportSettings;
import com.flurben.flashback120.export.Exporter;
import com.flurben.flashback120.export.FFmpegEncoder;
import com.flurben.flashback120.export.VideoEncoders;
import com.flurben.flashback120.gui.ExportScreen;
import com.flurben.flashback120.gui.ReplaySelectScreen;
import com.flurben.flashback120.recording.RecordingManager;
import com.flurben.flashback120.replay.CameraEntity;
import com.flurben.flashback120.replay.ReplayHandler;
import com.mojang.blaze3d.platform.NativeImage;

/**
 * Test harness for driving the game without a person, used to check the mod end to end. Only active when the game is
 * started with {@code -Dflashback120.dev=true}. Commands are read line by line from
 * {@code <game dir>/fb120-dev/commands.txt} (append to it while the game runs) and results go to {@code results.log}
 * in the same folder, with screenshots next to them.
 */
public final class DevDriver {

    private final Minecraft mc = Minecraft.getInstance();
    private final File dir = new File(mc.gameDirectory, "fb120-dev");
    private final File commands = new File(dir, "commands.txt");
    private final File results = new File(dir, "results.log");
    private final Deque<String> pending = new ArrayDeque<>();
    private long readOffset;
    private long nextPoll;
    private long waitUntil;
    private String waitCondition;
    private long waitDeadline;
    private String worldShot;

    public void install() {
        dir.mkdirs();
        readOffset = commands.exists() ? commands.length() : 0;
        log("driver ready");
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(ctx -> {
            if (worldShot != null) {
                String name = worldShot;
                worldShot = null;
                log("camera rot=" + ctx.camera().getXRot() + "," + ctx.camera().getYRot() + " pos=" + ctx.camera().getPosition()
                    + " fluid=" + ctx.camera().getFluidInCamera());
                screenshot(name);
            }
        });
    }

    private void log(String line) {
        Flashback120.LOG.info("[dev] {}", line);
        try (PrintWriter w = new PrintWriter(new FileWriter(results, StandardCharsets.UTF_8, true))) {
            w.println(System.currentTimeMillis() + " " + line);
        } catch (IOException ignored) {}
    }

    private void tick() {
        // the harness runs while the window is in the background
        if (mc.options != null) mc.options.pauseOnLostFocus = false;
        long now = System.currentTimeMillis();
        if (now >= nextPoll) {
            nextPoll = now + 250;
            poll();
        }
        if (now < waitUntil) return;
        if (waitCondition != null) {
            if (conditionMet(waitCondition)) {
                log("ok waitfor " + waitCondition);
                waitCondition = null;
            } else if (now > waitDeadline) {
                log("timeout waitfor " + waitCondition);
                waitCondition = null;
            } else {
                return;
            }
        }
        String cmd = pending.poll();
        if (cmd == null) return;
        try {
            run(cmd.trim());
        } catch (Throwable t) {
            log("error " + cmd + ": " + t);
            Flashback120.LOG.error("[dev] command failed: {}", cmd, t);
        }
    }

    private void poll() {
        if (!commands.exists() || commands.length() <= readOffset) return;
        try (RandomAccessFile f = new RandomAccessFile(commands, "r")) {
            f.seek(readOffset);
            byte[] data = new byte[(int) (f.length() - readOffset)];
            f.readFully(data);
            String text = new String(data, StandardCharsets.UTF_8);
            int lastNewline = text.lastIndexOf('\n');
            if (lastNewline < 0) return;
            readOffset += text.substring(0, lastNewline + 1).getBytes(StandardCharsets.UTF_8).length;
            for (String line : text.substring(0, lastNewline).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) pending.add(line.trim());
            }
        } catch (IOException e) {
            log("error reading commands: " + e);
        }
    }

    private boolean conditionMet(String c) {
        return switch (c) {
            case "world" -> mc.level != null && mc.player != null && mc.screen == null;
            case "noworld" -> mc.level == null;
            case "replay" -> ReplayHandler.isReplaying() && mc.level != null;
            case "noexport" -> !Exporter.isExporting();
            case "title" -> mc.screen instanceof TitleScreen;
            default -> mc.screen != null && mc.screen.getClass().getSimpleName().equals(c);
        };
    }

    private void run(String cmd) throws Exception {
        String[] a = cmd.split(" ");
        log("> " + cmd);
        switch (a[0]) {
            case "wait" -> waitUntil = System.currentTimeMillis() + Long.parseLong(a[1]);
            case "waitfor" -> {
                waitCondition = a[1];
                waitDeadline = System.currentTimeMillis() + (a.length > 2 ? Long.parseLong(a[2]) : 60000);
            }
            case "createworld" -> {
                String name = a[1];
                LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
                long seed = a.length > 2 ? Long.parseLong(a[2]) : 12345L;
                mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(seed, true, false),
                    access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL)
                        .value().createWorldDimensions());
            }
            case "openworld" -> mc.createWorldOpenFlows().loadLevel(mc.screen, a[1]);
            case "leave" -> {
                if (mc.level != null) {
                    boolean local = mc.isLocalServer();
                    mc.level.disconnect();
                    if (local) mc.clearLevel(new GenericDirtMessageScreen(Component.literal("Saving")));
                    else mc.clearLevel();
                }
                mc.setScreen(new TitleScreen());
            }
            case "cmd" -> mc.player.connection.sendCommand(cmd.substring(4));
            case "chat" -> mc.player.connection.sendChat(cmd.substring(5));
            case "look" -> {
                mc.player.setYRot(Float.parseFloat(a[1]));
                mc.player.setXRot(Float.parseFloat(a[2]));
            }
            case "walk" -> {
                // walks forward for a number of ticks
                int ticks = Integer.parseInt(a[1]);
                mc.options.keyUp.setDown(true);
                waitUntil = System.currentTimeMillis() + ticks * 50L;
                pending.addFirst("release");
            }
            case "release" -> mc.options.keyUp.setDown(false);
            case "config" -> {
                if (a[1].equals("quicksave")) Config.get().quicksave = Boolean.parseBoolean(a[2]);
                if (a[1].equals("autostart")) Config.get().autoStartRecording = Boolean.parseBoolean(a[2]);
                Config.save();
            }
            case "record" -> {
                switch (a[1]) {
                    case "start" -> RecordingManager.INSTANCE.start();
                    case "finish" -> RecordingManager.INSTANCE.finish();
                    case "pause" -> RecordingManager.INSTANCE.togglePause();
                    case "cancel" -> RecordingManager.INSTANCE.cancel();
                    case "marker" -> RecordingManager.INSTANCE.addMarker(0);
                    default -> log("unknown record command");
                }
                log("recording " + RecordingManager.INSTANCE.isRecording());
            }
            case "replays" -> mc.setScreen(new ReplaySelectScreen(new TitleScreen()));
            case "replay" -> replay(a);
            case "screenshot" -> screenshot(a[1]);
            case "state" -> state();
            case "worldshot" -> {
                worldShot = a[1];
            }
            case "hideui" -> mc.options.hideGui = Boolean.parseBoolean(a[1]);
            case "debug" -> {
                log("debug chunks=" + mc.levelRenderer.getChunkStatistics() + " camera=" + mc.getCameraEntity()
                    + " player=" + mc.player + " renderDistance=" + mc.options.getEffectiveRenderDistance()
                    + " gameMode=" + (mc.gameMode == null ? null : mc.gameMode.getPlayerMode()));
                if (mc.level != null && mc.player != null) {
                    var pos = mc.player.blockPosition();
                    log("debug block below=" + mc.level.getBlockState(pos.below()) + " light=" + mc.level.getMaxLocalRawBrightness(pos)
                        + " chunkLoaded=" + mc.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4));
                }
            }
            case "quit" -> mc.stop();
            default -> log("unknown command " + a[0]);
        }
    }

    private void replay(String[] a) throws Exception {
        ReplayHandler h = ReplayHandler.get();
        switch (a[1]) {
            case "open" -> {
                File file;
                if (a.length < 3 || a[2].equals("latest")) {
                    File[] all = RecordingManager.replaysDir().listFiles((d, n) -> n.endsWith(".zip"));
                    if (all == null || all.length == 0) {
                        log("no replays");
                        return;
                    }
                    Arrays.sort(all, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
                    file = all[0];
                } else {
                    file = new File(RecordingManager.replaysDir(), String.join(" ", Arrays.copyOfRange(a, 2, a.length)));
                }
                ReplayHandler.open(file);
                log("opened " + file.getName() + " ticks " + ReplayHandler.get().getTotalTicks());
            }
            case "seek" -> h.seek(Integer.parseInt(a[2]));
            case "play" -> h.setPaused(false);
            case "pause" -> h.setPaused(true);
            case "speed" -> h.setSpeed(Float.parseFloat(a[2]));
            case "spectate" -> {
                var e = mc.level.getEntity(h.getLocalPlayerId());
                if (e != null) h.spectate(e);
                log("spectating " + h.isSpectating());
            }
            case "unspectate" -> h.stopSpectating();
            case "camera" -> {
                CameraEntity c = h.getCamera();
                c.setCameraPosition(Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]),
                    Float.parseFloat(a[5]), Float.parseFloat(a[6]));
            }
            case "keyframe" -> {
                TrackType type = TrackType.valueOf(a[2].toUpperCase());
                int tick = Integer.parseInt(a[3]);
                double[] values = new double[a.length - 4];
                for (int i = 0; i < values.length; i++) values[i] = Double.parseDouble(a[4 + i]);
                h.editor.track(type).put(tick, values, Interpolation.SMOOTH);
                h.markEditorDirty();
            }
            case "undo" -> log("undo " + h.history.undo(h.editor) + " " + h.history.describe());
            case "redo" -> log("redo " + h.history.redo(h.editor) + " " + h.history.describe());
            case "settle" -> {
                h.history.settle(h.editor);
                log("history " + h.history.describe());
            }
            case "visual" -> {
                var v = h.editor.visuals;
                switch (a[2]) {
                    case "weather" -> v.weather = com.flurben.flashback120.editor.Visuals.Weather.valueOf(a[3].toUpperCase());
                    case "time" -> {
                        v.overrideTime = true;
                        v.time = Integer.parseInt(a[3]);
                    }
                    case "roll" -> v.cameraRoll = Float.parseFloat(a[3]);
                    case "entities" -> v.renderEntities = Boolean.parseBoolean(a[3]);
                    default -> log("unknown visual");
                }
                h.markEditorDirty();
            }
            case "exportscreen" -> mc.setScreen(new ExportScreen(mc.screen));
            case "export" -> {
                ExportSettings s = new ExportSettings();
                s.width = Integer.parseInt(a[2]);
                s.height = Integer.parseInt(a[3]);
                s.fps = Integer.parseInt(a[4]);
                s.format = FFmpegEncoder.Format.parse(a[5]);
                s.startTick = Integer.parseInt(a[6]);
                s.endTick = Integer.parseInt(a[7]);
                s.encoder = a.length > 8 ? VideoEncoders.Choice.parse(a[8]) : VideoEncoders.Choice.AUTO;
                s.ffmpegPath = Config.get().ffmpegPath;
                s.warmupFrames = 10;
                String ext = s.format == FFmpegEncoder.Format.PNG ? "" : "." + s.format.extension;
                s.output = RecordingManager.uniqueFile(ExportScreen.exportsDir(), "dev-export", ext);
                Exporter.start(h, s);
                log("export started " + s.output.getName() + " frames " + Exporter.get().getTotalFrames());
            }
            case "exit" -> h.exit();
            default -> log("unknown replay command");
        }
    }

    private void screenshot(String name) {
        try (NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            File out = new File(dir, name + ".png");
            image.writeToFile(out);
            log("screenshot " + out.getName() + " " + image.getWidth() + "x" + image.getHeight());
        } catch (IOException e) {
            log("screenshot failed " + e);
        }
    }

    private void state() {
        StringBuilder b = new StringBuilder("state");
        b.append(" screen=").append(mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
        b.append(" level=").append(mc.level != null);
        if (mc.level != null) {
            int entities = 0;
            for (var ignored : mc.level.entitiesForRendering()) entities++;
            b.append(" entities=").append(entities);
            b.append(" chunks=").append(mc.level.getChunkSource().getLoadedChunksCount());
            b.append(" players=").append(mc.level.players().size());
        }
        if (mc.player != null) {
            b.append(String.format(" pos=%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ()));
        }
        b.append(" recording=").append(RecordingManager.INSTANCE.isRecording());
        ReplayHandler h = ReplayHandler.get();
        if (h != null) {
            b.append(" replayTick=").append(h.getCurrentTick()).append('/').append(h.getTotalTicks());
            b.append(" paused=").append(h.isPaused());
            b.append(" localPlayer=").append(h.getLocalPlayerId());
            b.append(" spectating=").append(h.isSpectating());
            b.append(" history=").append(h.history.describe());
        }
        if (Exporter.isExporting()) {
            b.append(" export=").append(Exporter.get().getFramesWritten()).append('/').append(Exporter.get().getTotalFrames());
        }
        log(b.toString());
    }
}
