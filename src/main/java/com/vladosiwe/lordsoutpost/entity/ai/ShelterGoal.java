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
        return o != null && o.alarmUntil > mob.level().getGameTime();
    }

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
