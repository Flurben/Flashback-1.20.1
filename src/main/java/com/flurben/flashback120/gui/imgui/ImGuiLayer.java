package com.flurben.flashback120.gui.imgui;

import java.io.File;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.Flashback120;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;

import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImGuiStyle;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiKey;
import imgui.gl3.ImGuiImplGl3;

/**
 * Dear ImGui inside Minecraft: one context, rendered with OpenGL 3 on top of the game, fed with the input events of
 * the screen that shows it. Only the screens of this mod use it.
 */
public final class ImGuiLayer {

    private static boolean initialized;
    private static ImGuiImplGl3 renderer;
    private static long lastFrame;
    private static float fontScale = -1;
    private static boolean inFrame;

    private ImGuiLayer() {}

    private static void init() {
        if (initialized) return;
        initialized = true;
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setConfigFlags(ImGuiConfigFlags.DockingEnable);
        File ini = FabricLoader.getInstance().getConfigDir().resolve("flashback120-imgui.ini").toFile();
        io.setIniFilename(ini.getAbsolutePath());
        loadFonts(io);
        applyStyle();
        renderer = new ImGuiImplGl3();
        renderer.init("#version 150");
        Flashback120.LOG.info("ImGui ready");
    }

    /** A system UI font where there is one (nothing is bundled), else ImGui's own. */
    private static void loadFonts(ImGuiIO io) {
        float size = Math.round(16 * Math.max(0.5f, Config.get().uiScale) * contentScale());
        String[] candidates = {
            "C:/Windows/Fonts/segoeui.ttf",
            "/System/Library/Fonts/SFNS.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/noto/NotoSans-Regular.ttf",
            "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf" };
        io.getFonts().clear();
        for (String path : candidates) {
            if (new File(path).isFile()) {
                ImFontConfig config = new ImFontConfig();
                config.setOversampleH(2);
                config.setOversampleV(2);
                io.getFonts().addFontFromFileTTF(path, size, config, io.getFonts().getGlyphRangesDefault());
                config.destroy();
                fontScale = 1;
                return;
            }
        }
        ImFontConfig config = new ImFontConfig();
        config.setSizePixels(13 * Math.max(1, Math.round(size / 13)));
        io.getFonts().addFontDefault(config);
        config.destroy();
        fontScale = 1;
    }

