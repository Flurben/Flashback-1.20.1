package com.flurben.flashback120.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.lwjgl.glfw.GLFW;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.editor.CameraRig;
import com.flurben.flashback120.editor.EditorState;
import com.flurben.flashback120.editor.Interpolation;
import com.flurben.flashback120.editor.Keyframe;
import com.flurben.flashback120.editor.KeyframeTrack;
import com.flurben.flashback120.editor.TrackType;
import com.flurben.flashback120.editor.Visuals;
import com.flurben.flashback120.format.ReplayMeta;
import com.flurben.flashback120.gui.imgui.ImGuiLayer;
import com.flurben.flashback120.gui.imgui.ImGuiScreen;
import com.flurben.flashback120.replay.CameraEntity;
import com.flurben.flashback120.replay.ReplayHandler;
import com.flurben.flashback120.util.Notifications;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

/**
 * The replay editor: ImGui windows over the replay world with a menu bar, a timeline with keyframe tracks and side
 * panels. Hold the right mouse button over the world to look around and fly with the movement keys.
 */
public class ReplayEditorScreen extends ImGuiScreen {

    private static final class Selection {

        final KeyframeTrack track;
        final Keyframe keyframe;
        int originalTick;

        Selection(KeyframeTrack track, Keyframe keyframe) {
            this.track = track;
            this.keyframe = keyframe;
        }
    }

    private static final List<Object[]> clipboard = new ArrayList<>();
    private static boolean hideUi;
    private static boolean showTimeline = true, showVisuals = true, showPlayers = true, showInspector = true,
        showPresets = false, showMovement = false, showHelp = false;
    private static boolean previewPresets = true;

    private final Minecraft mc = Minecraft.getInstance();
    private final List<Selection> selection = new ArrayList<>();
    private CameraRig selectedRig;
    private boolean grabbed;
    private double lastMouseX, lastMouseY;
    private boolean draggingKeys;
    private int dragStartTick;
    private boolean draggingPlayhead;
    private boolean scrubbing;

    public ReplayEditorScreen() {
        super("Replay Editor");
    }

    private static ReplayHandler handler() {
        return ReplayHandler.get();
    }

    /** True while the right mouse button holds the camera, so movement keys fly it. */
    public boolean isCameraGrabbed() {
        return grabbed;
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void drawUi(float partialTicks) {
        ReplayHandler h = handler();
        if (h == null) {
            mc.setScreen(null);
            return;
        }
        updateGrab(h);
        drawGuides(h);
        if (!hideUi) {
            drawMenuBar(h);
            if (showTimeline) drawTimeline(h);
            if (showVisuals) drawVisuals(h);
            if (showPlayers) drawPlayers(h);
            if (showInspector) drawInspector(h);
            if (showPresets) drawPresets(h);
            if (showMovement) drawMovement(h);
            if (showHelp) drawHelp();
        }
        h.setRigPreview(previewPresets && showPresets);
        // a change is one undo step once no drag or typing goes on
        if (!ImGui.isAnyItemActive() && !ImGui.isMouseDown(ImGuiMouseButton.Left) && !draggingKeys) {
            h.history.settle(h.editor);
        }
    }

    private void updateGrab(ReplayHandler h) {
        if (!grabbed) return;
        long window = mc.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS) {
            releaseGrab();
            return;
        }
        double x = mc.mouseHandler.xpos(), y = mc.mouseHandler.ypos();
        double dx = x - lastMouseX, dy = y - lastMouseY;
        lastMouseX = x;
        lastMouseY = y;
        CameraEntity camera = h.getCamera();
        if (camera == null || h.isSpectating() || (dx == 0 && dy == 0)) return;
        double s = mc.options.sensitivity().get() * 0.6 + 0.2;
        double f = s * s * s * 8.0 * 0.15;
        float yaw = camera.getYRot() + (float) (dx * f);
        float pitch = Mth.clamp(camera.getXRot() + (float) (dy * f * (mc.options.invertYMouse().get() ? -1 : 1)), -90, 90);
        camera.setCameraPosition(camera.eyeX(), camera.eyeY(), camera.eyeZ(), yaw, pitch);
    }

    private void startGrab() {
        grabbed = true;
        lastMouseX = mc.mouseHandler.xpos();
        lastMouseY = mc.mouseHandler.ypos();
        GLFW.glfwSetInputMode(mc.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    private void releaseGrab() {
        if (!grabbed) return;
        grabbed = false;
        long window = mc.getWindow().getWindow();
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        GLFW.glfwSetCursorPos(window, mc.getWindow().getScreenWidth() / 2.0, mc.getWindow().getScreenHeight() / 2.0);
    }

    @Override
    public void removed() {
        releaseGrab();
        super.removed();
    }

    @Override
    protected boolean worldMouse(int button, boolean down) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && down) {
            ReplayHandler h = handler();
            if (h != null && h.isSpectating()) h.stopSpectating();
            startGrab();
            return true;
        }
        return false;
    }

    @Override
    protected boolean worldScroll(double amount) {
        ReplayHandler h = handler();
        if (h == null) return false;
        h.cameraController.adjustSpeed(amount);
        return true;
    }

    @Override
    public void onClose() {
        mc.setScreen(new ReplayMenuScreen(this));
    }

