package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.entity.CaptainEntity;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

/**
 * Тактика Лорда мародёров. Пока у него есть стража, он не лезет в первую линию:
 *  - стоит за ближайшим к врагу стражником, прикрываясь щитом, и бросает на врага всю стражу вокруг;
 *  - если враг прорвался вплотную или прикрыться некем — дерётся сам (это делает обычная цель ближнего боя);
 *  - на четверти здоровья отходит к трону, один раз за бой пьёт целебное зелье и зовёт подмогу.
 */
public class CaptainTacticsGoal extends Goal {
    private static final double GUARD_RADIUS = 20;
    private static final double MELEE_RANGE = 3.5;
    private final CaptainEntity mob;
    private boolean potionUsed;
    private int rallyCooldown;
    private int repath;

    public CaptainTacticsGoal(CaptainEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** Живые бойцы своей стороны рядом с Лордом. */
    public static List<FighterEntity> guards(CaptainEntity mob) {
        return mob.level().getEntitiesOfClass(FighterEntity.class, mob.getBoundingBox().inflate(GUARD_RADIUS),
                f -> f != mob && f.isAlive() && !(f instanceof CaptainEntity) && mob.isFriend(f));
    }

    private boolean retreating() {
        return mob.getHealth() < mob.getMaxHealth() * 0.25f;
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) return false;
        if (retreating()) return mob.distanceTo(target) > 2.0 || !potionUsed;
        return mob.distanceTo(target) > MELEE_RANGE && guards(mob).size() >= 2;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        if (mob.isUsingItem()) mob.stopUsingItem();
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) return;
        mob.getLookControl().setLookAt(target, 30f, 30f);
        List<FighterEntity> guards = guards(mob);

        // Бросить стражу на врага.
        if (--rallyCooldown <= 0) {
            rallyCooldown = 40;
            for (FighterEntity g : guards) if (g.getTarget() == null || !g.getTarget().isAlive()) g.setTarget(target);
        }

        if (retreating()) {
            retreat(target);
            return;
        }

        // Встать за ближайшим к врагу стражником: на линии «враг — стражник», в трёх блоках позади него.
        FighterEntity shield = null;
        double best = Double.MAX_VALUE;
        for (FighterEntity g : guards) {
            double d = g.distanceToSqr(target);
            if (d < best) {
                best = d;
                shield = g;
            }
        }
        if (shield != null && --repath <= 0) {
            repath = 10;
            Vec3 away = shield.position().subtract(target.position()).multiply(1, 0, 1);
            if (away.lengthSqr() < 0.01) away = mob.position().subtract(target.position()).multiply(1, 0, 1);
            Vec3 spot = shield.position().add(away.normalize().scale(3.0));
            mob.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.0);
        }
        // Щит поднят, когда в него могут попасть: враг со стрелковым оружием или подошёл близко.
        boolean threatened = mob.distanceTo(target) < 7 || holdsRanged(target);
        if (threatened && !mob.isUsingItem() && mob.getOffhandItem().is(Items.SHIELD)) {
            mob.startUsingItem(InteractionHand.OFF_HAND);
        } else if (!threatened && mob.isUsingItem()) {
            mob.stopUsingItem();
        }
    }

    private void retreat(LivingEntity target) {
        if (mob.isUsingItem() && !mob.getOffhandItem().is(Items.SHIELD)) return;
        if (!potionUsed) {
            potionUsed = true;
            mob.heal(mob.getMaxHealth() * 0.3f);
            mob.playSound(SoundEvents.WITCH_DRINK, 1f, 0.9f);
            if (mob.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.HEART, mob.getX(), mob.getY() + 2, mob.getZ(), 6, 0.4, 0.3, 0.4, 0);
                com.vladosiwe.lordsoutpost.server.NpcLifecycle.reinforce(sl, mob, 1);
            }
            return;
        }
        Outpost o = mob.outpost();
        BlockPos home = o == null ? mob.blockPosition() : o.marker(Marker.THRONE);
        Vec3 away = mob.position().subtract(target.position()).multiply(1, 0, 1);
        if (--repath <= 0) {
            repath = 10;
            if (mob.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5) > 9) {
                mob.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 1.15);
            } else if (away.lengthSqr() > 0.01) {
                Vec3 spot = mob.position().add(away.normalize().scale(4));
                mob.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.15);
            }
        }
        if (!mob.isUsingItem() && mob.getOffhandItem().is(Items.SHIELD)) mob.startUsingItem(InteractionHand.OFF_HAND);
    }

    private static boolean holdsRanged(LivingEntity target) {
        var item = target.getMainHandItem().getItem();
        return item instanceof BowItem || item instanceof CrossbowItem
                || (target instanceof Player p && p.isUsingItem() && (p.getUseItem().getItem() instanceof BowItem || p.getUseItem().getItem() instanceof CrossbowItem));
    }
}
