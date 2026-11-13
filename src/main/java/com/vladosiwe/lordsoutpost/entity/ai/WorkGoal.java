package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;

import java.util.EnumSet;
import java.util.List;

/**
 * Рабочий день жителя: дойти до рабочего места, поработать, отнести добычу на склад; ночью — спать.
 * Сколько ресурса при этом произведено, считает симуляция; здесь — то, что видит игрок.
 * Исключение — охотник: добытые им животные дают склад настоящую прибавку.
 */
public class WorkGoal extends Goal {
    private static final int GO = 0, WORK = 1, CARRY = 2, PAUSE = 3;
    private final WorkerEntity mob;
    private int phase = GO;
    private int timer;
    private int cycle;
    private BlockPos spot;

    public WorkGoal(WorkerEntity mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (mob.level().isClientSide || mob.caravanId != null) return false;
        Outpost o = mob.outpost();
        return o != null && o.state == OutpostState.OWNED;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Outpost o = mob.outpost();
        Resident r = mob.resident();
        if (o == null || r == null || !(mob.level() instanceof ServerLevel level)) return;
        Role role = r.role;
        if (level.isNight() && role != Role.BUILDER) {
            List<BlockPos> beds = o.markers(Marker.HOUSING);
            BlockPos bed = beds.isEmpty() ? o.marker(Marker.YARD) : beds.get(Math.floorMod(r.id.hashCode(), beds.size()));
            mob.approach(bed, 1.0, 2.0, 400);
            phase = GO;
            spot = null;
            return;
        }
        switch (phase) {
            case GO -> {
                if (spot == null) spot = workplace(level, o, role);
                if (role == Role.HUNTER) hunt(o);
                if (mob.approach(spot, 1.0, role == Role.HERDER ? 4.5 : 2.5, 400)) {
                    phase = WORK;
                    timer = 140 + mob.getRandom().nextInt(120);
                }
            }
            case WORK -> {
                if (role == Role.HUNTER) hunt(o);
                if (--timer % 25 == 0) {
                    SoundEvent sound = workSound(role);
                    if (sound != null) {
                        mob.swing(InteractionHand.MAIN_HAND);
                        mob.playSound(sound, 0.5f, 0.9f + mob.getRandom().nextFloat() * 0.2f);
                    }
                }
                if (timer <= 0) {
                    boolean carries = role == Role.LUMBERJACK || role == Role.MINER || role == Role.HUNTER || role == Role.HERDER;
                    phase = carries ? CARRY : PAUSE;
                    timer = 40;
                    spot = null;
                    cycle++;
                }
            }
            case CARRY -> {
                if (mob.approach(o.marker(Marker.STORAGE), 1.0, 2.5, 400)) {
                    phase = PAUSE;
                    timer = 40;
                }
            }
            default -> {
                if (--timer <= 0) phase = GO;
            }
        }
    }

    private BlockPos workplace(ServerLevel level, Outpost o, Role role) {
        return switch (role) {
            case LUMBERJACK -> o.marker(Marker.WOODYARD);
            case MINER -> o.marker(Marker.MINE);
            case SMITH -> o.design().markers(Marker.SMITHY).isEmpty() ? o.marker(Marker.STORAGE) : o.marker(Marker.SMITHY);
            case MAGE -> o.design().markers(Marker.MAGE).isEmpty() ? o.marker(Marker.THRONE) : o.marker(Marker.MAGE);
            case HERDER -> o.marker(Marker.PEN);
            case HUNTER -> {
                List<BlockPos> outer = o.markers(Marker.OUTER_PATROL);
                BlockPos p = outer.get(mob.getRandom().nextInt(outer.size()));
                yield NpcLifecycle.groundAt(level, o, p.getX(), p.getZ());
            }
            case STEWARD -> switch (cycle % 3) {
                case 0 -> o.marker(Marker.THRONE);
                case 1 -> o.marker(Marker.BOARD);
                default -> o.marker(Marker.YARD);
            };
            case CARAVANEER -> {
                List<BlockPos> yard = o.markers(Marker.YARD);
                yield yard.get(yard.size() - 1);
            }
            default -> o.marker(Marker.YARD);
        };
    }

    /** Охотник бьёт дичь за стенами; животных в загоне не трогает. */
    private void hunt(Outpost o) {
        if (mob.tickCount % 40 != 0) return;
        Animal prey = null;
        double best = 14 * 14;
        for (Animal a : mob.level().getEntitiesOfClass(Animal.class, mob.getBoundingBox().inflate(14, 5, 14))) {
            if (!a.isAlive() || a.isBaby() || o.insideWalls(a.blockPosition()) || a.isLeashed() || a.hasCustomName()
                    || a.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG) || !mob.hasLineOfSight(a)) continue;
            if (a instanceof net.minecraft.world.entity.TamableAnimal t && t.isTame()) continue;
            if (a instanceof net.minecraft.world.entity.animal.horse.AbstractHorse h && h.isTamed()) continue;
            double d = mob.distanceToSqr(a);
            if (d < best) {
                best = d;
                prey = a;
            }
        }
        if (prey == null) return;
        mob.getLookControl().setLookAt(prey);
        mob.swing(InteractionHand.MAIN_HAND);
        mob.playSound(SoundEvents.ARROW_SHOOT, 0.8f, 1.1f);
        prey.getPersistentData().putBoolean(NpcLifecycle.HUNTED_TAG, true);
        prey.hurt(mob.damageSources().mobAttack(mob), 7f);
    }

    private static SoundEvent workSound(Role role) {
        return switch (role) {
            case LUMBERJACK -> SoundEvents.AXE_STRIP;
            case MINER -> SoundEvents.STONE_HIT;
            case SMITH -> SoundEvents.SMITHING_TABLE_USE;
            case MAGE -> SoundEvents.BOOK_PAGE_TURN;
            case HERDER -> SoundEvents.GRASS_STEP;
            default -> null;
        };
    }
}
