package com.vladosiwe.lordsoutpost.server;

import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.data.Names;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** Жизненный цикл аванпоста: появление, захват, основание владения, отказ, возвращение мародёров. */
public final class OutpostManager {
    public enum FoundResult { OK, ALREADY_LORD, NOT_CONQUERED, OUTSIDE, NAME_INVALID }

    private OutpostManager() {}

    /** Запись для форта, созданного генерацией мира: блоки уже стоят. */
    public static Outpost createGenerated(OutpostSavedData data, long id, BlockPos center, Layout layout, Palette palette, Rotation rotation,
                                          Difficulty difficulty, long now, RandomSource rnd) {
        Outpost o = new Outpost(id, center, layout, palette, rotation, difficulty, false);
        o.steps = Outpost.initialSteps(o.blueprint(), difficulty);
        o.placed = o.steps;
        o.basePlaced = true;
        o.groundTier = o.steps > 0 ? o.tier + 1 : o.tier;
        o.lastSim = now;
        fillGarrison(o, rnd);
        data.add(o);
        return o;
    }

    /** Форт, созданный командой: блоки поставит согласование при первой загрузке чанков. */
    public static Outpost createManual(OutpostSavedData data, BlockPos center, Layout layout, Palette palette, Rotation rotation,
                                       Difficulty difficulty, long now, RandomSource rnd) {
        Outpost o = new Outpost(data.nextManualId(), center, layout, palette, rotation, difficulty, true);
        o.steps = Outpost.initialSteps(o.blueprint(), difficulty);
        o.placed = 0;
        o.basePlaced = false;
        o.groundTier = 0;
        o.lastSim = now;
        fillGarrison(o, rnd);
        data.add(o);
        return o;
    }

    /** Гарнизон мародёров: Капитан и бойцы, снаряжение растёт с числом черепков. */
    public static void fillGarrison(Outpost o, RandomSource rnd) {
        o.residents.clear();
        int skulls = o.difficulty.skulls;
        Resident captain = new Resident(UUID.randomUUID(), Role.CAPTAIN, Names.random(rnd));
        captain.gear = Math.min(Resident.MAX_GEAR, (skulls + 1) / 2);
        o.residents.add(captain);
        for (int i = 0; i < o.difficulty.garrison; i++) o.residents.add(marauder(o, rnd));
    }

    public static Resident marauder(Outpost o, RandomSource rnd) {
        int skulls = o.difficulty.skulls;
        Resident r = new Resident(UUID.randomUUID(), randomFighter(rnd), Names.random(rnd));
        r.gear = Math.max(0, Math.min(Resident.MAX_GEAR, (skulls - 1) / 2 + (rnd.nextInt(4) == 0 ? 1 : 0)));
        return r;
    }

    public static Role randomFighter(RandomSource rnd) {
        int roll = rnd.nextInt(100);
        return roll < 35 ? Role.SHIELDMAN : roll < 55 ? Role.BERSERKER : roll < 85 ? Role.ARCHER : Role.CROSSBOWMAN;
    }

    /** Капитан убит: гарнизон разбегается, форт ждёт Штандарта. */
    public static void conquer(OutpostSavedData data, Outpost o, long now) {
        if (o.state != OutpostState.WILD) return;
        o.state = OutpostState.CONQUERED;
        o.conqueredAt = now;
        o.residents.clear();
        o.raid = null;
        o.alarmUntil = 0;
        clearRelations(data, o);
        o.log(now, "lordsoutpost.event.conquered");
        o.dirtyForClients = true;
        data.setDirty();
    }

    /** Проверки перед основанием владения. */
    public static FoundResult canFound(OutpostSavedData data, @Nullable Outpost o, UUID player, BlockPos pos) {
        if (data.lordOutpost(player) != null) return FoundResult.ALREADY_LORD;
        if (o == null || !o.insideWalls(pos)) return FoundResult.OUTSIDE;
        if (o.state != OutpostState.CONQUERED) return FoundResult.NOT_CONQUERED;
        return FoundResult.OK;
    }

