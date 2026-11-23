package com.flurben.flashback120.export;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.editor.KeyframeTrack;
import com.flurben.flashback120.editor.TrackType;
import com.flurben.flashback120.gui.ReplayEditorScreen;
import com.flurben.flashback120.replay.ReplayHandler;
import com.flurben.flashback120.util.Notifications;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;

/**
 * Renders the replay frame by frame and feeds the frames to an encoder.
 *
 * <p>
 * Frames are produced by the game's normal render loop: the replay clock is driven by the exporter (one step per saved
 * frame) and every frame is read back from the main framebuffer after the complete frame, including shader composite
 * passes, has been rendered. The main framebuffer is resized to the export resolution the same way a window resize
 * would do it, which keeps shader packs and post-processing consistent.
 */
public final class Exporter implements ReplayHandler.ClockDriver {

    private static Exporter active;

    public static boolean isExporting() {
        return active != null;
    }

    public static Exporter get() {
        return active;
    }

    public static boolean hidesNametags() {
        Exporter e = active;
        return e != null && e.settings.hideNametags;
    }

    private final Minecraft mc = Minecraft.getInstance();
    private final ReplayHandler handler;
    private final ExportSettings settings;
    private final FrameEncoder encoder;
    private final int totalFrames;
    private final long startedAt = System.currentTimeMillis();

    private double time;
    private boolean advance;
    private int framesWritten;
    private int warmupLeft;
    private int extraLeft;
    private int waited;
    private FrameReader reader;
    private long handOffNanos;
    private boolean finished;
    private final ExportStats stats = new ExportStats();

    private boolean savedPaused;

    private Exporter(ReplayHandler handler, ExportSettings settings, FrameEncoder encoder) {
        this.handler = handler;
        this.settings = settings;
        this.encoder = encoder;
        this.totalFrames = countFrames(handler, settings);
    }

    public static void start(ReplayHandler handler, ExportSettings settings) throws IOException {
        if (active != null) throw new IllegalStateException("Already exporting");
        settings.width = Math.max(16, settings.width & ~1);
        settings.height = Math.max(16, settings.height & ~1);
        if (settings.endTick <= settings.startTick) throw new IOException("The export range is empty");
        FrameEncoder encoder;
        if (settings.format == FFmpegEncoder.Format.PNG) {
            encoder = new PngSequenceEncoder(settings.output, settings.pngCompression);
        } else {
            if (settings.format == FFmpegEncoder.Format.MP4 || settings.format == FFmpegEncoder.Format.MKV) {
                settings.resolvedEncoder = resolveEncoder(settings);
            }
            encoder = new FFmpegEncoder(settings);
        }
        Exporter e = new Exporter(handler, settings, encoder);
        active = e;
        e.begin();
    }

    /**
     * The encoder to use: Auto's pick, or the chosen one. A hardware encoder first encodes a test frame of the export's
     * size; if it fails the export uses libx264 and says so.
     */
    private static VideoEncoders.Choice resolveEncoder(ExportSettings s) {
        VideoEncoders.Choice c = s.encoder == null ? VideoEncoders.Choice.LIBX264 : s.encoder;
        if (c == VideoEncoders.Choice.AUTO) c = VideoEncoders.auto(s.ffmpegPath);
        if (c == VideoEncoders.Choice.LIBX264) return c;
        if (VideoEncoders.test(s.ffmpegPath, c, s.width, s.height, s.crf)) {
            Flashback120.LOG.info("Exporting with the hardware encoder {}", c.codec);
            return c;
        }
        Flashback120.LOG.warn("Hardware encoder {} failed its test, exporting with libx264", c.codec);
        Notifications.show("Flashback", c.displayName + " did not work: exporting with x264", 8000);
        return VideoEncoders.Choice.LIBX264;
    }

    /** How many frames an export of these settings saves, speed keyframes included. */
    public static int countFrames(ReplayHandler handler, ExportSettings settings) {
        double t = settings.startTick;
        int n = 0;
        while (t <= settings.endTick && n < 10_000_000) {
            n++;
            t = step(handler, settings, t);
        }
        return n;
    }

