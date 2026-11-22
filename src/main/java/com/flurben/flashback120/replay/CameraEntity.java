package com.flurben.flashback120.replay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * The replay viewer. It replaces the client player while a replay is open: it never ticks physics, never collides,
 * is never rendered and is moved per frame by {@link CameraController} or the camera keyframes.
 */
public class CameraEntity extends LocalPlayer {

    /** Last known camera transform, carried over when the client re-creates the player (respawn or seek). */
    static double lastX, lastY, lastZ;
    static float lastYaw, lastPitch;
    static boolean hasLast;

    private final MobEffectInstance nightVision = new MobEffectInstance(MobEffects.NIGHT_VISION, 1000000, 0, false, false);

    public CameraEntity(Minecraft mc, ClientLevel level, ClientPacketListener listener, StatsCounter stats,
        ClientRecipeBook recipeBook) {
        super(mc, level, listener, stats, recipeBook, false, false);
        this.noPhysics = true;
        this.getAbilities().flying = true;
        this.getAbilities().mayfly = true;
        this.getAbilities().invulnerable = true;
        if (hasLast) restoreLast();
    }

    public void restoreLast() {
        setCameraPosition(lastX, lastY, lastZ, lastYaw, lastPitch);
    }

    /** Moves the camera without any interpolation (eye position). */
    public void setCameraPosition(double x, double y, double z, float yaw, float pitch) {
        this.setPos(x, y - this.getEyeHeight(), z);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.setYHeadRot(yaw);
        this.yBodyRot = yaw;
        this.setDeltaMovement(Vec3.ZERO);
        snapInterpolation();
        remember();
    }

    /** Eye position x, y, z. */
    public double eyeX() {
        return getX();
    }

    public double eyeY() {
        return getY() + getEyeHeight();
    }

    public double eyeZ() {
        return getZ();
    }

    public void remember() {
        lastX = getX();
        lastY = getY() + getEyeHeight();
        lastZ = getZ();
        lastYaw = getYRot();
        lastPitch = getXRot();
        hasLast = true;
    }

    public static void forgetLast() {
        hasLast = false;
    }

    /** Keeps render interpolation from smearing the per-frame camera movement. */
    public void snapInterpolation() {
        this.xo = this.xOld = getX();
        this.yo = this.yOld = getY();
        this.zo = this.zOld = getZ();
        this.yRotO = getYRot();
        this.xRotO = getXRot();
        this.yHeadRot = this.yHeadRotO = getYRot();
        this.yBodyRot = this.yBodyRotO = getYRot();
    }

    @Override
    public void tick() {
        // no physics and no movement packets
        this.tickCount++;
        this.noPhysics = true;
        snapInterpolation();
        remember();
    }

    @Override
    public void aiStep() {}

    @Override
    public void move(MoverType type, Vec3 movement) {
        setPos(getX() + movement.x, getY() + movement.y, getZ() + movement.z);
    }

    @Override
    public void respawn() {}

    @Override
    public boolean isSpectator() {
        return true;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public void push(Entity entity) {}

    @Override
    public boolean isInvisible() {
        return true;
    }

    @Override
    public boolean shouldRender(double x, double y, double z) {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return false;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public float getFieldOfViewModifier() {
        return 1.0F;
    }

    @Override
    public boolean hasEffect(MobEffect effect) {
        return effect == MobEffects.NIGHT_VISION && ReplayHandler.visuals().nightVision;
    }

    @Override
    @Nullable
    public MobEffectInstance getEffect(MobEffect effect) {
        return hasEffect(effect) ? nightVision : null;
    }

    @Override
    public void playSound(SoundEvent sound, float volume, float pitch) {}

    @Override
    public boolean isShiftKeyDown() {
        return false;
    }

    @Override
    public boolean isCrouching() {
        return false;
    }

    @Override
    public boolean isSleeping() {
        return false;
    }

    @Override
    public boolean isInWall() {
        return false;
    }
}
