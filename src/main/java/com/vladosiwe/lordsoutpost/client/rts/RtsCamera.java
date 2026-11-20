package com.vladosiwe.lordsoutpost.client.rts;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Стратегическая камера RTS-режима. Только клиентское представление: вид рисуется из невидимой метки (Marker),
 * которая не добавляется в мир; настоящий игрок остаётся на своём месте и виден со стороны.
 * Камера облетает точку фокуса на земле: перемещение, приближение, вращение, наклон — плавно, в границах территории.
 */
public final class RtsCamera {
    private static boolean active;
    @Nullable private static Entity eye;
    private static double cx, cz, limit, baseY;
    // Цель и сглаженное состояние: фокус на земле, угол поворота, наклон, расстояние.
    private static double fx, fz, yaw = 180, pitch = 55, dist = 34;
    private static double sx, sz, sy, syaw = 180, spitch = 55, sdist = 34;
    private static long lastNanos;
    private static boolean prevHideGui;
    @Nullable private static CameraType prevType;
    private static double savedDist = 34;
    private static boolean overview;

    private RtsCamera() {}

    public static boolean active() {
        return active;
    }

    public static void enter(double centerX, double centerY, double centerZ, double bound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        cx = centerX;
        cz = centerZ;
        baseY = centerY;
        limit = Math.max(16, bound);
        fx = sx = centerX;
        fz = sz = centerZ;
        sy = centerY;
        yaw = syaw = 180;
        pitch = spitch = 55;
        dist = sdist = Math.min(60, Math.max(28, bound * 0.8));
        overview = false;
        eye = EntityType.MARKER.create(mc.level);
        if (eye == null) return;
        prevHideGui = mc.options.hideGui;
        prevType = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.FIRST_PERSON);
        mc.options.hideGui = true;
        mc.gameRenderer.setRenderHand(false);
        active = true;
        lastNanos = System.nanoTime();
        place();
        mc.setCameraEntity(eye);
    }

    public static void exit() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        active = false;
        if (mc.player != null) mc.setCameraEntity(mc.player);
        mc.gameRenderer.setRenderHand(true);
        mc.options.hideGui = prevHideGui;
        if (prevType != null) mc.options.setCameraType(prevType);
        eye = null;
    }

    /** Сдвиг фокуса в координатах экрана: вперёд/вбок относительно поворота камеры. */
    public static void pan(double forward, double right) {
        double r = Math.toRadians(yaw);
        double scale = 0.35 + dist * 0.02;
        fx += (-Math.sin(r) * forward - Math.cos(r) * right) * scale;
        fz += (Math.cos(r) * forward - Math.sin(r) * right) * scale;
        clamp();
    }

    public static void rotate(double degrees) {
        yaw = (yaw + degrees) % 360;
    }

    public static void tilt(double degrees) {
        pitch = Mth.clamp(pitch + degrees, 25, 89);
    }

    public static void zoom(double steps) {
        dist = Mth.clamp(dist * Math.pow(0.88, steps), 8, 110);
        overview = false;
    }

    /** Вернуть камеру к центру аванпоста. */
    public static void home() {
        fx = cx;
        fz = cz;
    }

    /** Переключение: обзор всей территории сверху ↔ прежний участок. */
    public static void toggleOverview() {
        if (!overview) {
            savedDist = dist;
            fx = cx;
            fz = cz;
            dist = Mth.clamp(limit * 2.2, 40, 110);
            pitch = 80;
            overview = true;
        } else {
            dist = savedDist;
            pitch = 55;
            overview = false;
        }
    }

    public static void focusOn(double x, double z) {
        fx = x;
        fz = z;
        clamp();
    }

    private static void clamp() {
        double dx = fx - cx, dz = fz - cz;
        double d = Math.max(Math.abs(dx), Math.abs(dz));
        if (d > limit) {
            fx = cx + dx * limit / d;
            fz = cz + dz * limit / d;
        }
    }

    /** Каждый кадр: плавное приближение к цели и установка метки-камеры. */
    public static void frame() {
        if (!active || eye == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.getCameraEntity() != eye) {
            exit();
            return;
        }
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1e9);
        lastNanos = now;
        double k = 1 - Math.exp(-dt * 8);
        sx += (fx - sx) * k;
        sz += (fz - sz) * k;
        double ground = groundAt(sx, sz);
        sy += (ground - sy) * k;
        double dyaw = Mth.wrapDegrees(yaw - syaw);
        syaw += dyaw * k;
        spitch += (pitch - spitch) * k;
        sdist += (dist - sdist) * k;
        place();
    }

    private static double groundAt(double x, double z) {
        Minecraft mc = Minecraft.getInstance();
        int bx = Mth.floor(x), bz = Mth.floor(z);
        if (mc.level == null || !mc.level.hasChunk(bx >> 4, bz >> 4)) return baseY;
        int h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
        // Не нырять в ямы и не прыгать по кронам: высота фокуса держится рядом с уровнем двора.
        return Mth.clamp(h, baseY - 6, baseY + 10);
    }

    private static void place() {
        if (eye == null) return;
        double yr = Math.toRadians(syaw), pr = Math.toRadians(spitch);
        double hx = -Math.sin(yr) * Math.cos(pr), hz = Math.cos(yr) * Math.cos(pr), hy = -Math.sin(pr);
        // Камера на расстоянии sdist от фокуса, против направления взгляда.
        double ex = sx - hx * sdist, ey = sy - hy * sdist, ez = sz - hz * sdist;
        eye.setPos(ex, ey, ez);
        eye.xo = eye.xOld = ex;
        eye.yo = eye.yOld = ey;
        eye.zo = eye.zOld = ez;
        eye.setYRot((float) syaw);
        eye.setXRot((float) spitch);
        eye.yRotO = (float) syaw;
        eye.xRotO = (float) spitch;
    }

    public static Vec3 focus() {
        return new Vec3(sx, sy, sz);
    }

    public static double centerX() {
        return cx;
    }

    public static double centerZ() {
        return cz;
    }

    public static double limit() {
        return limit;
    }
}