    private double step(double t) {
        return step(handler, settings, t);
    }

    private static double step(ReplayHandler handler, ExportSettings settings, double t) {
        double speed = 1.0;
        KeyframeTrack track = handler.editor.track(TrackType.SPEED);
        if (settings.useSpeedTrack && track.isActive()) speed = Math.max(0.01, track.evaluate(t)[0]);
        return t + speed * 20.0 / settings.fps;
    }

    private void begin() {
        savedPaused = handler.isPaused();

        mc.setScreen(null);
        mc.getSoundManager().pause();
        mc.getWindow().updateVsync(false);
        resizeTo(settings.width, settings.height);
        reader = settings.asyncReadback ? FrameReader.create(settings.width, settings.height)
            : new FrameReader.Direct(settings.width, settings.height);

        handler.seekReloading(settings.startTick);
        time = settings.startTick;
        advance = false;
        warmupLeft = Math.max(1, settings.warmupFrames);
        extraLeft = settings.extraFrames;
        handler.setClockDriver(this);
        Flashback120.LOG.info("Export started: {}x{} @ {} fps, ticks {}-{}, {} frames -> {}", settings.width,
            settings.height, settings.fps, settings.startTick, settings.endTick, totalFrames, settings.output);
    }

    /** Sets the size the game renders at, like a window resize does. */
    private void resizeTo(int width, int height) {
        Window window = mc.getWindow();
        if (window.getWidth() == width && window.getHeight() == height) return;
        window.framebufferWidth = width;
        window.framebufferHeight = height;
        mc.resizeDisplay();
    }

    /** Exports run as fast as they can: no frame limit. */
    public static boolean unlimitedFramerate() {
        return active != null;
    }

    /** Called at the start of every rendered frame. */
    public void onFrameStart() {
        if (handler.isSeeking()) return;
        if (!finished) stats.frameStart();
    }

    @Override
    public double nextFrameTime() {
        if (advance) {
            advance = false;
            time = step(time);
        }
        return time;
    }

