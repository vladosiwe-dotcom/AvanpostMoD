package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** При тревоге мирные жители бегут в донжон и ждут там конца боя. */
public class ShelterGoal extends Goal {
    private final WorkerEntity mob;

    public ShelterGoal(WorkerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    /** Моб, который охотится за жителем (последний замеченный), и когда его видели. */
    @org.jetbrains.annotations.Nullable private net.minecraft.world.entity.Mob threat;
    private long threatSeenAt = Long.MIN_VALUE / 2;
    /** Сколько раз за последние 2 минуты пришлось бросать работу из-за мобов. */
    private int harassed;
    private long harassWindow;

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.caravanId != null) return false;
        Outpost o = mob.outpost();
        if (o == null) return false;
        if (mobThreat(o)) return true;
        if (o.raid != null && o.raid.preparing(mob.level().getGameTime())) return true;
        if (o.alarmUntil <= mob.level().getGameTime()) return false;
        // Прятаться — только когда враг рядом; иначе жители работают и во время набега.
        if (mob.tickCount % 20 == 0 || danger) {
            danger = !mob.level().getEntitiesOfClass(com.vladosiwe.lordsoutpost.entity.FighterEntity.class, mob.getBoundingBox().inflate(32, 12, 32),
                    f -> f.isAlive() && f.hostileFaction()).isEmpty();
        }
        return danger;
    }

    private boolean danger;

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Житель заметил, что за ним гонится моб: бежит в форт и ждёт, пока всё стихнет (5 секунд без погони), потом
     * возвращается к работе. Если мобы мешают часто (второй раз за 2 минуты), зовёт ближайшего стражника на помощь.
     */
    private boolean mobThreat(Outpost o) {
        long now = mob.level().getGameTime();
        if (mob.tickCount % 10 == 0) {
            net.minecraft.world.entity.Mob found = null;
            for (var m : mob.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, mob.getBoundingBox().inflate(16, 6, 16),
                    m -> m.isAlive() && m.getTarget() == mob)) {
                found = m;
                break;
            }
            if (found != null) {
                if (now - threatSeenAt > 200) onNewThreat(o, found, now);
                threat = found;
                threatSeenAt = now;
            }
        }
        return now - threatSeenAt < 100;
    }

    private void onNewThreat(Outpost o, net.minecraft.world.entity.Mob m, long now) {
        if (now - harassWindow > 2400) {
            harassWindow = now;
            harassed = 0;
        }
        harassed++;
        if (harassed < 2) return;
        com.vladosiwe.lordsoutpost.entity.FighterEntity guard = null;
        double best = Double.MAX_VALUE;
        for (var f : mob.level().getEntitiesOfClass(com.vladosiwe.lordsoutpost.entity.FighterEntity.class, mob.getBoundingBox().inflate(64, 16, 64),
                f -> f.isAlive() && !f.hostileFaction() && f.outpostId() == o.id && f.getTarget() == null)) {
            double d = f.distanceToSqr(mob);
            if (d < best) {
                best = d;
                guard = f;
            }
        }
        if (guard != null) {
            guard.setTarget(m);
            mob.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1f, 1.1f);
        }
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        if (o == null) return;
        if (o.raid != null && o.raid.preparing(mob.level().getGameTime())) {
            // Набег: по домам или в донжон Лорда (чётные — в донжон, нечётные — в ближайший дом).
            if (shelter == null || mob.tickCount % 200 == 0) shelter = shelterSpot(o);
            mob.approach(shelter, 1.25, 1.8, 400);
            return;
        }
        shelter = null;
        mob.approach(o.marker(Marker.YARD), 1.35, 3, 300);
    }

    private net.minecraft.core.BlockPos shelter;

    private net.minecraft.core.BlockPos shelterSpot(Outpost o) {
        var houses = o.markers(Marker.HOUSING);
        if ((mob.getId() & 1) == 0 || houses.isEmpty()) return o.marker(Marker.THRONE);
        return houses.stream().min(java.util.Comparator.comparingDouble(p -> p.distSqr(mob.blockPosition()))).orElse(o.marker(Marker.THRONE));
    }
}
