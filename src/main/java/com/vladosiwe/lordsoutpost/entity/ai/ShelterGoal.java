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

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.caravanId != null) return false;
        Outpost o = mob.outpost();
        if (o == null || o.alarmUntil <= mob.level().getGameTime()) return false;
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

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        if (o != null) mob.approach(o.marker(Marker.THRONE), 1.35, 3, 300);
    }
}