    /** Called at the end of every rendered frame, before it is shown. */
    public void onFrameRendered() {
        // loading a world renders frames of its own while the replay seeks
        if (finished || handler.isSeeking() || mc.level == null || mc.player == null) return;
        if (InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_ESCAPE)) {
            cancel("Export cancelled");
            return;
        }
        if (mc.getWindow().getWidth() != settings.width || mc.getWindow().getHeight() != settings.height) {
            // the window was resized: force our size back
            resizeTo(settings.width, settings.height);
            warmupLeft = Math.max(warmupLeft, 5);
        }
        if (mc.screen != null) mc.setScreen(null);
        long renderedAt = stats.frameRendered();
        int skipped = -1;
        try {
            if (warmupLeft > 0) {
                warmupLeft--;
                skipped = 0;
                return;
            }
            if (settings.waitForChunks && waited < 240 && !ChunkWaiter.isComplete()) {
                waited++;
                skipped = 1;
                return;
            }
            if (extraLeft > 0) {
                extraLeft--;
                skipped = 2;
                return;
            }
            handOffNanos = 0;
            readback();
            long done = System.nanoTime();
            stats.saved(renderedAt, done - handOffNanos, done);
            framesWritten++;
            waited = 0;
            extraLeft = settings.extraFrames;
            if (framesWritten >= totalFrames) {
                reader.flush(sink);
                finish();
                return;
            }
            advance = true;
        } catch (Throwable t) {
            Flashback120.LOG.error("Export failed", t);
            cancel("Export failed: " + t.getMessage());
        } finally {
            if (!finished) {
                long p = System.nanoTime();
                drawProgress();
                stats.progress += System.nanoTime() - p;
                if (skipped >= 0) stats.skipped(renderedAt, skipped);
            }
        }
    }

    private void readback() throws IOException {
        int w = settings.width, h = settings.height;
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width != w || target.height != h) {
            throw new IOException("Framebuffer is " + target.width + "x" + target.height + ", expected " + w + "x" + h);
        }
        target.bindWrite(false);
        reader.read(w, h, sink);
    }

    private final FrameReader.Sink sink = this::handOff;

    private void handOff(ByteBuffer rgb, int w, int h) throws IOException {
        long t = System.nanoTime();
        encoder.encode(rgb, w, h);
        handOffNanos += System.nanoTime() - t;
    }

    /** Drawn into the main framebuffer after the frame was read, so it shows on screen but not in the video. */
    private void drawProgress() {
        Window window = mc.getWindow();
        int scale = Math.max(1, (int) Math.round(window.getHeight() / 360.0));
        int sw = window.getWidth() / scale, sh = window.getHeight() / scale;
        Matrix4f projection = new Matrix4f().setOrtho(0, sw, sh, 0, 1000, 3000);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.getModelViewStack().translate(0, 0, -2000);
        RenderSystem.applyModelViewMatrix();
        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        String line1 = String.format("Exporting %d / %d frames (%.1f%%)", framesWritten, totalFrames,
            framesWritten * 100.0 / Math.max(1, totalFrames));
        long elapsed = System.currentTimeMillis() - startedAt;
        String eta = framesWritten > 0 ? formatMs((long) ((double) elapsed / framesWritten * (totalFrames - framesWritten)))
            : "...";
        String line2 = "Elapsed " + formatMs(elapsed) + "  |  remaining " + eta + "  |  ESC to cancel";
        int boxW = Math.max(mc.font.width(line1), mc.font.width(line2)) + 16;
        int x = (sw - boxW) / 2, y = 6;
        g.fill(x, y, x + boxW, y + 36, 0xD0101014);
        int barW = boxW - 16;
        g.fill(x + 8, y + 26, x + 8 + barW, y + 30, 0xFF303040);
        g.fill(x + 8, y + 26, x + 8 + (int) (barW * framesWritten / (double) Math.max(1, totalFrames)), y + 30, 0xFF4F8FE0);
        g.drawString(mc.font, line1, x + 8, y + 4, 0xFFFFFF);
        g.drawString(mc.font, line2, x + 8, y + 15, 0xAAAAAA);
        g.flush();
        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
    }

    private static String formatMs(long ms) {
        long s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void restore() {
        handler.setClockDriver(null);
        if (reader != null) reader.dispose();
        handler.setPaused(true);
        Window window = mc.getWindow();
        window.refreshFramebufferSize();
        mc.resizeDisplay();
        window.updateVsync(mc.options.enableVsync().get());
        mc.getSoundManager().resume();
        active = null;
        if (!savedPaused) handler.setPaused(false);
        mc.setScreen(new ReplayEditorScreen());
    }

    private void finish() {
        finished = true;
        restore();
        final File out = settings.output;
        Notifications.show("Flashback", "Finishing export...");
        Thread t = new Thread(() -> {
            try {
                long closing = System.nanoTime();
                encoder.close();
                stats.finish = System.nanoTime() - closing;
                stats.log("finished", encoder.stats());
                ExportEstimator.record(settings, stats.msPerFrame(), outputSize(out), stats.savedFrames);
                Notifications.show("Export finished", out.getName(), 8000);
                MutableComponent msg = Component.literal("[Flashback] Export saved: ");
                msg.append(Component.literal(out.getName()).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, out.getAbsolutePath()))));
                mc.execute(() -> mc.gui.getChat().addMessage(msg));
            } catch (Throwable e) {
                Flashback120.LOG.error("Export failed while finishing", e);
                Notifications.show("Export failed", String.valueOf(e.getMessage()), 10000);
            }
        }, "Flashback export finish");
        t.start();
    }

    /** Bytes written: the video file, or all images of a PNG sequence. */
    private static long outputSize(File out) {
        if (out.isFile()) return out.length();
        long sum = 0;
        File[] files = out.listFiles();
        if (files != null) for (File f : files) sum += f.length();
        return sum;
    }

    public void cancel(String message) {
        if (finished) return;
        finished = true;
        stats.log("stopped (" + message + ")", encoder.stats());
        encoder.abort();
        restore();
        Notifications.show("Flashback", message, 6000);
    }

    public int getFramesWritten() {
        return framesWritten;
    }

    public int getTotalFrames() {
        return totalFrames;
    }
}