    @Override
    protected boolean worldKey(int key, int scancode, int modifiers) {
        ReplayHandler h = handler();
        if (h == null) return false;
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (hideUi) {
                hideUi = false;
                return true;
            }
            onClose();
            return true;
        }
        if (grabbed) return true; // movement keys
        switch (key) {
            case GLFW.GLFW_KEY_F1 -> hideUi = !hideUi;
            case GLFW.GLFW_KEY_SPACE -> h.setPaused(!h.isPaused());
            case GLFW.GLFW_KEY_LEFT -> h.seekRelative(shift ? -20 : -1);
            case GLFW.GLFW_KEY_RIGHT -> h.seekRelative(shift ? 20 : 1);
            case GLFW.GLFW_KEY_UP -> jumpKeyframe(true);
            case GLFW.GLFW_KEY_DOWN -> jumpKeyframe(false);
            case GLFW.GLFW_KEY_I -> setInPoint(h);
            case GLFW.GLFW_KEY_O -> setOutPoint(h);
            case GLFW.GLFW_KEY_K -> addKeyframe(TrackType.CAMERA);
            case GLFW.GLFW_KEY_M -> addMarker();
            case GLFW.GLFW_KEY_DELETE, GLFW.GLFW_KEY_BACKSPACE -> deleteSelection();
            case GLFW.GLFW_KEY_Z -> {
                if (ctrl) {
                    if (shift) redo();
                    else undo();
                }
            }
            case GLFW.GLFW_KEY_Y -> {
                if (ctrl) redo();
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl) copySelection();
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) paste();
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) selectAll();
            }
            case GLFW.GLFW_KEY_E -> {
                if (ctrl) openExport();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ menu bar

    private void drawMenuBar(ReplayHandler h) {
        if (!ImGui.beginMainMenuBar()) return;
        if (ImGui.beginMenu("File")) {
            if (ImGui.menuItem("Export Video...", "Ctrl+E")) openExport();
            if (ImGui.menuItem("Save Editor State")) {
                h.saveEditorAsync();
                Notifications.show("Flashback", "Saved");
            }
            ImGui.separator();
            if (ImGui.menuItem("Exit Replay")) h.exit();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Edit")) {
            if (ImGui.menuItem("Undo", "Ctrl+Z", false, h.history.canUndo())) undo();
            if (ImGui.menuItem("Redo", "Ctrl+Y", false, h.history.canRedo())) redo();
            ImGui.separator();
            if (ImGui.menuItem("Copy Keyframes", "Ctrl+C", false, !selection.isEmpty())) copySelection();
            if (ImGui.menuItem("Paste Keyframes", "Ctrl+V", false, !clipboard.isEmpty())) paste();
            if (ImGui.menuItem("Select All Keyframes", "Ctrl+A")) selectAll();
            if (ImGui.menuItem("Delete Keyframes", "Del", false, !selection.isEmpty())) deleteSelection();
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Window")) {
            if (ImGui.menuItem("Timeline", "", showTimeline)) showTimeline = !showTimeline;
            if (ImGui.menuItem("Visuals", "", showVisuals)) showVisuals = !showVisuals;
            if (ImGui.menuItem("Player List", "", showPlayers)) showPlayers = !showPlayers;
            if (ImGui.menuItem("Keyframe Inspector", "", showInspector)) showInspector = !showInspector;
            if (ImGui.menuItem("Camera Presets", "", showPresets)) showPresets = !showPresets;
            if (ImGui.menuItem("Camera Movement", "", showMovement)) showMovement = !showMovement;
            ImGui.separator();
            if (ImGui.menuItem("Hide UI", "F1")) hideUi = true;
            ImGui.endMenu();
        }
        if (ImGui.beginMenu("Help")) {
            if (ImGui.menuItem("Controls", "", showHelp)) showHelp = !showHelp;
            ImGui.endMenu();
        }
        String status = (h.isPaused() ? "Paused" : "Playing") + "   " + ReplayMeta.formatTicks(h.getCurrentTick())
            + " / " + ReplayMeta.formatTicks(h.getTotalTicks()) + "   " + h.meta.name;
        ImGui.sameLine(ImGui.getWindowWidth() - ImGui.calcTextSize(status).x - 12);
        ImGui.textDisabled(status);
        ImGui.endMainMenuBar();
    }

    private void openExport() {
        releaseGrab();
        mc.setScreen(new ExportScreen(this));
    }

    // ------------------------------------------------------------------ timeline

    private static int col(int argb) {
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private void drawTimeline(ReplayHandler h) {
        float w = mc.getWindow().getWidth(), hh = mc.getWindow().getHeight();
        float height = Math.max(200, hh * 0.28f);
        ImGui.setNextWindowPos(0, hh - height, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(w, height, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Timeline", ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse)) {
            ImGui.end();
            return;
        }
        EditorState e = h.editor;
        // playback controls
        if (ImGui.button("|<")) h.seek(e.inPoint >= 0 ? e.inPoint : 0);
        ImGui.sameLine();
        if (ImGui.button("<<")) h.seekRelative(-20 * Config.get().skipSeconds);
        ImGui.sameLine();
        if (ImGui.button(h.isPaused() ? "  Play  " : " Pause ")) h.setPaused(!h.isPaused());
        ImGui.sameLine();
        if (ImGui.button(">>")) h.seekRelative(20 * Config.get().skipSeconds);
        ImGui.sameLine();
        if (ImGui.button(">|")) h.seek(e.outPoint >= 0 ? e.outPoint : h.getTotalTicks() - 1);
        ImGui.sameLine();
        ImGui.text(ReplayMeta.formatTicks(h.getCurrentTick()) + " / " + ReplayMeta.formatTicks(h.getTotalTicks())
            + "  (tick " + h.getCurrentTick() + ")");
        ImGui.sameLine();
        ImGui.setNextItemWidth(140);
        float[] speed = { h.getSpeed() };
        if (ImGui.sliderFloat("##speed", speed, 0.05f, 8f, "Speed %.2fx", imgui.flag.ImGuiSliderFlags.Logarithmic)) {
            h.setSpeed(speed[0]);
        }
        ImGui.sameLine();
        if (ImGui.button("In")) setInPoint(h);
        ImGui.sameLine();
        if (ImGui.button("Out")) setOutPoint(h);
        ImGui.sameLine();
        if (ImGui.button("Clear In/Out")) {
            e.inPoint = -1;
            e.outPoint = -1;
            h.markEditorDirty();
        }
        ImGui.sameLine();
        if (ImGui.button("Add Keyframe")) ImGui.openPopup("addKeyframe");
        if (ImGui.beginPopup("addKeyframe")) {
            for (TrackType t : TrackType.values()) {
                if (ImGui.menuItem(t.displayName)) addKeyframe(t);
            }
            ImGui.endPopup();
        }
        ImGui.sameLine();
        if (ImGui.button("Marker")) addMarker();
        ImGui.sameLine();
        boolean apply = h.isApplyKeyframes();
        if (ImGui.checkbox("Keyframes", apply)) h.setApplyKeyframes(!apply);

        drawTracks(h);
        ImGui.end();
    }

    private void drawTracks(ReplayHandler h) {
        EditorState e = h.editor;
        int total = Math.max(1, h.getTotalTicks() - 1);
        float labelW = 130, rulerH = 20, trackH = 20;
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        float availW = ImGui.getContentRegionAvailX(), availH = ImGui.getContentRegionAvailY();
        float tx0 = x0 + labelW, tw = Math.max(50, availW - labelW);
        if (e.zoomTicks <= 0 || e.zoomTicks > total) e.zoomTicks = total;
        e.zoomTicks = Math.max(20, Math.min(total, e.zoomTicks));
        e.scrollTick = Math.max(0, Math.min(total - e.zoomTicks, e.scrollTick));
        double view = e.zoomTicks, start = e.scrollTick;
        ImDrawList dl = ImGui.getWindowDrawList();

        // track labels and enable boxes
        List<KeyframeTrack> tracks = e.tracks;
        for (int i = 0; i < tracks.size(); i++) {
            KeyframeTrack t = tracks.get(i);
            float ry = y0 + rulerH + i * trackH;
            ImGui.setCursorScreenPos(x0, ry + 1);
            ImGui.pushID("track" + i);
            ImBoolean enabled = new ImBoolean(t.enabled);
            if (ImGui.checkbox("##en", enabled)) {
                t.enabled = enabled.get();
                h.markEditorDirty();
            }
            ImGui.sameLine();
            ImGui.textColored(((t.type.color >> 16) & 0xFF) / 255f, ((t.type.color >> 8) & 0xFF) / 255f,
                (t.type.color & 0xFF) / 255f, 1f, t.type.displayName);
            ImGui.popID();
        }

        // the canvas
        float canvasH = Math.max(rulerH + trackH, Math.min(availH, rulerH + tracks.size() * trackH + 10));
        ImGui.setCursorScreenPos(tx0, y0);
        ImGui.invisibleButton("##timeline", tw, canvasH);
        boolean hovered = ImGui.isItemHovered();
        boolean active = ImGui.isItemActive();
        float mx = ImGui.getMousePosX(), my = ImGui.getMousePosY();
        dl.addRectFilled(tx0, y0, tx0 + tw, y0 + canvasH, col(0xFF16161B));
        dl.addRectFilled(tx0, y0, tx0 + tw, y0 + rulerH, col(0xFF22222A));

        // in and out points
        if (e.inPoint >= 0 || e.outPoint >= 0) {
            int in = e.inPoint >= 0 ? e.inPoint : 0, out = e.outPoint >= 0 ? e.outPoint : total;
            float ax = tickX(in, start, view, tx0, tw), bx = tickX(out, start, view, tx0, tw);
            dl.addRectFilled(Math.max(tx0, ax), y0, Math.min(tx0 + tw, bx), y0 + canvasH, col(0x283A6EC8));
        }
        // ruler
        double step = niceStep(view / Math.max(1, tw / 70));
        for (double t = Math.floor(start / step) * step; t <= start + view; t += step) {
            float x = tickX(t, start, view, tx0, tw);
            if (x < tx0) continue;
            dl.addLine(x, y0 + rulerH - 6, x, y0 + rulerH, col(0xFF808090), 1);
            dl.addText(x + 2, y0 + 2, col(0xFFA0A0B0), ReplayMeta.formatTicks((int) t));
        }
        // track rows and keyframes
        for (int i = 0; i < tracks.size(); i++) {
            KeyframeTrack t = tracks.get(i);
            float ry = y0 + rulerH + i * trackH;
            dl.addLine(tx0, ry + trackH, tx0 + tw, ry + trackH, col(0xFF2A2A32), 1);
            if (t.type == TrackType.CAMERA) drawRigBars(h, dl, ry, trackH, start, view, tx0, tw);
            int color = t.enabled ? t.type.color : 0xFF606060;
            for (int k = 0; k < t.keyframes.size(); k++) {
                Keyframe kf = t.keyframes.get(k);
                float x = tickX(kf.tick, start, view, tx0, tw);
                if (k + 1 < t.keyframes.size()) {
                    float nx = tickX(t.keyframes.get(k + 1).tick, start, view, tx0, tw);
                    dl.addLine(x, ry + trackH / 2, nx, ry + trackH / 2, col((color & 0x00FFFFFF) | 0x80000000), 2);
                }
                if (x < tx0 - 8 || x > tx0 + tw + 8) continue;
                float r = 6;
                float cy = ry + trackH / 2;
                boolean sel = isSelected(kf);
                int c = sel ? 0xFFFFFFFF : color;
                if (kf.interpolation == Interpolation.HOLD) {
                    dl.addRectFilled(x - r + 1, cy - r + 1, x + r - 1, cy + r - 1, col(c));
                } else {
                    dl.addQuadFilled(x, cy - r, x + r, cy, x, cy + r, x - r, cy, col(c));
                }
                if (sel) dl.addQuad(x, cy - r - 2, x + r + 2, cy, x, cy + r + 2, x - r - 2, cy, col(0xFF4FA3E0), 2);
            }
        }
        // markers
        for (ReplayMeta.Marker m : h.getAllMarkers()) {
            float x = tickX(m.tick, start, view, tx0, tw);
            if (x < tx0 || x > tx0 + tw) continue;
            dl.addTriangleFilled(x - 5, y0, x + 5, y0, x, y0 + 8, col(m.color));
            dl.addLine(x, y0, x, y0 + canvasH, col((m.color & 0x00FFFFFF) | 0x60000000), 1);
            if (hovered && Math.abs(mx - x) < 5 && my < y0 + rulerH && m.description != null && !m.description.isEmpty()) {
                ImGui.setTooltip(m.description);
            }
        }
        // playhead
        float px = tickX(h.getReplayTime(), start, view, tx0, tw);
        if (px >= tx0 && px <= tx0 + tw) {
            dl.addLine(px, y0, px, y0 + canvasH, col(0xFFE04040), 2);
            dl.addTriangleFilled(px - 6, y0, px + 6, y0, px, y0 + 10, col(0xFFE04040));
        }

        // interaction
        double mouseTick = start + (mx - tx0) / tw * view;
        int mouseTickInt = (int) Math.round(Mth.clamp(mouseTick, 0, total));
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            Selection hit = keyframeAt(h, mx, my, y0 + rulerH, trackH, start, view, tx0, tw);
            boolean shift = ImGui.getIO().getKeyShift();
            if (my < y0 + rulerH || hit == null) {
                if (!shift) selection.clear();
                draggingPlayhead = true;
            } else {
                if (!isSelected(hit.keyframe)) {
                    if (!shift) selection.clear();
                    selection.add(hit);
                } else if (shift) {
                    selection.removeIf(s -> s.keyframe == hit.keyframe);
                }
                draggingKeys = true;
                dragStartTick = mouseTickInt;
                for (Selection s : selection) s.originalTick = s.keyframe.tick;
            }
        }
        if (draggingPlayhead) {
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                if (mouseTickInt != h.getCurrentTick()) {
                    h.seek(mouseTickInt);
                    scrubbing = true;
                }
            } else {
                draggingPlayhead = false;
                scrubbing = false;
            }
        }
        if (draggingKeys) {
            if (ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                int delta = mouseTickInt - dragStartTick;
                boolean moved = false;
                for (Selection s : selection) {
                    int nt = Mth.clamp(s.originalTick + delta, 0, total);
                    if (s.keyframe.tick != nt) {
                        s.keyframe.tick = nt;
                        moved = true;
                    }
                }
                if (moved) {
                    for (Selection s : selection) s.track.sort();
                    h.markEditorDirty();
                }
            } else {
                draggingKeys = false;
                // keyframes dropped on another keyframe of the same track replace it
                for (Selection s : selection) {
                    s.track.keyframes.removeIf(k -> k != s.keyframe && k.tick == s.keyframe.tick && !isSelected(k));
                }
            }
        }
        if (hovered && ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            Selection hit = keyframeAt(h, mx, my, y0 + rulerH, trackH, start, view, tx0, tw);
            if (hit != null) {
                if (!isSelected(hit.keyframe)) {
                    selection.clear();
                    selection.add(hit);
                }
                ImGui.openPopup("keyframeMenu");
            } else {
                ImGui.openPopup("timelineMenu");
            }
        }
        if (ImGui.beginPopup("keyframeMenu")) {
            ImGui.textDisabled(selection.size() + " keyframe(s)");
            if (ImGui.beginMenu("Interpolation")) {
                for (Interpolation in : Interpolation.values()) {
                    if (ImGui.menuItem(in.displayName)) {
                        for (Selection s : selection) s.keyframe.interpolation = in;
                        h.markEditorDirty();
                    }
                }
                ImGui.endMenu();
            }
            if (ImGui.menuItem("Jump To") && !selection.isEmpty()) h.seek(selection.get(0).keyframe.tick);
            if (ImGui.menuItem("Copy")) copySelection();
            if (ImGui.menuItem("Delete")) deleteSelection();
            ImGui.endPopup();
        }
        if (ImGui.beginPopup("timelineMenu")) {
            for (TrackType t : TrackType.values()) {
                if (ImGui.menuItem("Add " + t.displayName + " keyframe")) addKeyframe(t);
            }
            if (ImGui.menuItem("Paste", "Ctrl+V", false, !clipboard.isEmpty())) paste();
            ImGui.endPopup();
        }
        // zoom with ctrl+scroll, pan with scroll
        if (hovered) {
            float wheel = ImGui.getIO().getMouseWheel();
            if (wheel != 0) {
                if (ImGui.getIO().getKeyCtrl()) {
                    double factor = wheel > 0 ? 0.8 : 1.25;
                    double newView = Math.max(20, Math.min(total, view * factor));
                    e.scrollTick = mouseTick - (mouseTick - start) * newView / view;
                    e.zoomTicks = newView;
                } else {
                    e.scrollTick = start - wheel * view * 0.1;
                }
            }
        }
        if (!active && !draggingPlayhead && !h.isPaused()) {
            // keep the playhead in view while playing
            if (h.getReplayTime() > start + view) e.scrollTick = h.getReplayTime() - view * 0.1;
            else if (h.getReplayTime() < start) e.scrollTick = h.getReplayTime();
        }
    }

    private void drawRigBars(ReplayHandler h, ImDrawList dl, float ry, float trackH, double start, double view,
        float tx0, float tw) {
        for (CameraRig rig : h.editor.rigs) {
            float a = Math.max(tx0, tickX(rig.start, start, view, tx0, tw));
            float b = Math.min(tx0 + tw, tickX(rig.end, start, view, tx0, tw));
            if (b <= a) continue;
            int c = rig == selectedRig ? 0x90E0A040 : 0x6060A060;
            dl.addRectFilled(a, ry + 3, b, ry + trackH - 3, col(c), 3);
            dl.addText(a + 3, ry + 3, col(0xFFFFFFFF), rig.type.displayName + " " + rig.targetName);
        }
    }

    private Selection keyframeAt(ReplayHandler h, float mx, float my, float tracksY, float trackH, double start,
        double view, float tx0, float tw) {
        int row = (int) Math.floor((my - tracksY) / trackH);
        if (row < 0 || row >= h.editor.tracks.size()) return null;
        KeyframeTrack t = h.editor.tracks.get(row);
        Keyframe best = null;
        float bestD = 8;
        for (Keyframe k : t.keyframes) {
            float d = Math.abs(tickX(k.tick, start, view, tx0, tw) - mx);
            if (d <= bestD) {
                bestD = d;
                best = k;
            }
        }
        return best == null ? null : new Selection(t, best);
    }

    private static float tickX(double tick, double start, double view, float x0, float w) {
        return (float) (x0 + (tick - start) / view * w);
    }

    private static double niceStep(double raw) {
        double[] steps = { 1, 2, 5, 10, 20, 40, 100, 200, 400, 600, 1200, 2400, 6000, 12000, 24000, 72000 };
        for (double s : steps) if (s >= raw) return s;
        return 72000;
    }

    // ------------------------------------------------------------------ side windows

    private boolean cb(String label, boolean value) {
        ImBoolean b = new ImBoolean(value);
        ImGui.checkbox(label, b);
        return b.get();
    }

    private void drawVisuals(ReplayHandler h) {
        ImGui.setNextWindowPos(10, 40, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(280, 520, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Visuals")) {
            ImGui.end();
            return;
        }
        Visuals v = h.editor.visuals;
        String before = h.editor.historyJson();
        if (ImGui.collapsingHeader("GUI", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            v.showChat = cb("Chat", v.showChat);
            v.showScoreboard = cb("Scoreboard", v.showScoreboard);
            v.showPlayerList = cb("Player list (Tab)", v.showPlayerList);
            v.showBossBar = cb("Boss bar", v.showBossBar);
            v.showRecordedHotbar = cb("Recorded hotbar", v.showRecordedHotbar);
        }
        if (ImGui.collapsingHeader("World", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            v.renderEntities = cb("Entities", v.renderEntities);
            v.renderPlayers = cb("Players", v.renderPlayers);
            v.renderParticles = cb("Particles", v.renderParticles);
            v.renderSky = cb("Sky", v.renderSky);
            v.renderClouds = cb("Clouds", v.renderClouds);
            v.renderNametags = cb("Nametags", v.renderNametags);
        }
        if (ImGui.collapsingHeader("Overrides", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            v.overrideFov = cb("Override FOV", v.overrideFov);
            if (v.overrideFov) {
                float[] fov = { v.fov };
                if (ImGui.sliderFloat("FOV", fov, 1, 170, "%.0f")) v.fov = fov[0];
            }
            v.overrideTime = cb("Override time of day", v.overrideTime);
            if (v.overrideTime) {
                int[] time = { v.time };
                if (ImGui.sliderInt("Time", time, 0, 24000)) v.time = time[0];
            }
            ImInt weather = new ImInt(v.weather.ordinal());
            if (ImGui.combo("Weather", weather, new String[] { "Recorded", "Clear", "Rain", "Thunder" })) {
                v.weather = Visuals.Weather.values()[weather.get()];
            }
            v.nightVision = cb("Night vision", v.nightVision);
            float[] roll = { v.cameraRoll };
            if (ImGui.sliderFloat("Camera roll", roll, -180, 180, "%.1f")) v.cameraRoll = roll[0];
            v.cameraShake = cb("Camera shake", v.cameraShake);
            float[] freq = { v.shakeFrequency }, amp = { v.shakeAmplitude };
            if (ImGui.sliderFloat("Shake frequency", freq, 0.1f, 5f, "%.2f")) v.shakeFrequency = freq[0];
            if (ImGui.sliderFloat("Shake amplitude", amp, 0.1f, 10f, "%.2f")) v.shakeAmplitude = amp[0];
        }
        if (ImGui.collapsingHeader("Guides")) {
            v.ruleOfThirds = cb("Rule of thirds", v.ruleOfThirds);
            v.centerGuide = cb("Center", v.centerGuide);
            v.showCameraPath = cb("Camera path", v.showCameraPath);
        }
        if (!v.hiddenEntities.isEmpty() && ImGui.button("Show all hidden entities (" + v.hiddenEntities.size() + ")")) {
            v.hiddenEntities.clear();
        }
        if (!before.equals(h.editor.historyJson())) h.markEditorDirty();
        ImGui.end();
    }

    private void drawPlayers(ReplayHandler h) {
        ImGui.setNextWindowPos(10, 570, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(280, 260, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Player List")) {
            ImGui.end();
            return;
        }
        if (mc.level != null) {
            if (h.isSpectating() && ImGui.button("Stop spectating")) h.stopSpectating();
            int i = 0;
            for (AbstractClientPlayer p : mc.level.players()) {
                if (p instanceof CameraEntity) continue;
                entityRow(h, p, p.getGameProfile().getName() + (p.getId() == h.getLocalPlayerId() ? " (recorder)" : ""), i++);
            }
            if (ImGui.collapsingHeader("Nearby mobs")) {
                CameraEntity cam = h.getCamera();
                List<Entity> mobs = new ArrayList<>();
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof LivingEntity && !(e instanceof Player) && cam != null && e.distanceToSqr(cam) < 64 * 64) {
                        mobs.add(e);
                    }
                }
                mobs.sort((a, b) -> Double.compare(a.distanceToSqr(cam), b.distanceToSqr(cam)));
                for (int k = 0; k < Math.min(30, mobs.size()); k++) {
                    Entity e = mobs.get(k);
                    entityRow(h, e, e.getDisplayName().getString() + " #" + e.getId(), i++);
                }
            }
        }
        ImGui.end();
    }

    private void entityRow(ReplayHandler h, Entity e, String name, int i) {
        ImGui.pushID("ent" + i);
        boolean hidden = h.editor.visuals.hiddenEntities.contains(ReplayHandler.entityKey(e));
        if (hidden) ImGui.textDisabled(name);
        else ImGui.text(name);
        ImGui.sameLine(ImGui.getWindowWidth() - 190);
        if (ImGui.smallButton("Spectate")) h.spectate(e);
        ImGui.sameLine();
        if (ImGui.smallButton("Go to")) {
            CameraEntity cam = h.getCamera();
            if (cam != null) {
                h.stopSpectating();
                double yaw = Math.toRadians(e.getYRot());
                cam.setCameraPosition(e.getX() + Math.sin(yaw) * 3, e.getEyeY() + 1, e.getZ() - Math.cos(yaw) * 3,
                    e.getYRot(), 15);
            }
        }
        ImGui.sameLine();
        if (ImGui.smallButton(hidden ? "Show" : "Hide")) h.toggleHidden(e);
        ImGui.popID();
    }

    private void drawInspector(ReplayHandler h) {
        float w = mc.getWindow().getWidth();
        ImGui.setNextWindowPos(w - 330, 40, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(320, 460, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Keyframe Inspector")) {
            ImGui.end();
            return;
        }
        if (selection.isEmpty()) {
            ImGui.textDisabled("No keyframe selected.");
            ImGui.textDisabled("Click a keyframe on the timeline to edit it.");
            ImGui.end();
            return;
        }
        if (selection.size() > 1) {
            ImGui.text(selection.size() + " keyframes selected");
            ImInt interp = new ImInt(selection.get(0).keyframe.interpolation.ordinal());
            if (ImGui.combo("Interpolation", interp, interpolationNames())) {
                for (Selection s : selection) s.keyframe.interpolation = Interpolation.values()[interp.get()];
                h.markEditorDirty();
            }
            ImGui.end();
            return;
        }
        Selection s = selection.get(0);
        if (!s.track.keyframes.contains(s.keyframe)) {
            selection.clear();
            ImGui.end();
            return;
        }
        Keyframe k = s.keyframe;
        ImGui.text(s.track.type.displayName + " keyframe");
        ImInt tick = new ImInt(k.tick);
        if (ImGui.inputInt("Tick", tick)) {
            k.tick = Mth.clamp(tick.get(), 0, h.getTotalTicks() - 1);
            s.track.sort();
            h.markEditorDirty();
        }
        for (int c = 0; c < k.values.length && c < s.track.type.channels.length; c++) {
            float[] v = { (float) k.values[c] };
            String label = s.track.type.channels[c];
            boolean changed;
            if (s.track.type == TrackType.WEATHER) changed = ImGui.sliderFloat(label, v, 0, 1, "%.2f");
            else if (s.track.type == TrackType.TIME) changed = ImGui.sliderFloat(label, v, 0, 24000, "%.0f");
            else if (s.track.type == TrackType.FOV) changed = ImGui.sliderFloat(label, v, 1, 170, "%.1f");
            else changed = ImGui.dragFloat(label, v, 0.05f);
            if (changed) {
                k.values[c] = v[0];
                h.markEditorDirty();
            }
        }
        ImInt interp = new ImInt(k.interpolation.ordinal());
        if (ImGui.combo("Interpolation", interp, interpolationNames())) {
            k.interpolation = Interpolation.values()[interp.get()];
            h.markEditorDirty();
        }
        Keyframe prev = s.track.lastBefore(k.tick);
        boolean easeInApplies = prev != null && prev.interpolation == Interpolation.SMOOTH;
        boolean easeOutApplies = k.interpolation == Interpolation.SMOOTH && s.track.nextAfter(k.tick) != null;
        if (easeInApplies || easeOutApplies) {
            ImGui.separator();
            ImGui.textDisabled("Easing of Smooth movement (1/3 = plain spline)");
            if (easeInApplies) {
                float[] ei = { (float) k.easeIn };
                if (ImGui.sliderFloat("Ease in", ei, (float) Keyframe.MIN_EASE, 1f, "%.2f")) {
                    k.easeIn = ei[0];
                    h.markEditorDirty();
                }
            }
            if (easeOutApplies) {
                float[] eo = { (float) k.easeOut };
                if (ImGui.sliderFloat("Ease out", eo, (float) Keyframe.MIN_EASE, 1f, "%.2f")) {
                    k.easeOut = eo[0];
                    h.markEditorDirty();
                }
            }
            if (ImGui.smallButton("Reset easing")) {
                k.easeIn = Keyframe.DEFAULT_EASE;
                k.easeOut = Keyframe.DEFAULT_EASE;
                h.markEditorDirty();
            }
        }
        if (s.track.type == TrackType.CAMERA && ImGui.button("Set to current camera")) {
            CameraEntity cam = h.getCamera();
            if (cam != null) {
                k.values = new double[] { cam.eyeX(), cam.eyeY(), cam.eyeZ(), cam.getYRot(), cam.getXRot(),
                    h.editor.visuals.cameraRoll };
                h.markEditorDirty();
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Jump to")) h.seek(k.tick);
        ImGui.separator();
        drawSpeedGraph(s.track, k);
        ImGui.end();
    }

    /** The speed of the track's movement around the keyframe: for the camera its speed through the world. */
    private void drawSpeedGraph(KeyframeTrack track, Keyframe k) {
        Keyframe prev = track.lastBefore(k.tick), next = track.nextAfter(k.tick);
        int from = prev != null ? prev.tick : k.tick - 20, to = next != null ? next.tick : k.tick + 20;
        if (to <= from) return;
        int n = 120;
        float[] speeds = new float[n];
        float max = 0;
        double dt = (to - from) / (double) (n - 1);
        for (int i = 0; i < n; i++) {
            double t = from + i * dt;
            double[] a = track.evaluate(t - 0.05), b = track.evaluate(t + 0.05);
            if (a == null || b == null) continue;
            double d;
            if (track.type == TrackType.CAMERA) {
                double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
                d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            } else {
                d = Math.abs(b[0] - a[0]);
            }
            speeds[i] = (float) (d / 0.1 * (track.type == TrackType.CAMERA ? 20 : 1));
            max = Math.max(max, speeds[i]);
        }
        String unit = track.type == TrackType.CAMERA ? " blocks/s" : " per tick";
        ImGui.text("Speed around this keyframe");
        float gw = ImGui.getContentRegionAvailX(), gh = 90;
        float x0 = ImGui.getCursorScreenPosX(), y0 = ImGui.getCursorScreenPosY();
        ImGui.dummy(gw, gh);
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addRectFilled(x0, y0, x0 + gw, y0 + gh, col(0xFF16161B), 3);
        float scale = max <= 0 ? 1 : (gh - 8) / max;
        for (int i = 1; i < n; i++) {
            float ax = x0 + (i - 1) * gw / (n - 1), bx = x0 + i * gw / (n - 1);
            dl.addLine(ax, y0 + gh - 4 - speeds[i - 1] * scale, bx, y0 + gh - 4 - speeds[i] * scale, col(0xFF4FA3E0), 2);
        }
        float kx = x0 + (float) ((k.tick - from) / (double) (to - from)) * gw;
        dl.addLine(kx, y0, kx, y0 + gh, col(0xFFFFFFFF), 1);
        ImGui.textDisabled(String.format("Top speed %.2f%s", max, unit));
    }

    private static String[] interpolationNames() {
        Interpolation[] all = Interpolation.values();
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) names[i] = all[i].displayName;
        return names;
    }

    private void drawPresets(ReplayHandler h) {
        float w = mc.getWindow().getWidth();
        ImGui.setNextWindowPos(w - 330, 510, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(320, 360, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Camera Presets")) {
            ImGui.end();
            return;
        }
        String before = h.editor.historyJson();
        ImGui.textWrapped("Presets follow a player or mob while the replay plays. Spectate the target first, "
            + "or it uses the recording player.");
        ImBoolean preview = new ImBoolean(previewPresets);
        if (ImGui.checkbox("Preview while paused", preview)) previewPresets = preview.get();
        for (CameraRig.Type type : CameraRig.Type.values()) {
            if (ImGui.button("Add " + type.displayName)) addRig(h, type);
            ImGui.sameLine();
        }
        ImGui.newLine();
        ImGui.separator();
        for (int i = 0; i < h.editor.rigs.size(); i++) {
            CameraRig r = h.editor.rigs.get(i);
            ImGui.pushID("rig" + i);
            boolean open = ImGui.treeNode(r.type.displayName + " " + r.targetName + "  " + ReplayMeta.formatTicks(r.start)
                + "-" + ReplayMeta.formatTicks(r.end));
            if (ImGui.isItemClicked()) selectedRig = r;
            if (open) {
                int[] range = { r.start, r.end };
                if (ImGui.dragInt2("Start / end tick", range, 1, 0, h.getTotalTicks() - 1)) {
                    r.start = range[0];
                    r.end = Math.max(range[0] + 1, range[1]);
                }
                int[] blend = { r.blend };
                if (ImGui.sliderInt("Blend ticks", blend, 0, 100)) r.blend = blend[0];
                float[] smoothing = { (float) r.smoothing };
                if (ImGui.sliderFloat("Smoothing (s)", smoothing, 0, 2, "%.2f")) r.smoothing = smoothing[0];
                switch (r.type) {
                    case ORBIT -> {
                        float[] radius = { (float) r.radius }, height = { (float) r.height }, sp = { (float) r.orbitSpeed };
                        if (ImGui.sliderFloat("Radius", radius, 0.5f, 50, "%.1f")) r.radius = radius[0];
                        if (ImGui.sliderFloat("Height", height, -20, 30, "%.1f")) r.height = height[0];
                        if (ImGui.sliderFloat("Degrees per second", sp, -180, 180, "%.0f")) r.orbitSpeed = sp[0];
                    }
                    case FOLLOW -> {
                        float[] off = { (float) r.offsetRight, (float) r.offsetUp, (float) r.offsetForward };
                        if (ImGui.dragFloat3("Offset R/U/F", off, 0.05f)) {
                            r.offsetRight = off[0];
                            r.offsetUp = off[1];
                            r.offsetForward = off[2];
                        }
                        r.turnWithTarget = cb("Turn with target", r.turnWithTarget);
                    }
                    default -> {}
                }
                r.lookAtTarget = cb("Look at target", r.lookAtTarget);
                float[] aim = { (float) r.aimHeight };
                if (ImGui.sliderFloat("Aim height", aim, 0, 1.5f, "%.2f")) r.aimHeight = aim[0];
                if (ImGui.button("Start from camera")) {
                    if (!h.fitRigToCamera(r)) Notifications.show("Flashback", "The target is not in the world here");
                }
                ImGui.sameLine();
                if (ImGui.button("Delete")) {
                    h.editor.rigs.remove(i);
                    ImGui.treePop();
                    ImGui.popID();
                    break;
                }
                ImGui.treePop();
            }
            ImGui.popID();
        }
        if (!before.equals(h.editor.historyJson())) h.markEditorDirty();
        ImGui.end();
    }

    private void addRig(ReplayHandler h, CameraRig.Type type) {
        Entity target = h.getSpectating();
        if (target == null && mc.level != null && h.getLocalPlayerId() != Integer.MIN_VALUE) {
            target = mc.level.getEntity(h.getLocalPlayerId());
        }
        if (target == null) {
            Notifications.show("Flashback", "Spectate a player or mob to use it as the target");
            return;
        }
        CameraRig rig = new CameraRig();
        rig.type = type;
        rig.target = ReplayHandler.entityKey(target);
        rig.targetName = target.getDisplayName().getString();
        rig.start = h.getCurrentTick();
        rig.end = Math.min(h.getTotalTicks() - 1, rig.start + 200);
        h.stopSpectating();
        h.fitRigToCamera(rig);
        h.editor.rigs.add(rig);
        selectedRig = rig;
        h.markEditorDirty();
    }

    private void drawMovement(ReplayHandler h) {
        ImGui.setNextWindowPos(300, 40, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(260, 220, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Camera Movement")) {
            ImGui.end();
            return;
        }
        float[] sp = { h.cameraController.getSpeed() };
        if (ImGui.sliderFloat("Speed (blocks/s)", sp, 0.2f, 200f, "%.1f", imgui.flag.ImGuiSliderFlags.Logarithmic)) {
            h.cameraController.setSpeed(sp[0]);
        }
        boolean momentum = Config.get().cameraMomentum;
        if (ImGui.checkbox("Momentum", momentum)) {
            Config.get().cameraMomentum = !momentum;
            Config.save();
        }
        h.cameraController.horizontalMovement = cb("Horizontal movement", h.cameraController.horizontalMovement);
        h.cameraController.lockX = cb("Lock X", h.cameraController.lockX);
        ImGui.sameLine();
        h.cameraController.lockY = cb("Lock Y", h.cameraController.lockY);
        ImGui.sameLine();
        h.cameraController.lockZ = cb("Lock Z", h.cameraController.lockZ);
        ImGui.end();
    }

    private void drawHelp() {
        ImGui.setNextWindowSize(380, 360, ImGuiCond.FirstUseEver);
        ImBoolean open = new ImBoolean(true);
        if (ImGui.begin("Controls", open)) {
            String[] lines = { "Right mouse over the world: look around", "WASD, Space, Shift: fly (Ctrl: faster)",
                "Scroll over the world: flying speed", "Space: play / pause", "Left / Right: step (Shift: 1 second)",
                "Up / Down: next / previous keyframe", "K: camera keyframe   M: marker", "I / O: in and out points",
                "Ctrl+Z, Ctrl+Y, Ctrl+Shift+Z: undo / redo", "Ctrl+C, Ctrl+V, Ctrl+A, Del: keyframes",
                "Ctrl+scroll on the timeline: zoom", "Ctrl+E: export   F1: hide the UI   Esc: menu" };
            for (String l : lines) ImGui.bulletText(l);
        }
        ImGui.end();
        if (!open.get()) showHelp = false;
    }

    private void drawGuides(ReplayHandler h) {
        Visuals v = h.editor.visuals;
        if (!v.ruleOfThirds && !v.centerGuide) return;
        ImDrawList dl = ImGui.getBackgroundDrawList();
        float w = mc.getWindow().getWidth(), hh = mc.getWindow().getHeight();
        int c = col(0x80FFFFFF);
        if (v.ruleOfThirds) {
            dl.addLine(w / 3, 0, w / 3, hh, c, 1);
            dl.addLine(2 * w / 3, 0, 2 * w / 3, hh, c, 1);
            dl.addLine(0, hh / 3, w, hh / 3, c, 1);
            dl.addLine(0, 2 * hh / 3, w, 2 * hh / 3, c, 1);
        }
        if (v.centerGuide) {
            dl.addLine(w / 2 - 12, hh / 2, w / 2 + 12, hh / 2, c, 1);
            dl.addLine(w / 2, hh / 2 - 12, w / 2, hh / 2 + 12, c, 1);
        }
    }

    // ------------------------------------------------------------------ actions

    private boolean isSelected(Keyframe k) {
        for (Selection s : selection) if (s.keyframe == k) return true;
        return false;
    }

    private void setInPoint(ReplayHandler h) {
        h.editor.inPoint = h.getCurrentTick();
        if (h.editor.outPoint >= 0 && h.editor.outPoint <= h.editor.inPoint) h.editor.outPoint = -1;
        h.markEditorDirty();
    }

    private void setOutPoint(ReplayHandler h) {
        h.editor.outPoint = h.getCurrentTick();
        if (h.editor.inPoint >= h.editor.outPoint) h.editor.inPoint = -1;
        h.markEditorDirty();
    }

    private void addKeyframe(TrackType type) {
        ReplayHandler h = handler();
        if (h == null) return;
        CameraEntity cam = h.getCamera();
        int tick = h.getCurrentTick();
        double[] values;
        switch (type) {
            case CAMERA -> {
                if (cam == null) return;
                if (h.isSpectating()) {
                    Notifications.show("Flashback", "Camera keyframes aren't needed while spectating");
                    return;
                }
                values = new double[] { cam.eyeX(), cam.eyeY(), cam.eyeZ(), cam.getYRot(), cam.getXRot(),
                    h.editor.visuals.cameraRoll };
            }
            case FOV -> values = new double[] { !Float.isNaN(h.getFovOverride()) ? h.getFovOverride() : mc.options.fov().get() };
            case TIME -> values = new double[] { mc.level == null ? 0 : mc.level.getDayTime() % 24000 };
            case SPEED, SHAKE -> values = new double[] { 1.0 };
            case WEATHER -> {
                float rain = mc.level == null ? 0 : mc.level.getRainLevel(1.0F);
                float thunder = mc.level == null || rain <= 0 ? 0 : mc.level.getThunderLevel(1.0F) / rain;
                values = new double[] { rain, Math.min(1, thunder) };
            }
            default -> {
                return;
            }
        }
        KeyframeTrack track = h.editor.track(type);
        Keyframe k = track.put(tick, values, Interpolation.parse(Config.get().defaultInterpolation, Interpolation.SMOOTH));
        selection.clear();
        selection.add(new Selection(track, k));
        h.markEditorDirty();
    }

    private void deleteSelection() {
        ReplayHandler h = handler();
        if (h == null || selection.isEmpty()) return;
        for (Selection s : selection) s.track.keyframes.remove(s.keyframe);
        Notifications.show("Flashback", "Deleted " + selection.size() + " keyframe(s)");
        selection.clear();
        h.markEditorDirty();
    }

    private void copySelection() {
        if (selection.isEmpty()) return;
        clipboard.clear();
        int base = Integer.MAX_VALUE;
        for (Selection s : selection) base = Math.min(base, s.keyframe.tick);
        for (Selection s : selection) {
            Keyframe copy = s.keyframe.copy();
            copy.tick -= base;
            clipboard.add(new Object[] { s.track.type, copy });
        }
        Notifications.show("Flashback", "Copied " + clipboard.size() + " keyframe(s)");
    }

    private void paste() {
        ReplayHandler h = handler();
        if (h == null || clipboard.isEmpty()) return;
        int base = h.getCurrentTick();
        selection.clear();
        for (Object[] o : clipboard) {
            KeyframeTrack track = h.editor.track((TrackType) o[0]);
            Keyframe src = (Keyframe) o[1];
            int tick = Mth.clamp(base + src.tick, 0, h.getTotalTicks() - 1);
            Keyframe k = track.put(tick, src.values.clone(), src.interpolation);
            k.easeIn = src.easeIn;
            k.easeOut = src.easeOut;
            selection.add(new Selection(track, k));
        }
        h.markEditorDirty();
        Notifications.show("Flashback", "Pasted " + clipboard.size() + " keyframe(s)");
    }

    private void selectAll() {
        ReplayHandler h = handler();
        if (h == null) return;
        selection.clear();
        for (KeyframeTrack t : h.editor.tracks) for (Keyframe k : t.keyframes) selection.add(new Selection(t, k));
    }

    private void jumpKeyframe(boolean forward) {
        ReplayHandler h = handler();
        if (h == null) return;
        int cur = h.getCurrentTick();
        int best = forward ? Integer.MAX_VALUE : Integer.MIN_VALUE;
        for (KeyframeTrack t : h.editor.tracks) {
            for (Keyframe k : t.keyframes) {
                if (forward && k.tick > cur && k.tick < best) best = k.tick;
                if (!forward && k.tick < cur && k.tick > best) best = k.tick;
            }
        }
        if (best != Integer.MAX_VALUE && best != Integer.MIN_VALUE) h.seek(best);
    }

    private void addMarker() {
        ReplayHandler h = handler();
        if (h == null) return;
        ReplayMeta.Marker m = new ReplayMeta.Marker(h.getCurrentTick(), 0xFFE0C040, "");
        CameraEntity cam = h.getCamera();
        if (cam != null) {
            m.hasPosition = true;
            m.x = cam.getX();
            m.y = cam.getY();
            m.z = cam.getZ();
        }
        h.editor.markers.add(m);
        h.markEditorDirty();
        Notifications.show("Flashback", "Added marker at " + ReplayMeta.formatTicks(m.tick));
    }

    private void undo() {
        ReplayHandler h = handler();
        if (h == null) return;
        if (h.history.undo(h.editor)) afterHistory(h, "Undone");
        else Notifications.show("Flashback", "Nothing to undo");
    }

    private void redo() {
        ReplayHandler h = handler();
        if (h == null) return;
        if (h.history.redo(h.editor)) afterHistory(h, "Redone");
        else Notifications.show("Flashback", "Nothing to redo");
    }

    /** The editor's contents were replaced: nothing selected may point at what was there. */
    private void afterHistory(ReplayHandler h, String what) {
        selection.clear();
        selectedRig = null;
        draggingKeys = false;
        h.markEditorDirty();
        Notifications.show("Flashback", what);
    }

    /** Unused: kept for input fields that need a text buffer. */
    static ImString buffer(String s, int size) {
        ImString b = new ImString(size);
        b.set(s);
        return b;
    }
}
