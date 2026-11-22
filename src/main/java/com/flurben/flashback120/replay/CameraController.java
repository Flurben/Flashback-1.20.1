package com.flurben.flashback120.replay;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

import com.flurben.flashback120.Config;
import com.flurben.flashback120.gui.ReplayEditorScreen;
import com.mojang.blaze3d.platform.InputConstants;

/** Frame-rate independent free camera movement (WASD + jump/sneak, scroll wheel for speed). */
public final class CameraController {

    private double vx, vy, vz;
    private long lastNanos;
    private float speedMultiplier = 1.0f;
    public boolean lockX, lockY, lockZ;
    public boolean horizontalMovement = true;

    public float getSpeed() {
        return Config.get().cameraBaseSpeed * speedMultiplier;
    }

    /** Sets the flying speed in blocks per second. */
    public void setSpeed(float blocksPerSecond) {
        speedMultiplier = Mth.clamp(blocksPerSecond / Math.max(0.01f, Config.get().cameraBaseSpeed), 0.02f, 50f);
    }

    public void adjustSpeed(double wheelDelta) {
        if (wheelDelta == 0) return;
        float factor = wheelDelta > 0 ? 1.15f : 1 / 1.15f;
        speedMultiplier = Mth.clamp(speedMultiplier * factor, 0.02f, 50f);
    }

    public void reset() {
        vx = vy = vz = 0;
        lastNanos = 0;
    }

    /** True if keyboard movement should currently move the camera. */
    public static boolean isControlActive() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return mc.isWindowActive();
        return mc.screen instanceof ReplayEditorScreen editor && editor.isCameraGrabbed();
    }

    public void update(CameraEntity camera) {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        dt = Math.min(dt, 0.1);
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;

        double fwd = 0, strafe = 0, up = 0;
        boolean boost = false;
        if (isControlActive()) {
            if (isDown(o.keyUp)) fwd += 1;
            if (isDown(o.keyDown)) fwd -= 1;
            if (isDown(o.keyLeft)) strafe += 1;
            if (isDown(o.keyRight)) strafe -= 1;
            if (isDown(o.keyJump)) up += 1;
            if (isDown(o.keyShift)) up -= 1;
            boost = isDown(o.keySprint) || InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL);
        }

        double speed = getSpeed() * (boost ? 3.0 : 1.0);
        double yawRad = Math.toRadians(camera.getYRot());
        double pitchRad = Math.toRadians(camera.getXRot());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double tx, ty, tz;
        if (horizontalMovement) {
            tx = (-sin * fwd + cos * strafe);
            tz = (cos * fwd + sin * strafe);
            ty = up;
        } else {
            double cp = Math.cos(pitchRad);
            tx = (-sin * cp * fwd + cos * strafe);
            tz = (cos * cp * fwd + sin * strafe);
            ty = -Math.sin(pitchRad) * fwd + up;
        }
        double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len > 1) {
            tx /= len;
            ty /= len;
            tz /= len;
        }
        tx *= speed;
        ty *= speed;
        tz *= speed;
        if (lockX) tx = 0;
        if (lockY) ty = 0;
        if (lockZ) tz = 0;

        if (Config.get().cameraMomentum) {
            double k = 1 - Math.exp(-dt * 8.0);
            vx += (tx - vx) * k;
            vy += (ty - vy) * k;
            vz += (tz - vz) * k;
        } else {
            vx = tx;
            vy = ty;
            vz = tz;
        }
        if (Math.abs(vx) < 1e-4 && Math.abs(vy) < 1e-4 && Math.abs(vz) < 1e-4) {
            vx = vy = vz = 0;
            camera.snapInterpolation();
            camera.remember();
            return;
        }
        camera.setPos(camera.getX() + vx * dt, camera.getY() + vy * dt, camera.getZ() + vz * dt);
        camera.snapInterpolation();
        camera.remember();
    }

    /** Polls the key itself: key mappings are not updated while a screen is open. */
    public static boolean isDown(KeyMapping mapping) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(mapping);
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() < 0) return false;
        return InputConstants.isKeyDown(window, key.getValue());
    }
}