    /** Штандарт установлен: игрок становится Лордом, в форт приходят первые поселенцы. */
    public static FoundResult found(OutpostSavedData data, Outpost o, UUID player, String playerName, BlockPos standard, long now, RandomSource rnd) {
        FoundResult check = canFound(data, o, player, standard);
        if (check != FoundResult.OK) return check;
        o.state = OutpostState.OWNED;
        o.owner = player;
        o.ownerName = playerName;
        o.name = defaultName(playerName);
        o.foundedAt = now;
        o.standard = standard.immutable();
        o.lastSim = now;
        o.residents.clear();
        o.caravans.clear();
        o.quests.clear();
        o.raid = null;
        o.alarmUntil = 0;
        o.squads[Squad.GUARD].order = Squad.Order.POSTS;
        o.squads[Squad.WATCH].order = Squad.Order.PATROL;
        o.squads[Squad.RETINUE].order = Squad.Order.POSTS;
        addSettler(o, Role.STEWARD, rnd);
        addSettler(o, Role.BUILDER, rnd);
        addSettler(o, Role.BUILDER, rnd);
        addSettler(o, Role.LUMBERJACK, rnd);
        addSettler(o, Role.MINER, rnd);
        addSettler(o, Role.HUNTER, rnd);
        addSettler(o, Role.SHIELDMAN, rnd);
        addSettler(o, Role.ARCHER, rnd);
        for (int i = o.residents.size(); i < o.populationCap() / 2 + 3; i++) addSettler(o, com.vladosiwe.lordsoutpost.data.Simulation.nextRole(o, rnd), rnd);
        o.setStock(Resource.WOOD, 160);
        o.setStock(Resource.STONE, 120);
        o.setStock(Resource.FOOD, 160);
        o.setStock(Resource.IRON, 16);
        o.setStock(Resource.HIDES, 12);
        o.setStock(Resource.GOLD, 25);
        o.penAnimals = 2;
        o.nextRaidAt = now + 3 * 24000L;
        o.log(now, "lordsoutpost.event.founded", playerName);
        data.setLord(o, player);
        o.dirtyForClients = true;
        return FoundResult.OK;
    }

    public static String defaultName(String playerName) {
        String name = "Fort " + playerName;
        return name.length() > 32 ? name.substring(0, 32) : name;
    }

    public static Resident addSettler(Outpost o, Role role, RandomSource rnd) {
        Resident r = new Resident(UUID.randomUUID(), role, Names.random(rnd));
        if (role.combat) r.squad = pickSquad(o);
        o.residents.add(r);
        return r;
    }

    /** Новобранцы распределяются так: половина в Стражу, треть в Дозор, остальные (до четырёх) в Свиту. */
    public static int pickSquad(Outpost o) {
        int[] n = new int[Squad.COUNT];
        for (Resident r : o.residents) if (r.role.combat && r.squad >= 0 && r.squad < Squad.COUNT) n[r.squad]++;
        int total = n[0] + n[1] + n[2] + 1;
        if (n[Squad.GUARD] < Math.ceil(total * 0.5)) return Squad.GUARD;
        if (n[Squad.WATCH] < Math.ceil(total * 0.3)) return Squad.WATCH;
        return n[Squad.RETINUE] < 4 ? Squad.RETINUE : Squad.GUARD;
    }

    /** Лорд покидает аванпост: жители уходят, постройки остаются. */
    public static void abandon(OutpostSavedData data, Outpost o, long now) {
        if (o.state != OutpostState.OWNED) return;
        data.setLord(o, null);
        o.log(now, "lordsoutpost.event.abandoned", o.ownerName);
        o.state = OutpostState.CONQUERED;
        o.conqueredAt = now;
        o.owner = null;
        o.ownerName = "";
        o.standard = null;
        o.residents.clear();
        o.caravans.clear();
        o.quests.clear();
        o.raid = null;
        o.access.players.clear();
        clearRelations(data, o);
        o.dirtyForClients = true;
        data.setDirty();
    }

    /** Мародёры возвращаются в брошенный форт. */
    public static void revertToWild(OutpostSavedData data, Outpost o, long now, RandomSource rnd) {
        o.state = OutpostState.WILD;
        o.name = "";
        fillGarrison(o, rnd);
        o.log(now, "lordsoutpost.event.reclaimed");
        o.dirtyForClients = true;
        data.setDirty();
    }

    public static void clearRelations(OutpostSavedData data, Outpost o) {
        for (long other : o.relations.keySet()) {
            Outpost t = data.byId(other);
            if (t != null) {
                t.relations.remove(o.id);
                t.dirtyForClients = true;
            }
        }
        o.relations.clear();
    }

    public static boolean validName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32) return false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (ch < 0x20 || ch == 0x7F || ch == '§') return false;
        }
        return true;
    }
}
