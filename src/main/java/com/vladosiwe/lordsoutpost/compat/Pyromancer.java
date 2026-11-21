package com.vladosiwe.lordsoutpost.compat;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.WorkZone;
import com.vladosiwe.lordsoutpost.server.Notify;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;

/**
 * Пиромант Iron's Spells 'n Spellbooks — особый нейтральный союзник, а не житель. Он приходит в форт третьего тира,
 * когда стоит башня мага, живёт у неё и защищает поселение настоящими заклинаниями мода (его собственный боевой ИИ),
 * а игроки могут торговать с ним, как в самом моде. Жёсткой зависимости нет: без мода пиромант просто не появляется.
 *
 * Сущность — настоящий моб мода и сохраняется в чанке сама; запись аванпоста помнит только его id и срок,
 * после которого после гибели придёт новый. Поэтому дублей при выгрузке и перезапуске нет.
 */
public final class Pyromancer {
    public static final String TAG = "lordsoutpost_pyromancer";
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(MagicLoot.IRONS, "pyromancer");
    /** Через сколько игровых дней после гибели приходит новый пиромант. */
    private static final long REJOIN = 3 * 24000L;
    /** Дальше этого от башни пиромант без боя не уходит. */
    private static final double LEASH = 20;

    private Pyromancer() {}

    @Nullable
    private static EntityType<?> type() {
        if (!MagicLoot.ironsLoaded() || !BuiltInRegistries.ENTITY_TYPE.containsKey(ID)) return null;
        return BuiltInRegistries.ENTITY_TYPE.get(ID);
    }

    public static boolean available() {
        return type() != null;
    }

    /** Придворный маг этого форта живёт в теле пироманта (мод есть, форт третьего тира, башня стоит). */
    public static boolean mode(Outpost o) {
        return o.tier >= 3 && o.hasZone(WorkZone.MAGE_TOWER) && available();
    }

    @Nullable
    private static com.vladosiwe.lordsoutpost.outpost.Resident mage(Outpost o) {
        for (var r : o.residents) if (r.role == com.vladosiwe.lordsoutpost.outpost.Role.MAGE && r.active()) return r;
        return null;
    }

    /** Пиромант этого аванпоста. */
    public static boolean ofOutpost(Entity e, long outpostId) {
        return e.getPersistentData().contains(TAG) && e.getPersistentData().getLong(TAG) == outpostId;
    }