    private static float contentScale() {
        float[] x = new float[1], y = new float[1];
        try {
            GLFW.glfwGetWindowContentScale(Minecraft.getInstance().getWindow().getWindow(), x, y);
            return Math.max(1, Math.min(3, x[0]));
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Dark grey panels with a blue accent, close to Flashback's editor. */
    private static void applyStyle() {
        ImGui.styleColorsDark();
        ImGuiStyle style = ImGui.getStyle();
        style.setWindowRounding(4);
        style.setFrameRounding(3);
        style.setGrabRounding(3);
        style.setPopupRounding(4);
        style.setScrollbarRounding(4);
        style.setTabRounding(3);
        style.setWindowBorderSize(1);
        style.setFramePadding(6, 4);
        style.setItemSpacing(8, 5);
        style.scaleAllSizes(Math.max(0.5f, Config.get().uiScale) * contentScale());
        color(style, ImGuiCol.WindowBg, 0.11f, 0.11f, 0.13f, 0.96f);
        color(style, ImGuiCol.ChildBg, 0.09f, 0.09f, 0.11f, 0.6f);
        color(style, ImGuiCol.PopupBg, 0.10f, 0.10f, 0.12f, 0.98f);
        color(style, ImGuiCol.Border, 0.25f, 0.25f, 0.30f, 0.6f);
        color(style, ImGuiCol.FrameBg, 0.18f, 0.18f, 0.22f, 1f);
        color(style, ImGuiCol.FrameBgHovered, 0.24f, 0.24f, 0.30f, 1f);
        color(style, ImGuiCol.FrameBgActive, 0.28f, 0.28f, 0.36f, 1f);
        color(style, ImGuiCol.TitleBg, 0.09f, 0.09f, 0.11f, 1f);
        color(style, ImGuiCol.TitleBgActive, 0.13f, 0.13f, 0.17f, 1f);
        color(style, ImGuiCol.MenuBarBg, 0.10f, 0.10f, 0.12f, 1f);
        color(style, ImGuiCol.Button, 0.20f, 0.22f, 0.28f, 1f);
        color(style, ImGuiCol.ButtonHovered, 0.27f, 0.42f, 0.70f, 1f);
        color(style, ImGuiCol.ButtonActive, 0.24f, 0.48f, 0.86f, 1f);
        color(style, ImGuiCol.Header, 0.22f, 0.30f, 0.46f, 1f);
        color(style, ImGuiCol.HeaderHovered, 0.27f, 0.42f, 0.70f, 1f);
        color(style, ImGuiCol.HeaderActive, 0.24f, 0.48f, 0.86f, 1f);
        color(style, ImGuiCol.CheckMark, 0.40f, 0.65f, 1.00f, 1f);
        color(style, ImGuiCol.SliderGrab, 0.36f, 0.58f, 0.94f, 1f);
        color(style, ImGuiCol.SliderGrabActive, 0.46f, 0.68f, 1.00f, 1f);
        color(style, ImGuiCol.Tab, 0.15f, 0.16f, 0.20f, 1f);
        color(style, ImGuiCol.TabHovered, 0.27f, 0.42f, 0.70f, 1f);
        color(style, ImGuiCol.TabActive, 0.22f, 0.34f, 0.56f, 1f);
    }

    private static void color(ImGuiStyle style, int col, float r, float g, float b, float a) {
        style.setColor(col, r, g, b, a);
    }

    /** Starts an ImGui frame. Call from a screen's render method. */
    public static void beginFrame() {
        init();
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        ImGuiIO io = ImGui.getIO();
        int fbW = window.getWidth(), fbH = window.getHeight();
        io.setDisplaySize(fbW, fbH);
        io.setDisplayFramebufferScale(1, 1);
        long now = System.nanoTime();
        io.setDeltaTime(lastFrame == 0 ? 1 / 60f : Math.max(1e-4f, (now - lastFrame) / 1e9f));
        lastFrame = now;
        // the mouse handler reports window coordinates; ImGui works in framebuffer pixels here
        double sx = (double) fbW / Math.max(1, window.getScreenWidth());
        double sy = (double) fbH / Math.max(1, window.getScreenHeight());
        if (mc.mouseHandler.isMouseGrabbed()) {
            io.addMousePosEvent(-Float.MAX_VALUE, -Float.MAX_VALUE);
        } else {
            io.addMousePosEvent((float) (mc.mouseHandler.xpos() * sx), (float) (mc.mouseHandler.ypos() * sy));
        }
        renderer.newFrame();
        ImGui.newFrame();
        inFrame = true;
    }

    /** Ends the frame and draws it. */
    public static void endFrame() {
        if (!inFrame) return;
        inFrame = false;
        ImGui.render();
        RenderSystem.assertOnRenderThread();
        renderer.renderDrawData(ImGui.getDrawData());
    }

    public static boolean wantsMouse() {
        return initialized && ImGui.getIO().getWantCaptureMouse();
    }

    public static boolean wantsKeyboard() {
        return initialized && ImGui.getIO().getWantCaptureKeyboard();
    }

    // ------------------------------------------------------------------ input

    public static void mouseButton(int button, boolean down) {
        if (!initialized || button < 0 || button > 4) return;
        ImGui.getIO().addMouseButtonEvent(button, down);
    }

    public static void scroll(double x, double y) {
        if (!initialized) return;
        ImGui.getIO().addMouseWheelEvent((float) x, (float) y);
    }

    public static void character(int codePoint) {
        if (!initialized) return;
        ImGui.getIO().addInputCharacter(codePoint);
    }

    public static void key(int glfwKey, boolean down, int modifiers) {
        if (!initialized) return;
        ImGuiIO io = ImGui.getIO();
        io.addKeyEvent(ImGuiKey.ModCtrl, (modifiers & GLFW.GLFW_MOD_CONTROL) != 0);
        io.addKeyEvent(ImGuiKey.ModShift, (modifiers & GLFW.GLFW_MOD_SHIFT) != 0);
        io.addKeyEvent(ImGuiKey.ModAlt, (modifiers & GLFW.GLFW_MOD_ALT) != 0);
        io.addKeyEvent(ImGuiKey.ModSuper, (modifiers & GLFW.GLFW_MOD_SUPER) != 0);
        int key = toImGuiKey(glfwKey);
        if (key != ImGuiKey.None) io.addKeyEvent(key, down);
    }

    public static int toImGuiKey(int k) {
        if (k >= GLFW.GLFW_KEY_A && k <= GLFW.GLFW_KEY_Z) return ImGuiKey.A + (k - GLFW.GLFW_KEY_A);
        if (k >= GLFW.GLFW_KEY_0 && k <= GLFW.GLFW_KEY_9) return ImGuiKey._0 + (k - GLFW.GLFW_KEY_0);
        if (k >= GLFW.GLFW_KEY_F1 && k <= GLFW.GLFW_KEY_F12) return ImGuiKey.F1 + (k - GLFW.GLFW_KEY_F1);
        if (k >= GLFW.GLFW_KEY_KP_0 && k <= GLFW.GLFW_KEY_KP_9) return ImGuiKey.Keypad0 + (k - GLFW.GLFW_KEY_KP_0);
        switch (k) {
            case GLFW.GLFW_KEY_TAB: return ImGuiKey.Tab;
            case GLFW.GLFW_KEY_LEFT: return ImGuiKey.LeftArrow;
            case GLFW.GLFW_KEY_RIGHT: return ImGuiKey.RightArrow;
            case GLFW.GLFW_KEY_UP: return ImGuiKey.UpArrow;
            case GLFW.GLFW_KEY_DOWN: return ImGuiKey.DownArrow;
            case GLFW.GLFW_KEY_PAGE_UP: return ImGuiKey.PageUp;
            case GLFW.GLFW_KEY_PAGE_DOWN: return ImGuiKey.PageDown;
            case GLFW.GLFW_KEY_HOME: return ImGuiKey.Home;
            case GLFW.GLFW_KEY_END: return ImGuiKey.End;
            case GLFW.GLFW_KEY_INSERT: return ImGuiKey.Insert;
            case GLFW.GLFW_KEY_DELETE: return ImGuiKey.Delete;
            case GLFW.GLFW_KEY_BACKSPACE: return ImGuiKey.Backspace;
            case GLFW.GLFW_KEY_SPACE: return ImGuiKey.Space;
            case GLFW.GLFW_KEY_ENTER: return ImGuiKey.Enter;
            case GLFW.GLFW_KEY_ESCAPE: return ImGuiKey.Escape;
            case GLFW.GLFW_KEY_KP_ENTER: return ImGuiKey.KeypadEnter;
            case GLFW.GLFW_KEY_KP_DECIMAL: return ImGuiKey.KeypadDecimal;
            case GLFW.GLFW_KEY_KP_SUBTRACT: return ImGuiKey.KeypadSubtract;
            case GLFW.GLFW_KEY_KP_ADD: return ImGuiKey.KeypadAdd;
            case GLFW.GLFW_KEY_LEFT_CONTROL: return ImGuiKey.LeftCtrl;
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return ImGuiKey.RightCtrl;
            case GLFW.GLFW_KEY_LEFT_SHIFT: return ImGuiKey.LeftShift;
            case GLFW.GLFW_KEY_RIGHT_SHIFT: return ImGuiKey.RightShift;
            case GLFW.GLFW_KEY_LEFT_ALT: return ImGuiKey.LeftAlt;
            case GLFW.GLFW_KEY_RIGHT_ALT: return ImGuiKey.RightAlt;
            case GLFW.GLFW_KEY_MINUS: return ImGuiKey.Minus;
            case GLFW.GLFW_KEY_PERIOD: return ImGuiKey.Period;
            case GLFW.GLFW_KEY_COMMA: return ImGuiKey.Comma;
            case GLFW.GLFW_KEY_SLASH: return ImGuiKey.Slash;
            default: return ImGuiKey.None;
        }
    }
}
