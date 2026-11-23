package com.flurben.flashback120.gui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.Flashback120;
import com.flurben.flashback120.editor.EditorState;
import com.flurben.flashback120.export.ExportEstimator;
import com.flurben.flashback120.export.ExportPreset;
import com.flurben.flashback120.export.ExportPresets;
import com.flurben.flashback120.export.ExportSettings;
import com.flurben.flashback120.export.Exporter;
import com.flurben.flashback120.export.FFmpegEncoder;
import com.flurben.flashback120.export.VideoEncoders;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.gui.imgui.ImGuiScreen;
import com.flurben.flashback120.recording.RecordingManager;
import com.flurben.flashback120.replay.ReplayHandler;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

/**
 * The export screen, laid out like the deliver page of a video editor: render settings with preset tiles on the left,
 * the replay framed as the export frames it in the middle, the output estimate and the Render button on the right, and
 * a timeline for the export range at the bottom.
 */
public final class ExportScreen extends ImGuiScreen {

    private static final String[] X264_PRESETS = { "ultrafast", "superfast", "veryfast", "faster", "fast", "medium",
        "slow", "slower", "veryslow" };
    private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 }, { 2560, 1440 }, { 3840, 2160 }, { 1080, 1920 },
        { 1080, 1080 }, { 854, 480 } };
    private static final String[] SIZE_NAMES = { "720p (1280x720)", "1080p (1920x1080)", "1440p (2560x1440)",
        "4K (3840x2160)", "Vertical (1080x1920)", "Square (1080x1080)", "480p (854x480)", "Custom" };
    private static final int[] RATES = { 24, 25, 30, 48, 50, 60, 120 };
    private static final String[] RATE_NAMES = { "24", "25", "30", "48", "50", "60", "120" };

    private static boolean ffmpegProbed;
    private static volatile boolean ffmpegChecked;
    private static volatile String ffmpegVersion;
    private static int exportCount;

    private final Screen parent;
    private final Minecraft mc = Minecraft.getInstance();
    private final ExportSettings draft = new ExportSettings();
    private final ImString filename = new ImString(256);
    private final ImString ffmpegPath = new ImString(512);
    private final ImString modalName = new ImString(64);
    private String presetName;
    private ExportPreset menuTarget;
    private String modalMode;
    private String modalError;
    private String error;
    private int frameCountKey = Integer.MIN_VALUE;
    private int frameCount;
    private int dragging;

    public ExportScreen(Screen parent) {
        super("Export");
        this.parent = parent;
        ReplayHandler h = ReplayHandler.get();
        EditorState.ExportSettings s = h != null ? h.editor.export : new EditorState.ExportSettings();
        draft.width = s.width > 0 ? s.width : 1920;
        draft.height = s.height > 0 ? s.height : 1080;
        draft.fps = s.fps > 0 ? s.fps : 60;
        draft.crf = s.crf >= 0 ? s.crf : 18;
        draft.format = FFmpegEncoder.Format.parse(s.format != null ? s.format : "MP4");
        draft.encoder = VideoEncoders.Choice.parse(s.encoder != null ? s.encoder : "AUTO");
        draft.preset = s.x264Preset != null ? s.x264Preset : "medium";
        draft.mp4Layout = FFmpegEncoder.Mp4Layout.parse(s.mp4Layout != null ? s.mp4Layout : "STANDARD");
        draft.pngCompression = s.pngCompression >= -1 ? s.pngCompression : Config.get().exportPngCompression;
        draft.extraFrames = Math.max(0, s.extraFrames);
        draft.waitForChunks = s.waitForChunks == null || s.waitForChunks;
        draft.useSpeedTrack = s.useSpeedTrack;
        draft.hideNametags = s.hideNametags;
        draft.warmupFrames = Config.get().exportWarmupFrames;
        filename.set(s.filename != null ? s.filename : Config.get().exportFilename);
        ffmpegPath.set(Config.get().ffmpegPath);
        presetName = ExportPresets.find(s.preset) != null ? s.preset : null;
        if (h != null) {
            draft.startTick = h.editor.inPoint >= 0 ? h.editor.inPoint : 0;
            draft.endTick = h.editor.outPoint >= 0 ? h.editor.outPoint : h.getTotalTicks() - 1;
        }
        probeFfmpeg(false);
    }

    private static void probeFfmpeg(boolean again) {
        if (ffmpegProbed && !again) return;
        ffmpegProbed = true;
        ffmpegChecked = false;
        String path = Config.get().ffmpegPath;
        Thread t = new Thread(() -> {
            ffmpegVersion = FFmpegEncoder.probe(path);
            ffmpegChecked = true;
            if (ffmpegVersion != null) VideoEncoders.detect(path);
        }, "Flashback ffmpeg probe");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onClose() {
        remember();
        mc.setScreen(parent);
    }

    @Override
    protected boolean worldKey(int key, int scancode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            render();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            ReplayHandler h = ReplayHandler.get();
            if (h != null) h.setPaused(!h.isPaused());
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void drawUi(float partialTicks) {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) {
            mc.setScreen(null);
            return;
        }
        float w = mc.getWindow().getWidth(), hh = mc.getWindow().getHeight();
        float leftW = Math.min(420, w * 0.3f), rightW = Math.min(320, w * 0.24f), bottomH = 110;
        drawViewerFrame(leftW, rightW, bottomH, w, hh);

        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(leftW, hh - bottomH, ImGuiCond.Always);
        ImGui.begin("Render Settings", ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse);
        drawPresets();
        ImGui.separator();
        if (ImGui.beginTabBar("tabs")) {
            if (ImGui.beginTabItem("Video")) {
                drawVideoTab();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("File")) {
                drawFileTab(h);
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Advanced")) {
                drawAdvancedTab();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
        drawModal();
        ImGui.end();

        ImGui.setNextWindowPos(w - rightW, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(rightW, hh - bottomH, ImGuiCond.Always);
        ImGui.begin("Output", ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse);
        drawOutput(h);
        ImGui.end();

        ImGui.setNextWindowPos(0, hh - bottomH, ImGuiCond.Always);
        ImGui.setNextWindowSize(w, bottomH, ImGuiCond.Always);
        ImGui.begin("Export Range", ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoCollapse
            | ImGuiWindowFlags.NoScrollbar);
        drawRange(h);
        ImGui.end();
    }

    /** Dims everything of the viewer outside the frame the export will have. */
    private void drawViewerFrame(float left, float right, float bottom, float w, float hh) {
        float vx = left, vy = 0, vw = w - left - right, vh = hh - bottom;
        if (vw <= 0 || vh <= 0) return;
        double aspect = draft.width / (double) Math.max(1, draft.height);
        float fw = vw, fh = (float) (vw / aspect);
        if (fh > vh) {
            fh = vh;
            fw = (float) (vh * aspect);
        }
        // the game fills the whole window: the frame is what the export's aspect keeps of the window's center
        double windowAspect = w / Math.max(1.0, hh);
        float cw = w, ch = hh;
        if (aspect > windowAspect) ch = (float) (w / aspect);
        else cw = (float) (hh * aspect);
        float cx = (w - cw) / 2, cy = (hh - ch) / 2;
        ImDrawList dl = ImGui.getBackgroundDrawList();
        int shade = 0xB0000000;
        dl.addRectFilled(0, 0, w, cy, shade);
        dl.addRectFilled(0, cy + ch, w, hh, shade);
        dl.addRectFilled(0, cy, cx, cy + ch, shade);
        dl.addRectFilled(cx + cw, cy, w, cy + ch, shade);
        dl.addRect(cx, cy, cx + cw, cy + ch, 0xC0FFFFFF, 0, 0, 1);
    }

    // ------------------------------------------------------------------ presets

    private void drawPresets() {
        ExportPreset current = presetName == null ? null : ExportPresets.find(presetName);
        String label = current == null ? "Custom settings" : current.name + (current.matches(draft) ? "" : " (modified)");
        ImGui.text("Preset: " + label);
        List<ExportPreset> all = ExportPresets.all();
        float tileW = (ImGui.getContentRegionAvailX() - 8) / 2;
        for (int i = 0; i < all.size(); i++) {
            ExportPreset p = all.get(i);
            ImGui.pushID("preset" + i);
            boolean chosen = p.name.equals(presetName);
            if (chosen) ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.24f, 0.42f, 0.74f, 1f);
            if (ImGui.button(p.name + "\n" + p.summary(), tileW, 44)) {
                p.applyTo(draft);
                presetName = p.name;
            }
            if (chosen) ImGui.popStyleColor();
            if (ImGui.isItemClicked(ImGuiMouseButton.Right)) {
                menuTarget = p;
                ImGui.openPopup("presetMenu");
            }
            if (ImGui.beginPopup("presetMenu")) {
                if (menuTarget != null) {
                    ImGui.textDisabled(menuTarget.name);
                    if (ImGui.menuItem("Use")) {
                        menuTarget.applyTo(draft);
                        presetName = menuTarget.name;
                    }
                    if (!menuTarget.builtIn && ImGui.menuItem("Update to current settings")) {
                        ExportPreset updated = ExportPreset.of(menuTarget.name, draft);
                        ExportPresets.put(updated);
                        presetName = updated.name;
                    }
                    if (!menuTarget.builtIn && ImGui.menuItem("Rename...")) openModal("rename", menuTarget.name);
                    if (ImGui.menuItem("Duplicate")) {
                        ExportPreset copy = ExportPreset.of(ExportPresets.freeName(menuTarget.name + " copy"), new ExportSettings());
                        ExportSettings tmp = new ExportSettings();
                        menuTarget.applyTo(tmp);
                        copy = ExportPreset.of(copy.name, tmp);
                        ExportPresets.put(copy);
                    }
                    if (!menuTarget.builtIn && ImGui.menuItem("Delete")) {
                        if (menuTarget.name.equals(presetName)) presetName = null;
                        ExportPresets.remove(menuTarget);
                    }
                }
                ImGui.endPopup();
            }
            ImGui.popID();
            if (i % 2 == 0 && i + 1 < all.size()) ImGui.sameLine();
        }
        if (ImGui.button("Save as preset...")) openModal("save", ExportPresets.freeName("My preset"));
    }

    private void openModal(String mode, String name) {
        modalMode = mode;
        modalName.set(name);
        modalError = null;
        ImGui.openPopup("presetName");
    }

    private void drawModal() {
        if (modalMode != null && !ImGui.isPopupOpen("presetName")) ImGui.openPopup("presetName");
        if (ImGui.beginPopupModal("presetName", ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text(modalMode.equals("save") ? "Save the current settings as a preset" : "Rename preset");
            ImGui.inputText("Name", modalName);
            if (modalError != null) ImGui.textColored(1, 0.4f, 0.4f, 1, modalError);
            if (ImGui.button("OK")) {
                String name = modalName.get().trim();
                ExportPreset except = modalMode.equals("rename") ? menuTarget : null;
                if (name.isEmpty()) {
                    modalError = "Enter a name";
                } else if (!ExportPresets.nameFree(name, except)) {
                    modalError = "A preset with this name exists";
                } else {
                    if (modalMode.equals("save")) {
                        ExportPresets.put(ExportPreset.of(name, draft));
                        presetName = name;
                    } else {
                        boolean wasChosen = menuTarget.name.equals(presetName);
                        ExportPresets.rename(menuTarget, name);
                        if (wasChosen) presetName = name;
                    }
                    modalMode = null;
                    ImGui.closeCurrentPopup();
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel")) {
                modalMode = null;
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    // ------------------------------------------------------------------ tabs

    private void drawVideoTab() {
        FFmpegEncoder.Format[] formats = FFmpegEncoder.Format.values();
        String[] formatNames = new String[formats.length];
        for (int i = 0; i < formats.length; i++) formatNames[i] = formats[i].displayName;
        ImInt format = new ImInt(draft.format.ordinal());
        if (ImGui.combo("Format", format, formatNames)) draft.format = formats[format.get()];

        if (usesEncoder()) {
            ImInt enc = new ImInt(draft.encoder.ordinal());
            if (ImGui.combo("Encoder", enc, encoderNames())) draft.encoder = VideoEncoders.Choice.values()[enc.get()];
        }

        int sizeIndex = SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            if (SIZES[i][0] == draft.width && SIZES[i][1] == draft.height) sizeIndex = i;
        }
        ImInt size = new ImInt(sizeIndex);
        if (ImGui.combo("Resolution", size, SIZE_NAMES) && size.get() < SIZES.length) {
            draft.width = SIZES[size.get()][0];
            draft.height = SIZES[size.get()][1];
        }
        int[] wh = { draft.width, draft.height };
        if (ImGui.inputInt2("Width x height", wh)) {
            draft.width = Mth.clamp(wh[0], 16, 7680);
            draft.height = Mth.clamp(wh[1], 16, 4320);
        }

        int rateIndex = -1;
        for (int i = 0; i < RATES.length; i++) if (RATES[i] == draft.fps) rateIndex = i;
        ImInt rate = new ImInt(Math.max(0, rateIndex));
        if (ImGui.combo("Frame rate", rate, RATE_NAMES)) draft.fps = RATES[rate.get()];

        if (draft.format != FFmpegEncoder.Format.PNG && draft.format != FFmpegEncoder.Format.GIF
            && draft.format != FFmpegEncoder.Format.MOV_PRORES) {
            int[] crf = { draft.crf };
            if (ImGui.sliderInt("Quality (CRF)", crf, 0, 40)) draft.crf = crf[0];
            ImGui.textDisabled(qualityName(draft.crf) + "  (lower is better)");
        }
        if (usesEncoder() && resolvedEncoder() == VideoEncoders.Choice.LIBX264) {
            ImInt preset = new ImInt(Math.max(0, Arrays.asList(X264_PRESETS).indexOf(draft.preset)));
            if (ImGui.combo("x264 preset", preset, X264_PRESETS)) draft.preset = X264_PRESETS[preset.get()];
        }
    }

    private void drawFileTab(ReplayHandler h) {
        ImGui.inputTextWithHint("File name", "%replay%-%date%-%time%", filename);
        String ext = draft.format == FFmpegEncoder.Format.PNG ? "/" : "." + draft.format.extension;
        ImGui.textDisabled(expandName(filename.get(), h, false) + ext);
        ImGui.textDisabled("%replay% %date% %time% %seq%");
        ImGui.separator();
        int last = Math.max(0, h.getTotalTicks() - 1);
        int[] range = { draft.startTick, draft.endTick };
        if (ImGui.dragInt2("Start / end tick", range, 1, 0, last)) {
            draft.startTick = Mth.clamp(range[0], 0, last);
            draft.endTick = Mth.clamp(range[1], 0, last);
        }
        ImGui.textDisabled(ReplayMeta.formatTicks(draft.startTick) + " - " + ReplayMeta.formatTicks(draft.endTick));
        if (ImGui.button("Use in/out points")) {
            draft.startTick = h.editor.inPoint >= 0 ? h.editor.inPoint : 0;
            draft.endTick = h.editor.outPoint >= 0 ? h.editor.outPoint : last;
        }
        ImGui.sameLine();
        if (ImGui.button("Whole replay")) {
            draft.startTick = 0;
            draft.endTick = last;
        }
        ImBoolean speed = new ImBoolean(draft.useSpeedTrack);
        if (ImGui.checkbox("Use speed keyframes", speed)) draft.useSpeedTrack = speed.get();
        ImBoolean names = new ImBoolean(draft.hideNametags);
        if (ImGui.checkbox("Hide nametags", names)) draft.hideNametags = names.get();
        if (ImGui.button("Open exports folder")) net.minecraft.Util.getPlatform().openFile(exportsDir());
    }

    private void drawAdvancedTab() {
        if (draft.format == FFmpegEncoder.Format.MP4) {
            FFmpegEncoder.Mp4Layout[] layouts = FFmpegEncoder.Mp4Layout.values();
            String[] names = new String[layouts.length];
            for (int i = 0; i < layouts.length; i++) names[i] = layouts[i].displayName;
            ImInt layout = new ImInt(draft.mp4Layout.ordinal());
            if (ImGui.combo("MP4 index", layout, names)) draft.mp4Layout = layouts[layout.get()];
            ImGui.textWrapped(switch (draft.mp4Layout) {
                case STANDARD -> "Index at the end: read by every player and editor, nothing extra when the export ends.";
                case FRAGMENTED -> "Finishes at once and stays playable if the game crashes, but some older editors read it badly.";
                case FASTSTART -> "Index at the start, for web playback; ffmpeg rewrites the whole file once at the end.";
            });
        }
        if (draft.format == FFmpegEncoder.Format.PNG) {
            int[] level = { Math.max(0, draft.pngCompression) };
            if (ImGui.sliderInt("PNG compression", level, 0, 9)) draft.pngCompression = level[0];
        }
        int[] extra = { draft.extraFrames };
        if (ImGui.sliderInt("Extra frames (shaders)", extra, 0, 8)) draft.extraFrames = extra[0];
        ImBoolean wait = new ImBoolean(draft.waitForChunks);
        if (ImGui.checkbox("Wait for chunks", wait)) draft.waitForChunks = wait.get();
        ImGui.separator();
        ImGui.inputText("ffmpeg", ffmpegPath);
        if (ImGui.button("Check ffmpeg")) {
            Config.get().ffmpegPath = ffmpegPath.get().trim().isEmpty() ? "ffmpeg" : ffmpegPath.get().trim();
            Config.save();
            probeFfmpeg(true);
        }
        ImGui.textDisabled(!ffmpegChecked ? "Checking ffmpeg..." : ffmpegVersion == null ? "ffmpeg not found"
            : "ffmpeg " + ffmpegVersion);
        ImGui.separator();
        if (ImGui.button("Reset estimates")) ExportEstimator.reset();
        ImGui.textDisabled("Forgets the speeds and sizes learned from your exports.");
    }

    // ------------------------------------------------------------------ output

    private void drawOutput(ReplayHandler h) {
        ExportEstimator.Estimate e = estimate(h);
        ImGui.text("Estimated size");
        ImGui.textColored(0.6f, 0.8f, 1f, 1f, ExportEstimator.formatBytes(e.bytes));
        ImGui.textDisabled("likely " + ExportEstimator.formatBytes(e.bytesLow) + " - " + ExportEstimator.formatBytes(e.bytesHigh));
        ImGui.spacing();
        ImGui.text("Estimated export time");
        ImGui.textColored(0.6f, 0.8f, 1f, 1f, ExportEstimator.formatSeconds(e.seconds));
        ImGui.textDisabled(e.calibrated ? "learned from " + e.samples + " of your exports" : "from reference measurements");
        ImGui.separator();
        row("Length", ExportEstimator.formatSeconds(e.videoSeconds));
        row("Frames", String.valueOf(e.frames));
        row("Resolution", draft.width + " x " + draft.height + " @ " + draft.fps);
        row("Encoder", e.encoder == null ? codecName() : e.encoder);
        if (e.videoSeconds > 0) row("Bitrate", String.format("%.1f Mbit/s", e.bytes * 8 / e.videoSeconds / 1e6));
        ImGui.separator();
        if (error != null) ImGui.textColored(1, 0.45f, 0.45f, 1, error);
        if (draft.format != FFmpegEncoder.Format.PNG && ffmpegChecked && ffmpegVersion == null) {
            ImGui.textColored(1, 0.7f, 0.3f, 1, "ffmpeg not found: install it or export PNG");
        }
        if (ImGui.button("Render  (Ctrl+Enter)", ImGui.getContentRegionAvailX(), 40)) render();
        if (ImGui.button("Back", ImGui.getContentRegionAvailX(), 0)) onClose();
    }

    private void row(String key, String value) {
        ImGui.text(key);
        ImGui.sameLine(ImGui.getContentRegionAvailX() - ImGui.calcTextSize(value).x + ImGui.getCursorPosX() - 4);
        ImGui.textDisabled(value);
    }

    private void drawRange(ReplayHandler h) {
        int total = Math.max(1, h.getTotalTicks() - 1);
        if (ImGui.button(h.isPaused() ? "Play" : "Pause")) h.setPaused(!h.isPaused());
        ImGui.sameLine();
        ImGui.text(ReplayMeta.formatTicks(h.getCurrentTick()) + " / " + ReplayMeta.formatTicks(total) + "     In "
            + ReplayMeta.formatTicks(draft.startTick) + "   Out " + ReplayMeta.formatTicks(draft.endTick) + "   Duration "
            + ReplayMeta.formatTicks(Math.max(0, draft.endTick - draft.startTick)));
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY() + 4;
        float bw = ImGui.getContentRegionAvailX(), bh = 26;
        ImGui.setCursorScreenPos(x0, y0);
        ImGui.invisibleButton("##range", bw, bh);
        boolean hovered = ImGui.isItemHovered();
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x0 + bw, y0 + bh, 0xFF1B1616);
        float sx = x0 + bw * draft.startTick / total, ex = x0 + bw * draft.endTick / total;
        dl.addRectFilled(sx, y0 + 2, ex, y0 + bh - 2, 0x80E08F4F);
        dl.addRectFilled(sx - 3, y0, sx + 3, y0 + bh, 0xFF40C0E0);
        dl.addRectFilled(ex - 3, y0, ex + 3, y0 + bh, 0xFF40C0E0);
        float px = x0 + (float) (bw * h.getReplayTime() / total);
        dl.addLine(px, y0, px, y0 + bh, 0xFF4040E0, 2);
        float mx = ImGui.getMousePosX();
        int tick = Mth.clamp((int) Math.round((mx - x0) / bw * total), 0, total);
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (Math.abs(mx - sx) < 6) dragging = 1;
            else if (Math.abs(mx - ex) < 6) dragging = 2;
            else dragging = 3;
        }
        if (dragging != 0) {
            if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                dragging = 0;
            } else if (dragging == 1) {
                draft.startTick = Math.min(tick, draft.endTick - 1);
            } else if (dragging == 2) {
                draft.endTick = Math.max(tick, draft.startTick + 1);
            } else if (tick != h.getCurrentTick()) {
                h.seek(tick);
            }
        }
    }

    // ------------------------------------------------------------------ render

    private ExportEstimator.Estimate estimate(ReplayHandler h) {
        ExportSettings s = rangeChecked(h);
        int key = s.startTick * 31 + s.endTick * 17 + s.fps * 7 + (s.useSpeedTrack ? 1 : 0);
        if (key != frameCountKey) {
            frameCountKey = key;
            frameCount = s.endTick > s.startTick ? Exporter.countFrames(h, s) : 0;
        }
        VideoEncoders.Choice c = usesEncoder() ? resolvedEncoder() : null;
        return ExportEstimator.estimate(s, c, frameCount);
    }

    private ExportSettings rangeChecked(ReplayHandler h) {
        int last = Math.max(0, h.getTotalTicks() - 1);
        draft.startTick = Mth.clamp(draft.startTick, 0, last);
        draft.endTick = Mth.clamp(draft.endTick, 0, last);
        return draft;
    }

    private void render() {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) return;
        error = null;
        ExportSettings s = copy(rangeChecked(h));
        s.width = Math.max(16, s.width & ~1);
        s.height = Math.max(16, s.height & ~1);
        s.ffmpegPath = Config.get().ffmpegPath;
        s.asyncReadback = Config.get().exportAsyncReadback;
        if (s.endTick <= s.startTick) {
            error = "The end must be after the start";
            return;
        }
        if (s.format != FFmpegEncoder.Format.PNG && ffmpegChecked && ffmpegVersion == null) {
            error = "ffmpeg not found. Install it, set its path in Advanced, or export PNG.";
            return;
        }
        String name = expandName(filename.get(), h, true);
        s.output = s.format == FFmpegEncoder.Format.PNG ? RecordingManager.uniqueFile(exportsDir(), name, "")
            : RecordingManager.uniqueFile(exportsDir(), name, "." + s.format.extension);
        remember();
        try {
            Exporter.start(h, s);
        } catch (Throwable t) {
            Flashback120.LOG.error("Unable to start export", t);
            error = String.valueOf(t.getMessage());
            mc.setScreen(this);
        }
    }

    private static ExportSettings copy(ExportSettings d) {
        ExportSettings s = new ExportSettings();
        ExportPreset.of("", d).applyTo(s);
        s.startTick = d.startTick;
        s.endTick = d.endTick;
        s.useSpeedTrack = d.useSpeedTrack;
        s.warmupFrames = d.warmupFrames;
        return s;
    }

    /** Keeps the settings with the replay. */
    private void remember() {
        ReplayHandler h = ReplayHandler.get();
        if (h == null) return;
        EditorState.ExportSettings saved = h.editor.export;
        saved.width = draft.width;
        saved.height = draft.height;
        saved.fps = draft.fps;
        saved.crf = draft.crf;
        saved.format = draft.format.name();
        saved.filename = filename.get();
        saved.useSpeedTrack = draft.useSpeedTrack;
        saved.hideNametags = draft.hideNametags;
        saved.encoder = draft.encoder.name();
        saved.x264Preset = draft.preset;
        saved.mp4Layout = draft.mp4Layout.name();
        saved.pngCompression = draft.pngCompression;
        saved.extraFrames = draft.extraFrames;
        saved.waitForChunks = draft.waitForChunks;
        saved.preset = presetName;
        h.markEditorDirty();
    }

    public static File exportsDir() {
        File f = new File(RecordingManager.rootDir(), "exports");
        f.mkdirs();
        return f;
    }

    // ------------------------------------------------------------------ helpers

    private boolean usesEncoder() {
        return draft.format == FFmpegEncoder.Format.MP4 || draft.format == FFmpegEncoder.Format.MKV;
    }

    /** The encoder an MP4 or MKV export would use now, Auto worked out once the check has run. */
    private VideoEncoders.Choice resolvedEncoder() {
        VideoEncoders.Choice c = draft.encoder;
        String path = Config.get().ffmpegPath;
        if (!VideoEncoders.detected(path)) return c == VideoEncoders.Choice.AUTO ? VideoEncoders.Choice.LIBX264 : c;
        if (c == VideoEncoders.Choice.AUTO) return VideoEncoders.auto(path);
        if (c != VideoEncoders.Choice.LIBX264 && !VideoEncoders.detect(path).contains(c)) return VideoEncoders.Choice.LIBX264;
        return c;
    }

    private String[] encoderNames() {
        String path = Config.get().ffmpegPath;
        boolean known = VideoEncoders.detected(path);
        List<String> names = new ArrayList<>();
        for (VideoEncoders.Choice c : VideoEncoders.Choice.values()) {
            String n = c.displayName;
            if (c == VideoEncoders.Choice.AUTO) n = known ? "Auto (" + VideoEncoders.auto(path).displayName + ")" : "Auto (checking...)";
            else if (c == VideoEncoders.Choice.LIBX264) n = "x264 (CPU)";
            else if (known && !VideoEncoders.detect(path).contains(c)) n += " (not found)";
            names.add(n);
        }
        return names.toArray(new String[0]);
    }

    private String codecName() {
        return switch (draft.format) {
            case WEBM -> "VP9 (libvpx)";
            case MOV_PRORES -> "ProRes 422 HQ";
            case GIF -> "GIF";
            case PNG -> "PNG, built in";
            default -> "H.264";
        };
    }

    private static String qualityName(int crf) {
        if (crf <= 14) return "Near lossless, very large";
        if (crf <= 19) return "High: looks like the game";
        if (crf <= 23) return "Good";
        if (crf <= 28) return "Medium: visible in motion";
        return "Low";
    }

    private static String expandName(String pattern, ReplayHandler h, boolean count) {
        Date now = new Date();
        if (count) exportCount++;
        String p = pattern == null || pattern.trim().isEmpty() ? "%replay%-%date%-%time%" : pattern;
        String s = p.replace("%date%", new SimpleDateFormat("yyyy-MM-dd").format(now))
            .replace("%time%", new SimpleDateFormat("HH_mm_ss").format(now))
            .replace("%replay%", h.meta.name)
            .replace("%seq%", String.valueOf(exportCount + (count ? 0 : 1)));
        return RecordingManager.sanitize(s);
    }
}