    /** Раз в секунду, пока чанки форта загружены. */
    public static void tick(ServerLevel level, Outpost o, long now) {
        if (o.state != OutpostState.OWNED || !mode(o)) return;
        EntityType<?> type = type();
        if (type == null) return;
        var mage = mage(o);
        BlockPos tower = o.zone(WorkZone.MAGE_TOWER);
        Entity e = o.pyromancer == null ? null : level.getEntity(o.pyromancer);
        if (e != null && !e.isAlive()) e = null;
        if (e == null && o.pyromancer != null) {
            // Сущность сохраняется в чанке у башни; пока форт только загрузился, она может ещё не появиться.
            if (now - o.onlineSince < 200 || !level.isLoaded(tower)) return;
            Mob near = findNear(level, o, tower);
            if (near != null) {
                o.pyromancer = near.getUUID();
                e = near;
            } else {
                // Тела нет ни у башни, ни в мире: пиромант считается ушедшим, придёт новый после срока.
                o.pyromancer = null;
                o.pyromancerRejoinAt = now + REJOIN;
                OutpostSavedData.get(level).setDirty();
                return;
            }
        }
        if (mage == null) {
            // Старое сохранение: пиромант есть, а записи мага нет — он и становится придворным магом.
            if (e == null) return;
            mage = com.vladosiwe.lordsoutpost.server.OutpostManager.addSettler(o, com.vladosiwe.lordsoutpost.outpost.Role.MAGE, level.random);
        }
        if (e != null) mage.health = Math.max(0.05f, ((LivingEntity) e).getHealth() / Math.max(1f, ((LivingEntity) e).getMaxHealth()));
        if (e == null) {
            if (now < o.pyromancerRejoinAt || !level.isLoaded(tower)) return;
            Mob spawned = spawn(level, o, type, tower);
            if (spawned != null) {
                spawned.setCustomName(Component.literal(mage.name + " ").append(Component.literal("[")
                        .append(com.vladosiwe.lordsoutpost.outpost.Role.MAGE.label()).append("]").withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE)));
            }
            if (spawned == null) return;
            o.pyromancer = spawned.getUUID();
            o.log(now, "lordsoutpost.event.pyromancer_joined");
            Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.pyromancer_joined"));
            OutpostSavedData.get(level).setDirty();
            return;
        }
        if (!(e instanceof Mob mob)) return;
        defend(level, o, mob, tower);
    }

    @Nullable
    private static Mob findNear(ServerLevel level, Outpost o, BlockPos tower) {
        return level.getEntitiesOfClass(Mob.class, new AABB(tower).inflate(64), m -> m.isAlive() && ofOutpost(m, o.id))
                .stream().findFirst().orElse(null);
    }

    @Nullable
    private static Mob spawn(ServerLevel level, Outpost o, EntityType<?> type, BlockPos tower) {
        Entity created = type.create(level);
        if (!(created instanceof Mob mob)) return null;
        mob.moveTo(tower.getX() + 0.5, tower.getY(), tower.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(tower), MobSpawnType.EVENT, null);
        mob.setPersistenceRequired();
        mob.getPersistentData().putLong(TAG, o.id);
        if (!level.addFreshEntity(mob)) return null;
        LordsOutpost.LOGGER.info("Форт #{}: к башне мага пришёл пиромант Iron's Spells", Long.toHexString(o.id));
        return mob;
    }

    /**
     * Защита: во время набега и при нападении мародёров на территорию пиромант сам выбирает ближайшего врага,
     * а колдует его собственный ИИ мода. Без боя он возвращается к башне.
     */
    private static void defend(ServerLevel level, Outpost o, Mob mob, BlockPos tower) {
        LivingEntity target = mob.getTarget();
        if (target != null && (!target.isAlive() || (target instanceof OutpostNpc npc && npc.isFriend(mob)))) {
            mob.setTarget(null);
            target = null;
        }
        if (target == null) {
            int r = o.reach() + 16;
            FighterEntity enemy = level.getEntitiesOfClass(FighterEntity.class, new AABB(o.center).inflate(r, 24, r),
                            f -> f.isAlive() && f.hostileFaction() && (f.raidTarget == o.id || o.contains(f.blockPosition())))
                    .stream().min(Comparator.comparingDouble(f -> f.distanceToSqr(mob))).orElse(null);
            if (enemy != null) {
                mob.setTarget(enemy);
                return;
            }
        }
        if (mob.getTarget() == null && mob.distanceToSqr(tower.getX() + 0.5, tower.getY(), tower.getZ() + 0.5) > LEASH * LEASH
                && mob.getNavigation().isDone()) {
            mob.getNavigation().moveTo(tower.getX() + 0.5, tower.getY(), tower.getZ() + 0.5, 1.0);
        }
    }

    /** Пиромант погиб: новый придёт через срок. */
    public static void onDeath(ServerLevel level, Entity dead) {
        if (!dead.getPersistentData().contains(TAG)) return;
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = data.byId(dead.getPersistentData().getLong(TAG));
        if (o == null || !dead.getUUID().equals(o.pyromancer)) return;
        long now = level.getGameTime();
        o.pyromancer = null;
        o.pyromancerRejoinAt = now + REJOIN;
        // Пиромант — тело придворного мага: маг погиб, новый придёт через срок.
        var mage = mage(o);
        if (mage != null) o.residents.remove(mage);
        o.log(now, "lordsoutpost.event.pyromancer_died");
        Notify.lord(level.getServer(), o, Component.translatable("lordsoutpost.event.pyromancer_died"));
        data.setDirty();
    }
}
