package com.vladosiwe.lordsoutpost.entity.ai;

import com.vladosiwe.lordsoutpost.data.Logistics;
import com.vladosiwe.lordsoutpost.data.MageCraft;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.Storehouse;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.OutpostTask;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.function.Supplier;

/**
 * Работа придворного мага (тело — пиромант Iron's Spells или, без мода, обычный житель). Цикл:
 * выбрать рецепт → зарезервировать ингредиенты на складе → забрать их в сумку → работать у станции
 * (кузня свитков для свитков, иначе стол зачарования башни) → отнести готовое на склад.
 * Защита важнее: при цели, набеге или тревоге работа останавливается и потом продолжается по содержимому сумки.
 * Сумка хранится в записи аванпоста, поэтому выгрузка тела ничего не теряет.
 */
public class MageWorkGoal extends Goal {
    private enum Stage { FETCH, CRAFT, SHELVE, DELIVER }

    private final PathfinderMob mob;
    private final Supplier<Outpost> outpost;
    @Nullable private Stage stage;
    @Nullable private MageCraft.Recipe recipe;
    @Nullable private OutpostTask task;
    private int timer, patience, repathAt;
    private long nextTry;

    public MageWorkGoal(PathfinderMob mob, Supplier<Outpost> outpost) {
        this.mob = mob;
        this.outpost = outpost;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private Resident mage(Outpost o) {
        for (Resident r : o.residents) if (r.role == Role.MAGE && r.active()) return r;
        return null;
    }

    private boolean calm(Outpost o, long now) {
        return mob.getTarget() == null && o.raid == null && now >= o.alarmUntil;
    }

    private boolean workable(Outpost o) {
        return o != null && o.state == OutpostState.OWNED && o.online && o.tier >= 3 && o.hasZone(WorkZone.MAGE_TOWER) && mage(o) != null;
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        Outpost o = outpost.get();
        long now = level.getGameTime();
        if (!workable(o) || now < nextTry || !calm(o, now) || !level.isDay()) return false;
        if (!o.mageSatchel.isEmpty()) return true;
        if (MageCraft.next(level, o, forge(level, o) != null) != null) return true;
        if (!com.vladosiwe.lordsoutpost.data.Shop.restockShelf(level, o).isEmpty()) return true;
        nextTry = now + 400;
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(mob.level() instanceof ServerLevel level)) return false;
        Outpost o = outpost.get();
        return workable(o) && calm(o, level.getGameTime()) && stage != null;
    }

    @Nullable
    private BlockPos forge(ServerLevel level, Outpost o) {
        return MageCraft.forge(level, o.zone(WorkZone.MAGE_TOWER));
    }

    @Override
    public void start() {
        ServerLevel level = (ServerLevel) mob.level();
        Outpost o = outpost.get();
        patience = 1200;
        boolean forge = forge(level, o) != null;
        // Сумка уже собрана под рецепт (работу прервал набег) — сразу к станции.
        recipe = MageCraft.resumable(level, o, forge);
        if (recipe != null) {
            stage = Stage.CRAFT;
            timer = -1;
            return;
        }
        if (!o.mageSatchel.isEmpty()) {
            stage = Stage.DELIVER;
            return;
        }
        recipe = MageCraft.next(level, o, forge);
        Resident r = mage(o);
        if (r == null) {
            stage = null;
            return;
        }
        if (recipe == null) {
            // Делать нечего — выставить на полку готовые магические товары со склада.
            var want = com.vladosiwe.lordsoutpost.data.Shop.restockShelf(level, o);
            task = want.isEmpty() ? null : Logistics.request(level, o, r, OutpostTask.Type.STOCK_SHOP, want, level.getGameTime());
            stage = task == null ? null : Stage.FETCH;
            if (task != null) task.zone = WorkZone.MAGE_TOWER;
            return;
        }
        task = Logistics.request(level, o, r, OutpostTask.Type.MAGE_CRAFT, recipe.inputs(), level.getGameTime());
        stage = task == null ? null : Stage.FETCH;
        if (task != null) task.zone = WorkZone.MAGE_TOWER;
    }

