package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Строй в бою (идея стражи Guard Villagers — стрелки держатся за щитоносцами): лучник или арбалетчик, оказавшийся
 * ближе к врагу, чем щитоносец своей стороны рядом, отходит за его щит — на 2,5 блока за ним по линии на врага —
 * и снова стреляет оттуда. Перестроение короткое: как только стрелок за щитом, цель отдаётся обычной стрельбе.
 */
public class ShieldLineGoal extends Goal {
    private final FighterEntity mob;
    @Nullable private FighterEntity shield;
    private Vec3 spot = Vec3.ZERO;
    private int ticks;
    private int nextCheck;

    public ShieldLineGoal(FighterEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!mob.ranged() || mob.tickCount < nextCheck) return false;
        nextCheck = mob.tickCount + 20;
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        double myDist = mob.distanceToSqr(target);
        if (myDist > 20 * 20) return false;
        shield = null;
        double best = Double.MAX_VALUE;
        for (FighterEntity f : mob.level().getEntitiesOfClass(FighterEntity.class, mob.getBoundingBox().inflate(12, 4, 12),
                f -> f != mob && f.isAlive() && f.role() == Role.SHIELDMAN && f.hostileFaction() == mob.hostileFaction()
                        && f.outpostId() == mob.outpostId())) {
            double d = f.distanceToSqr(mob);
            if (d < best) {
                best = d;
                shield = f;
            }
        }
        if (shield == null || shield.distanceToSqr(target) >= myDist) return false;
        Vec3 away = shield.position().subtract(target.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) return false;
        spot = shield.position().add(away.normalize().scale(2.5));
        return mob.distanceToSqr(spot) > 1.5 * 1.5;
    }

    @Override
    public void start() {
        ticks = 60;
        mob.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.25);
    }

    @Override
    public boolean canContinueToUse() {
        return --ticks > 0 && shield != null && shield.isAlive() && mob.getTarget() != null && mob.distanceToSqr(spot) > 1.2 * 1.2;
    }

    @Override
    public void tick() {
        if (mob.getTarget() != null) mob.getLookControl().setLookAt(mob.getTarget(), 30f, 30f);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
        shield = null;
    }
}
