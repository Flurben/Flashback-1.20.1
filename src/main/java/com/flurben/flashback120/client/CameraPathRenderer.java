package com.flurben.flashback120.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.flurben.flashback120.editor.Keyframe;
import com.flurben.flashback120.editor.KeyframeTrack;
import com.flurben.flashback120.editor.TrackType;
import com.flurben.flashback120.export.Exporter;
import com.flurben.flashback120.replay.ReplayHandler;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** The camera keyframe path in the world: a line along the path and a short look direction at each keyframe. */
public final class CameraPathRenderer {

    private CameraPathRenderer() {}

    public static void render(WorldRenderContext context) {
        ReplayHandler h = ReplayHandler.get();
        if (h == null || Exporter.isExporting() || !h.editor.visuals.showCameraPath) return;
        KeyframeTrack track = h.editor.track(TrackType.CAMERA);
        if (!track.isActive() || track.keyframes.size() < 1) return;
        MultiBufferSource buffers = context.consumers();
        if (buffers == null) return;
        PoseStack pose = context.matrixStack();
        Vec3 cam = context.camera().getPosition();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = pose.last().pose();
        Matrix3f n = pose.last().normal();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        int first = track.keyframes.get(0).tick, last = track.keyframes.get(track.keyframes.size() - 1).tick;
        double step = Math.max(0.25, (last - first) / 600.0);
        double[] prev = null;
        for (double t = first; t <= last + 1e-6; t += step) {
            double[] v = track.evaluate(t);
            if (prev != null) line(lines, m, n, prev[0], prev[1], prev[2], v[0], v[1], v[2], 0xFF4FA3E0);
            prev = v;
        }
        for (Keyframe k : track.keyframes) {
            double[] v = k.values;
            double yaw = Math.toRadians(v[3]), pitch = Math.toRadians(v[4]);
            double dx = -Math.sin(yaw) * Math.cos(pitch), dy = -Math.sin(pitch), dz = Math.cos(yaw) * Math.cos(pitch);
            line(lines, m, n, v[0], v[1], v[2], v[0] + dx, v[1] + dy, v[2] + dz, 0xFFFFD040);
            line(lines, m, n, v[0] - 0.15, v[1], v[2], v[0] + 0.15, v[1], v[2], 0xFFFFFFFF);
            line(lines, m, n, v[0], v[1] - 0.15, v[2], v[0], v[1] + 0.15, v[2], 0xFFFFFFFF);
            line(lines, m, n, v[0], v[1], v[2] - 0.15, v[0], v[1], v[2] + 0.15, 0xFFFFFFFF);
        }
        pose.popPose();
    }

    private static void line(VertexConsumer c, Matrix4f m, Matrix3f n, double x1, double y1, double z1, double x2,
        double y2, double z2, int argb) {
        float dx = (float) (x2 - x1), dy = (float) (y2 - y1), dz = (float) (z2 - z1);
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return;
        dx /= len;
        dy /= len;
        dz /= len;
        int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        c.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, b, a).normal(n, dx, dy, dz).endVertex();
        c.vertex(m, (float) x2, (float) y2, (float) z2).color(r, g, b, a).normal(n, dx, dy, dz).endVertex();
    }
}
