package com.vladosiwe.lordsoutpost.entity;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostMember;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.protection.Access;
import com.vladosiwe.lordsoutpost.server.NpcLifecycle;
import com.vladosiwe.lordsoutpost.server.Roads;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Общая основа жителей и бойцов. Сущность — лишь отражение записи {@link Resident}: она не сохраняется в чанк,
 * а при выгрузке возвращает своё состояние в запись. Поэтому у одного жителя не бывает двух тел и никто не теряется.
 */
public abstract class OutpostNpc extends PathfinderMob implements OutpostMember {
    private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> HOSTILE = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> STYLE = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.INT);
    /** Что делает житель руками (кузнец — изделие) и прогресс 0..1; для подсказки и полоски над головой. */
    public static final EntityDataAccessor<net.minecraft.world.item.ItemStack> CRAFT_ITEM = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.ITEM_STACK);
    public static final EntityDataAccessor<Float> CRAFT_PROGRESS = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.FLOAT);
    /** Стрелы стрелка в колчане (-1 — не считаются: мародёры). */
    public static final EntityDataAccessor<Integer> AMMO = SynchedEntityData.defineId(OutpostNpc.class, EntityDataSerializers.INT);
    private static final ResourceLocation ROAD_MODIFIER = LordsOutpost.id("road");

    protected long outpostId;
    @Nullable protected UUID residentId;
    /** Для налётчиков: аванпост, на который идёт набег. */
    public long raidTarget;
    /** Для участников каравана. */
    @Nullable public UUID caravanId;
    /** Счётчик для запасного телепорта, когда путь не находится. */
    public int stuckTicks;

    protected OutpostNpc(EntityType<? extends OutpostNpc> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setPathfindingMalus(PathType.DOOR_OPEN, 0.0f);
        setPathfindingMalus(PathType.WATER, 4.0f);
        // Открытый люк — это шахтный ствол: обходить, а не проваливаться.
        setPathfindingMalus(PathType.TRAPDOOR, -1.0f);
        if (getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }

    /** Навигация с калитками: закрытая калитка своего форта считается дверью, которую можно открыть. */
    @Override
    protected net.minecraft.world.entity.ai.navigation.PathNavigation createNavigation(Level level) {
        return new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(this, level) {
            @Override
            protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int maxVisitedNodes) {
                nodeEvaluator = new com.vladosiwe.lordsoutpost.entity.ai.GateNodeEvaluator();
                nodeEvaluator.setCanPassDoors(true);
                return new net.minecraft.world.level.pathfinder.PathFinder(nodeEvaluator, maxVisitedNodes);
            }
        };
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE, Role.SHIELDMAN.ordinal());
        builder.define(HOSTILE, true);
        builder.define(STYLE, 0);
        builder.define(CRAFT_ITEM, net.minecraft.world.item.ItemStack.EMPTY);
        builder.define(CRAFT_PROGRESS, 0f);
        builder.define(AMMO, -1);
    }

    public void showCraft(net.minecraft.world.item.ItemStack item, float progress) {
        if (!net.minecraft.world.item.ItemStack.matches(entityData.get(CRAFT_ITEM), item)) entityData.set(CRAFT_ITEM, item.copy());
        entityData.set(CRAFT_PROGRESS, Math.max(0f, Math.min(1f, progress)));
    }

    public net.minecraft.world.item.ItemStack craftItem() {
        return entityData.get(CRAFT_ITEM);
    }

    public float craftProgress() {
        return entityData.get(CRAFT_PROGRESS);
    }

    public int ammo() {
        return entityData.get(AMMO);
    }

    // ============================== Привязка к записи ==============================

    /** Превращает сущность в отражение жителя: роль, сторона, имя, снаряжение, здоровье. */
    public void bind(Outpost o, Resident r) {
        outpostId = o.id;
        residentId = r.id;
        entityData.set(ROLE, r.role.ordinal());
        entityData.set(HOSTILE, o.state != OutpostState.OWNED);
        entityData.set(STYLE, o.palette.ordinal());
        equip(o, r);
        setHealth(Math.max(1f, getMaxHealth() * r.health));
        refreshName(o, r);
        r.entity = getUUID();
    }

    /** Налётчик или участник засады: не житель, принадлежит стороне мародёров. */
    public void bindRaider(long source, long target, Role role, int gear) {
        outpostId = source;
        residentId = null;
        raidTarget = target;
        entityData.set(ROLE, role.ordinal());
        entityData.set(HOSTILE, true);
        Resident fake = new Resident(UUID.randomUUID(), role, "");
        fake.gear = gear;
        equip(null, fake);
        setHealth(getMaxHealth());
        setCustomName(Component.translatable("lordsoutpost.npc.raider", role.label()).withStyle(ChatFormatting.RED));
    }

    protected void refreshName(Outpost o, Resident r) {
        ChatFormatting color = o.state == OutpostState.OWNED ? ChatFormatting.GREEN : ChatFormatting.RED;
        Component role = o.state == OutpostState.OWNED || r.role == Role.CAPTAIN ? r.role.label() : Component.translatable("lordsoutpost.npc.marauder", r.role.label());
        setCustomName(Component.literal(r.name + " ").append(Component.literal("[").append(role).append("]").withStyle(color)));
    }

    /** Снаряжение и характеристики по роли и уровню снаряжения. */
    protected abstract void equip(@Nullable Outpost o, Resident r);

    protected void noDrops() {
        for (EquipmentSlot slot : EquipmentSlot.values()) setDropChance(slot, 0f);
    }

    /** Кому житель сейчас несёт вещь лично (см. CourierGoal); не сохраняется. */
    public java.util.UUID courierTarget;
    public long courierUntil;

    @Override
    public long outpostId() {
        return outpostId;
    }

    @Nullable
    public UUID residentId() {
        return residentId;
    }

    public Role role() {
        return Role.VALUES[Math.max(0, Math.min(Role.VALUES.length - 1, entityData.get(ROLE)))];
    }

    public boolean hostileFaction() {
        return entityData.get(HOSTILE);
    }

    /** Палитра форта: по ней клиент выбирает облик жителя. */
    public int style() {
        return entityData.get(STYLE);
    }

    @Nullable
    public Outpost outpost() {
        if (!(level() instanceof ServerLevel sl) || outpostId == 0) return null;
        return OutpostSavedData.get(sl).byId(outpostId);
    }

    @Nullable
    public Resident resident() {
        Outpost o = outpost();
        return o == null || residentId == null ? null : o.resident(residentId);
    }

    // ============================== Жизненный цикл ==============================

    /** Сущность не пишется в чанк: источник правды — запись жителя. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void checkDespawn() {
    }

    @Override
    protected boolean isImmobile() {
        return super.isImmobile() || isSleeping();
    }

    /**
     * Состояние сущности уже вернулось в запись жителя (ноша на складе, здоровье записано).
     * Такое тело больше не представляет жителя: если его чанк снова станет доступен, оно уходит само.
     */
    private boolean detached;

    @Override
    public void remove(RemovalReason reason) {
        // Иначе кровать останется «занятой» навсегда.
        if (!level().isClientSide && isSleeping()) stopSleeping();
        super.remove(reason);
    }

    /**
     * Сущность перестала отслеживаться миром. Выгрузка чанка вызывает {@code setRemoved(UNLOADED_TO_CHUNK)},
     * который нельзя переопределить и который не проходит через {@link #remove}, поэтому запись обновляется здесь:
     * этот хук срабатывает и при выгрузке чанка, и при понижении его статуса, и при discard().
     */
    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level().isClientSide || detached || getRemovalReason() == RemovalReason.KILLED || isDeadOrDying()) return;
        detach();
    }

    /** Возвращает состояние в запись ровно один раз. */
    public void detach() {
        if (detached || level().isClientSide) return;
        detached = true;
        if (isSleeping()) stopSleeping();
        NpcLifecycle.onUnloaded(this);
    }

    public boolean detached() {
        return detached;
    }

    @Override
    public void tick() {
        // Тело, чьё состояние уже вернулось в запись, не должно снова работать рядом с новым телом того же жителя.
        if (detached && !level().isClientSide) {
            discard();
            return;
        }
        // Житель не спит вечно: днём, без кровати под собой или если его ИИ сейчас занят другим — просыпается.
        if (!level().isClientSide && isSleeping() && tickCount % 20 == 0) {
            boolean bed = getSleepingPos().map(p -> level().getBlockState(p).getBlock() instanceof net.minecraft.world.level.block.BedBlock).orElse(false);
            if (level().isDay() || !bed) stopSleeping();
        }
        super.tick();
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (!level().isClientSide) NpcLifecycle.onDeath(this, source);
    }

    @Override
    protected boolean shouldDropLoot() {
        return hostileFaction();
    }

    @Override
    protected int getBaseExperienceReward() {
        return hostileFaction() ? 8 : 0;
    }

    // ============================== Свои и чужие ==============================

    /** Кого этот боец никогда не тронет. */
    public boolean isFriend(LivingEntity other) {
        if (other == this) return true;
        if (other instanceof OutpostNpc npc) {
            if (npc.outpostId == outpostId && npc.hostileFaction() == hostileFaction()) return true;
            if (hostileFaction() && npc.hostileFaction()) return true;
            if (!hostileFaction() && !npc.hostileFaction()) {
                Outpost o = outpost();
                Relation rel = o == null ? null : o.relations.get(npc.outpostId);
                return rel != null && rel.pact != Pact.NONE;
            }
            // Гарнизон дикого форта не трогает караваны того, с кем у Капитана договор.
            Outpost wild = hostileFaction() ? outpost() : npc.outpost();
            long otherId = hostileFaction() ? npc.outpostId : outpostId;
            if (wild != null && raidTarget == 0 && npc.raidTarget == 0) {
                Relation rel = wild.relations.get(otherId);
                return rel != null && rel.pact != Pact.NONE;
            }
            return false;
        }
        if (other instanceof Player player) {
            if (player.isCreative() || player.isSpectator()) return true;
            if (hostileFaction()) return false;
            Outpost o = outpost();
            if (o == null || !(level() instanceof ServerLevel sl)) return false;
            return Access.level(OutpostSavedData.get(sl), o, player.getUUID()).atLeast(AccessLevel.ALLY);
        }
        // Пиромант Iron's Spells своего форта — союзник: его не трогают, и его заклинания не ранят своих.
        if (!hostileFaction() && com.vladosiwe.lordsoutpost.compat.Pyromancer.ofOutpost(other, outpostId)) return true;
        return other.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG) && !hostileFaction();
    }

    /** Кого этот боец атакует сам, без повода. */
    public boolean isEnemy(LivingEntity other) {
        if (!other.isAlive() || isFriend(other)) return false;
        if (other instanceof OutpostNpc npc) return npc.hostileFaction() != hostileFaction();
        if (other instanceof Player player) {
            if (hostileFaction()) return true;
            Outpost o = outpost();
            if (o == null || !(level() instanceof ServerLevel sl)) return false;
            return o.contains(player.blockPosition())
                    && Access.level(OutpostSavedData.get(sl), o, player.getUUID()) == AccessLevel.HOSTILE;
        }
        if (hostileFaction()) return other.getPersistentData().contains(NpcLifecycle.CARAVAN_TAG);
        return other instanceof Enemy && other instanceof Mob;
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !isFriend(target) && super.canAttack(target);
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        if (target != null && isFriend(target)) return;
        super.setTarget(target);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // Свои не ранят своих: ни Лорд случайным ударом, ни стрела товарища.
        if (!level().isClientSide && source.getEntity() instanceof LivingEntity attacker && attacker != this && isFriend(attacker)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean isAlliedTo(net.minecraft.world.entity.Entity other) {
        return (other instanceof LivingEntity living && isFriend(living)) || super.isAlliedTo(other);
    }

    // ============================== Дороги и застревание ==============================

    /**
     * Подъём и спуск по лестнице по пути: следующий узел выше — лезет вверх (как игрок с прыжком), ниже — спускается,
     * держась оси лестницы; у верхнего края шагает на площадку к следующему узлу.
     */
    private void climbLadder() {
        if (!onClimbable()) return;
        var path = getNavigation().getPath();
        if (path == null || path.isDone()) return;
        var node = path.getNextNode();
        double cx = node.x + 0.5, cz = node.z + 0.5;
        double dy = node.y - getY();
        double hx = (cx - getX()) * 0.25, hz = (cz - getZ()) * 0.25;
        if (dy > 0.1) setDeltaMovement(hx, 0.2, hz);
        else if (dy < -0.5) setDeltaMovement(hx, -0.15, hz);
        else return;
        fallDistance = 0;
        getLookControl().setLookAt(cx, getEyeY(), cz);
    }

    @Override
    public void aiStep() {
        if (!level().isClientSide) climbLadder();
        super.aiStep();
        if (!level().isClientSide && tickCount % 5 == 0) {
            AttributeInstance speed = getAttribute(Attributes.MOVEMENT_SPEED);
            if (speed != null) {
                boolean road = Roads.onRoad(this);
                if (road != speed.hasModifier(ROAD_MODIFIER)) {
                    speed.removeModifier(ROAD_MODIFIER);
                    if (road) speed.addTransientModifier(new AttributeModifier(ROAD_MODIFIER,
                            OutpostConfig.get(OutpostConfig.ROAD_SPEED, 1.35) - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                }
            }
        }
    }

    /**
     * Идёт к точке; если путь не находится слишком долго, переносится к ней.
     * Нужно для постов на башнях и для редких случаев, когда поиск пути сдаётся.
     *
     * @return true, когда сущность на месте
     */
    private int repathAt;
    @Nullable private BlockPos repathGoal;
    private boolean repathReach;

    /**
     * Запрос пути с ограничением частоты. Поиск пути — самая дорогая часть ИИ жителей: раньше при недостижимой цели
     * он повторялся каждый тик. Теперь: путь к той же цели обновляется не чаще раза в 2 с, а после неудачи
     * (пути нет или он обрывается, не дойдя) — с паузой 1,5–2,5 с.
     * Возвращает true, если есть путь, который ведёт к цели.
     */
    public boolean pathTo(double x, double y, double z, double speed) {
        BlockPos goal = BlockPos.containing(x, y, z);
        boolean same = goal.equals(repathGoal);
        boolean moving = !getNavigation().isDone();
        if (same && tickCount < repathAt) return moving && repathReach;
        // Дошёл по прошлому пути, но ещё не на месте — можно уточнить путь чуть раньше обычного.
        var path = getNavigation().createPath(x, y, z, 0);
        repathGoal = goal;
        repathReach = path != null && path.canReach();
        if (path != null) getNavigation().moveTo(path, speed);
        repathAt = tickCount + (repathReach ? 40 : 30 + random.nextInt(20));
        return repathReach;
    }

    /** Сбросить ограничение (цель сменилась по смыслу, например открылась калитка). */
    public void repathNow() {
        repathAt = 0;
    }

    /**
     * Подъём по лестнице на пост (башня, вышка, боевой ход): обычный поиск пути лестниц не знает.
     * Житель идёт к основанию лестницы рядом с постом, лезет вверх и сходит на площадку — ногами, без переноса.
     */
    private boolean climbTo(BlockPos target) {
        if (target.getY() - getY() < 1.5) return false;
        double tx = target.getX() + 0.5, tz = target.getZ() + 0.5;
        if (Math.hypot(tx - getX(), tz - getZ()) > 5) return false;
        BlockPos ladder = ladderNear(target);
        if (ladder == null) return false;
        double lx = ladder.getX() + 0.5, lz = ladder.getZ() + 0.5;
        if (onClimbable()) {
            getNavigation().stop();
            if (getY() >= target.getY() - 0.3) {
                // Наверху: шаг с лестницы на площадку.
                setDeltaMovement((tx - getX()) * 0.15, 0.08, (tz - getZ()) * 0.15);
            } else {
                setDeltaMovement((lx - getX()) * 0.2, 0.2, (lz - getZ()) * 0.2);
            }
            getLookControl().setLookAt(lx, getY() + 1, lz);
            return true;
        }
        double hd = Math.hypot(lx - getX(), lz - getZ());
        if (hd < 1.2) {
            getNavigation().stop();
            setDeltaMovement((lx - getX()) * 0.25, getDeltaMovement().y, (lz - getZ()) * 0.25);
        } else {
            pathTo(lx, ladder.getY(), lz, 1.0);
        }
        return true;
    }

    @Nullable private BlockPos ladderTarget, ladderFound;
    private int ladderAt;

    /**
     * Лестница у поста. Поиск — перебор сотен блоков, поэтому результат (и отсутствие лестницы) кэшируется на секунду
     * для той же цели: раньше скотовод, стоя у высокой цели (тюк сена на крыше, животное на холме), перебирал их каждый тик.
     */
    @Nullable
    private BlockPos ladderNear(BlockPos target) {
        if (target.equals(ladderTarget) && tickCount < ladderAt
                && (ladderFound == null || level().getBlockState(ladderFound).is(net.minecraft.tags.BlockTags.CLIMBABLE)))
            return ladderFound;
        BlockPos ladder = null;
        double best = Double.MAX_VALUE;
        int y0 = Mth.floor(getY()) - 1;
        for (BlockPos p : BlockPos.betweenClosed(target.getX() - 3, y0, target.getZ() - 3, target.getX() + 3, target.getY(), target.getZ() + 3)) {
            if (!level().getBlockState(p).is(net.minecraft.tags.BlockTags.CLIMBABLE)) continue;
            double d = p.distToCenterSqr(getX(), getY(), getZ());
            if (d < best) {
                best = d;
                ladder = p.immutable();
            }
        }
        ladderTarget = target.immutable();
        ladderFound = ladder;
        ladderAt = tickCount + (ladder == null ? 40 : 20);
        return ladder;
    }

    @Nullable private BlockPos progressGoal;
    private double progressBest;

    /**
     * Идёт к точке ногами. Перенос — только крайняя мера: если житель {@code patience} тиков не приближается
     * к цели (пути нет, упёрся, застрял в яме). Пока он идёт и расстояние сокращается, терпение не тратится —
     * раньше счётчик рос каждый тик, и любой долгий, но нормальный переход заканчивался телепортом.
     */
    public boolean approach(BlockPos pos, double speed, double reach, int patience) {
        if (!level().isClientSide && distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > reach * reach) {
            BlockPos via = gateWaypoint(pos);
            if (via != null) {
                approachDirect(via, speed, 1.2, patience);
                return false;
            }
        }
        return approachDirect(pos, speed, reach, patience);
    }

    /**
     * Цель по другую сторону стены форта: точка у ближайших ворот (сначала с этой стороны, потом с той), иначе null.
     * Поиск пути видит лишь ограниченную область и через весь двор в обход стен ворот не находит — раньше житель
     * упирался в стену в сторону цели и, застряв, переносился. Теперь он идёт через ворота ногами.
     */
    @Nullable
    private BlockPos gateWaypoint(BlockPos target) {
        Outpost o = outpost();
        if (o == null) return null;
        boolean here = o.insideWalls(blockPosition()), there = o.insideWalls(target);
        if (here == there) return null;
        BlockPos gate = null;
        double best = Double.MAX_VALUE;
        for (BlockPos rel : o.design().markers(com.vladosiwe.lordsoutpost.blueprint.Marker.GATE)) {
            BlockPos g = o.world(rel);
            double d = g.distSqr(blockPosition()) + g.distSqr(target);
            if (d < best) {
                best = d;
                gate = g;
            }
        }
        if (gate == null) return null;
        double dx = gate.getX() - o.center.getX(), dz = gate.getZ() - o.center.getZ();
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        int ix = (int) Math.round(dx / len * 3), iz = (int) Math.round(dz / len * 3);
        BlockPos inner = gate.offset(-ix, 0, -iz), outer = gate.offset(ix, 0, iz);
        BlockPos near = here ? inner : outer, far = here ? outer : inner;
        // Уже у ворот со своей стороны или в самом проходе (ближе к той стороне, чем точка этой) — проходим насквозь.
        double toNear = blockPosition().distSqr(near), toFar = blockPosition().distSqr(far), span = near.distSqr(far);
        return toNear <= 2.5 * 2.5 || toFar < span ? far : near;
    }

    private boolean approachDirect(BlockPos pos, double speed, double reach, int patience) {
        double dist = distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        if (dist <= reach * reach) {
            stuckTicks = 0;
            progressGoal = null;
            if (!getNavigation().isDone()) getNavigation().stop();
            return true;
        }
        double d = Math.sqrt(dist);
        if (!pos.equals(progressGoal)) {
            progressGoal = pos.immutable();
            progressBest = d;
            stuckTicks = 0;
        } else if (d < progressBest - 0.3) {
            progressBest = d;
            stuckTicks = Math.max(0, stuckTicks - 10);
        }
        if (!level().isClientSide && climbTo(pos)) {
            stuckTicks = Math.max(0, stuckTicks - 1);
            return false;
        }
        if (getNavigation().isDone() || tickCount % 40 == 0) {
            int before = repathAt;
            boolean ok = pathTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
            // Штраф за неудачу — только за новый поиск, а не за каждый тик ожидания.
            if (!ok && repathAt != before) stuckTicks += 20;
        }
        // Застрял: сперва попробовать выбраться самому (прыжок, новый путь), перенос — после полного терпения.
        if (++stuckTicks % 60 == 0 && onGround()) {
            getJumpControl().jump();
            repathNow();
        }
        if (stuckTicks > patience) {
            stuckTicks = 0;
            progressBest = d;
            BlockPos safe = safeSpotNear(pos);
            if (safe == null) return false;
            com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.debug("{} застрял по пути к {} — перенос", getName().getString(), pos.toShortString());
            getNavigation().stop();
            teleportTo(safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5);
            return true;
        }
        return false;
    }

    /**
     * Ближайшая к цели клетка, где можно стоять: ноги и голова свободны (или это лестница), под ногами опора.
     * Цель сама может быть блоком (бревно, руда) — переносить прямо в неё нельзя, задохнёшься.
     */
    @Nullable
    public BlockPos safeSpotNear(BlockPos pos) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            if (!standable(p)) continue;
            double d = p.distSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }

    private boolean standable(BlockPos p) {
        var level = level();
        var feet = level.getBlockState(p);
        var head = level.getBlockState(p.above());
        boolean feetFree = feet.is(net.minecraft.tags.BlockTags.CLIMBABLE) || feet.getCollisionShape(level, p).isEmpty();
        boolean headFree = head.is(net.minecraft.tags.BlockTags.CLIMBABLE) || head.getCollisionShape(level, p.above()).isEmpty();
        if (!feetFree || !headFree || !feet.getFluidState().isEmpty()) return false;
        return feet.is(net.minecraft.tags.BlockTags.CLIMBABLE) || level.getBlockState(p.below()).isFaceSturdy(level, p.below(), net.minecraft.core.Direction.UP);
    }
}