    @Override
    public void stop() {
        Outpost o = outpost.get();
        long now = mob.level().getGameTime();
        // Ингредиенты, ещё не взятые со склада, снова доступны; взятое остаётся в сумке и дождётся мага.
        if (o != null && task != null && task.active()) Logistics.cancel(o, task, "interrupted", now);
        task = null;
        stage = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean walk(BlockPos target, double reach) {
        double d = mob.distanceToSqr(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (d <= reach * reach) {
            mob.getNavigation().stop();
            return true;
        }
        // Поиск пути дорог: не чаще раза в 2 с, а после неудачи — через 2 с, а не каждый тик.
        if (mob.tickCount >= repathAt) {
            boolean ok = mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.9);
            repathAt = mob.tickCount + (ok ? 40 : 40 + mob.getRandom().nextInt(20));
        }
        return false;
    }

    @Override
    public void tick() {
        ServerLevel level = (ServerLevel) mob.level();
        Outpost o = outpost.get();
        long now = level.getGameTime();
        if (o == null || stage == null) return;
        if (--patience <= 0) {
            // Не добрался за минуту: бросить и попробовать позже (без телепортации).
            stop();
            nextTry = now + 1200;
            return;
        }
        BlockPos store = Storehouse.storePos(level, o);
        switch (stage) {
            case FETCH -> {
                if (!walk(store, 2.2)) return;
                Resident r = mage(o);
                if (task == null || r == null || !Logistics.withdraw(level, o, task, o.mageSatchel, r.id, now)) {
                    if (task != null) Logistics.cancel(o, task, "withdraw failed", now);
                    task = null;
                    stage = null;
                    nextTry = now + 600;
                    return;
                }
                mob.swing(InteractionHand.MAIN_HAND);
                stage = recipe == null ? Stage.SHELVE : Stage.CRAFT;
                timer = -1;
                patience = 1200;
            }
            case SHELVE -> {
                if (!walk(o.zone(WorkZone.MAGE_TOWER), 2.5)) return;
                // На полку — ровно взятое под эту задачу; что не поместилось, вернётся на склад с остальной сумкой.
                for (int i = 0; i < o.mageSatchel.getContainerSize(); i++) {
                    ItemStack st = o.mageSatchel.getItem(i);
                    if (!com.vladosiwe.lordsoutpost.data.Shop.magic(st) || task == null || !task.carried.containsKey(st.getItem())) continue;
                    o.mageSatchel.setItem(i, com.vladosiwe.lordsoutpost.data.Shop.shelve(o, st.copy()));
                }
                o.mageSatchel.setChanged();
                if (task != null) {
                    Logistics.complete(o, task, now);
                    task = null;
                }
                mob.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, mob.blockPosition(), SoundEvents.BOOK_PUT, SoundSource.NEUTRAL, 0.7f, 1.0f);
                OutpostSavedData.get(level).setDirty();
                stage = o.mageSatchel.isEmpty() ? null : Stage.DELIVER;
                if (stage == null) nextTry = now + 200;
            }
            case CRAFT -> {
                if (recipe == null) {
                    stage = Stage.DELIVER;
                    return;
                }
                BlockPos station = recipe.forge() ? forge(level, o) : o.zone(WorkZone.MAGE_TOWER);
                if (station == null) {
                    o.zones.report(WorkZone.MAGE_TOWER, "no scroll forge");
                    stage = Stage.DELIVER;
                    return;
                }
                if (timer < 0) {
                    if (!walk(station, 2.5)) return;
                    timer = 100;
                }
                mob.getLookControl().setLookAt(station.getX() + 0.5, station.getY() + 0.5, station.getZ() + 0.5);
                if (--timer > 0) {
                    if (timer % 10 == 0) {
                        mob.swing(InteractionHand.MAIN_HAND);
                        level.sendParticles(recipe.forge() ? ParticleTypes.FLAME : ParticleTypes.ENCHANT,
                                station.getX() + 0.5, station.getY() + 1.1, station.getZ() + 0.5, 6, 0.3, 0.3, 0.3, 0.02);
                    }
                    return;
                }
                // Сначала проверить, что в сумке всё есть, потом списать — ничего не тратится наполовину.
                for (var e : recipe.inputs().entrySet()) {
                    if (o.mageSatchel.countItem(e.getKey()) < e.getValue()) {
                        stage = Stage.DELIVER;
                        return;
                    }
                }
                for (var e : recipe.inputs().entrySet()) take(o, e.getKey(), e.getValue());
                for (ItemStack made : MageCraft.craft(level, recipe, level.random)) {
                    ItemStack left = Logistics.addTo(o.mageSatchel, made);
                    if (!left.isEmpty()) o.storeItems.add(left);
                }
                if (task != null) {
                    Logistics.complete(o, task, now);
                    task = null;
                }
                level.playSound(null, station, recipe.forge() ? SoundEvents.FIRECHARGE_USE : SoundEvents.ENCHANTMENT_TABLE_USE,
                        SoundSource.NEUTRAL, 0.7f, 1.0f);
                o.zones.clearProblem(WorkZone.MAGE_TOWER);
                o.log(now, "lordsoutpost.event.mage_crafted", recipe.id());
                OutpostSavedData.get(level).setDirty();
                stage = Stage.DELIVER;
                patience = 1200;
            }
            case DELIVER -> {
                if (!walk(store, 2.2)) return;
                Storehouse.deposit(level, o, o.mageSatchel);
                OutpostSavedData.get(level).setDirty();
                mob.swing(InteractionHand.MAIN_HAND);
                stage = null;
                nextTry = now + 200;
            }
        }
    }

    private static void take(Outpost o, net.minecraft.world.item.Item item, int count) {
        for (int i = 0; i < o.mageSatchel.getContainerSize() && count > 0; i++) {
            ItemStack st = o.mageSatchel.getItem(i);
            if (!st.is(item)) continue;
            int n = Math.min(count, st.getCount());
            st.shrink(n);
            count -= n;
        }
        o.mageSatchel.setChanged();
    }
}
