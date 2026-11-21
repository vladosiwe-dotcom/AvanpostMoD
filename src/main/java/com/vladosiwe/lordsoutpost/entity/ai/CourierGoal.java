package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.server.LordMirror;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Житель несёт вещь игроку лично: подходит к нему и вручает (сейчас — Управляющий с Зеркалом Лорда). */
public class CourierGoal extends Goal {
    private final OutpostNpc npc;
    private int repath;

    public CourierGoal(OutpostNpc npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    private ServerPlayer target() {
        if (npc.courierTarget == null || npc.level().isClientSide || npc.level().getGameTime() > npc.courierUntil) return null;
        return npc.level().getPlayerByUUID(npc.courierTarget) instanceof ServerPlayer sp && sp.isAlive() && sp.level() == npc.level()
                && sp.distanceToSqr(npc) < 64 * 64 ? sp : null;
    }

    @Override
    public boolean canUse() {
        return target() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target() != null;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
    }

    @Override
    public void tick() {
        ServerPlayer p = target();
        if (p == null) return;
        npc.getLookControl().setLookAt(p, 30f, 30f);
        if (npc.distanceToSqr(p) <= 2.5 * 2.5) {
            npc.getNavigation().stop();
            npc.courierTarget = null;
            LordMirror.handOver(npc, p);
            return;
        }
        if (--repath <= 0) {
            repath = 20;
            npc.getNavigation().moveTo(p, 0.8);
        }
    }
}
