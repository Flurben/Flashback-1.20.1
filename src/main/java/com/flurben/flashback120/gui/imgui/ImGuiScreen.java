package com.flurben.flashback120.gui.imgui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A screen drawn with ImGui over the game. Input goes to ImGui first; what ImGui does not want goes to
 * {@link #worldMouse}, {@link #worldKey} and {@link #worldScroll}.
 */
public abstract class ImGuiScreen extends Screen {

    protected ImGuiScreen(String title) {
        super(Component.literal(title));
    }

    /** Draws the ImGui windows of this frame. */
    protected abstract void drawUi(float partialTicks);

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        graphics.flush();
        ImGuiLayer.beginFrame();
        try {
            drawUi(partialTicks);
        } finally {
            ImGuiLayer.endFrame();
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {}

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** A mouse button over the world, not over an ImGui window. Return true when handled. */
    protected boolean worldMouse(int button, boolean down) {
        return false;
    }

    /** A key ImGui did not want. Return true when handled. */
    protected boolean worldKey(int key, int scancode, int modifiers) {
        return false;
    }

    protected boolean worldScroll(double amount) {
        return false;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        ImGuiLayer.mouseButton(button, true);
        if (!ImGuiLayer.wantsMouse()) worldMouse(button, true);
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        ImGuiLayer.mouseButton(button, false);
        worldMouse(button, false);
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (ImGuiLayer.wantsMouse()) {
            ImGuiLayer.scroll(0, amount);
            return true;
        }
        if (!worldScroll(amount)) ImGuiLayer.scroll(0, amount);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        ImGuiLayer.key(key, true, modifiers);
        if (ImGuiLayer.wantsKeyboard()) return true;
        if (worldKey(key, scancode, modifiers)) return true;
        return super.keyPressed(key, scancode, modifiers);
    }

    @Override
    public boolean keyReleased(int key, int scancode, int modifiers) {
        ImGuiLayer.key(key, false, modifiers);
        return true;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        ImGuiLayer.character(c);
        return true;
    }
}
