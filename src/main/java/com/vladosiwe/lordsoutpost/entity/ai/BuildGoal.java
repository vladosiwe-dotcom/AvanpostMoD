package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.Role;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Строитель берёт конкретный шаг чертежа, подходит к площадке, расчищает естественные препятствия
 * и только после этого физически ставит блок. Недоступный шаг освобождается, а не появляется сам собой.
 */
public class BuildGoal extends Goal {
    private final WorkerEntity mob;
    private int claim = -1;
    private BlockPos target = BlockPos.ZERO;
    private int timeout;
    private int cooldown;

    public BuildGoal(WorkerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.role() != Role.BUILDER || mob.caravanId != null || mob.cargoReadyToDeposit()) return false;
        Outpost o = mob.outpost();
        return o != null && (claim >= 0 || ChunkReconciliation.hasWork(o));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        if (o == null || !(mob.level() instanceof ServerLevel level)) return;
        if (claim < 0) {
            if (--cooldown > 0) return;
            claim = ChunkReconciliation.claim(o, level.getGameTime());
            if (claim < 0) {
                cooldown = 10;
                return;
            }
            if (claim >= o.nextPlan().size()) {
                ChunkReconciliation.releaseClaim(o, claim);
                claim = -1;
                cooldown = 20;
                return;
            }
            target = o.world(o.nextPlan().get(claim).pos());
            timeout = 120;
        }

        if (ChunkReconciliation.clearNaturalObstruction(level, o, claim, mob)) {
            timeout = 120;
            return;
        }
        if (ChunkReconciliation.blockedByForeignBlock(level, o, claim)) {
            // Не сносим сундуки, декор и постройки игрока ради автоматического чертежа.
            ChunkReconciliation.releaseClaim(o, claim);
            claim = -1;
            cooldown = 40;
            return;
        }

        mob.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
        double dx = mob.getX() - (target.getX() + 0.5);
        double dz = mob.getZ() - (target.getZ() + 0.5);
        if (dx * dx + dz * dz > 3.5 * 3.5) {
            if (--timeout <= 0) {
                ChunkReconciliation.releaseClaim(o, claim);
                claim = -1;
                cooldown = 40;
                mob.getNavigation().stop();
                return;
            }
            if (mob.getNavigation().isDone() || mob.tickCount % 20 == 0) {
                mob.getNavigation().moveTo(target.getX() + 0.5, o.center.getY() + 1, target.getZ() + 0.5, 1.0);
            }
            return;
        }

        mob.getNavigation().stop();
        mob.swing(InteractionHand.MAIN_HAND);
        ChunkReconciliation.place(level, o, claim, true);
        claim = -1;
        cooldown = 8;
    }

    @Override
    public void stop() {
        ChunkReconciliation.releaseClaim(mob.outpost(), claim);
        claim = -1;
        mob.getNavigation().stop();
    }
}
