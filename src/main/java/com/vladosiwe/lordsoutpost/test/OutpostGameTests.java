package com.vladosiwe.lordsoutpost.test;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.blueprint.Canvas;
import com.vladosiwe.lordsoutpost.blueprint.Design;
import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import com.vladosiwe.lordsoutpost.data.ALifeSimulationManager;
import com.vladosiwe.lordsoutpost.data.Caravans;
import com.vladosiwe.lordsoutpost.data.Diplomacy;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.data.QuestBoard;
import com.vladosiwe.lordsoutpost.data.Raids;
import com.vladosiwe.lordsoutpost.data.Simulation;
import com.vladosiwe.lordsoutpost.entity.CaptainEntity;
import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.entity.OutpostNpc;
import com.vladosiwe.lordsoutpost.entity.WorkerEntity;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.AccessRules;
import com.vladosiwe.lordsoutpost.outpost.Caravan;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import com.vladosiwe.lordsoutpost.outpost.Law;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Quest;
import com.vladosiwe.lordsoutpost.outpost.Resident;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.protection.Access;
import com.vladosiwe.lordsoutpost.protection.GateAccess;
import com.vladosiwe.lordsoutpost.protection.ProtectionEvents;
import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import com.vladosiwe.lordsoutpost.registry.ModItems;
import com.vladosiwe.lordsoutpost.server.OutpostManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Игровые тесты: запускаются на настоящем выделенном сервере (задача runGameTestServer, в том числе на GitHub).
 * Проверяют чертежи, экономику A-Life, права и полный цикл форта в мире: постройка, гарнизон, захват, основание, стройка.
 */
@GameTestHolder(LordsOutpost.MODID)
@PrefixGameTestTemplate(false)
public final class OutpostGameTests {
    private static final BlockPos FAR = new BlockPos(4_000_000, 64, 4_000_000);

    private OutpostGameTests() {}

    // ============================== Чертежи ==============================

    /** Каждая планировка в каждой палитре даёт три тира со всеми рабочими точками. */
    @GameTest(template = "empty")
    public static void blueprintsAreComplete(GameTestHelper h) {
        Marker[] always = {Marker.GATE, Marker.KEEP_DOOR, Marker.THRONE, Marker.TREASURE, Marker.TRIBUTE, Marker.SUPPLY, Marker.BOARD, Marker.STORAGE,
                Marker.MINE, Marker.WOODYARD, Marker.PEN, Marker.HOUSING, Marker.GUARD_POST, Marker.PATROL, Marker.OUTER_PATROL, Marker.YARD, Marker.CARAVAN, Marker.LOOT_CHEST};
        for (Layout layout : Layout.VALUES)
            for (Palette palette : Palette.VALUES) {
                FortBlueprint bp = FortBlueprint.get(layout, palette, Rotation.NONE);
                int prev = 0;
                for (int tier = 1; tier <= FortBlueprint.MAX_TIER; tier++) {
                    Design d = bp.design(tier);
                    String id = layout.id + "/" + palette.id + "/T" + tier;
                    h.assertTrue(d.cells.size() > prev, id + ": тир не крупнее предыдущего (" + d.cells.size() + ")");
                    prev = d.cells.size();
                    for (Marker m : always) h.assertTrue(!d.markers(m).isEmpty(), id + ": нет точки " + m);
                    if (tier >= 2) {
                        h.assertTrue(!d.markers(Marker.INFIRMARY).isEmpty(), id + ": нет лазарета");
                        h.assertTrue(!d.markers(Marker.SMITHY).isEmpty(), id + ": нет кузницы");
                    }
                    if (tier == 3) h.assertTrue(!d.markers(Marker.MAGE).isEmpty(), id + ": нет башни мага");
                    int reach = Layout.RADIUS[tier] + Layout.MARGIN + 2;
                    for (BlockPos p : d.cells.keySet()) {
                        h.assertTrue(Math.abs(p.getX()) <= reach && Math.abs(p.getZ()) <= reach && p.getY() >= -8 && p.getY() <= 26, id + ": блок вне площадки " + p);
                    }
                    // Сундуки и рабочие точки стоят там, где чертёж действительно ставит нужный блок.
                    for (Marker m : new Marker[]{Marker.TREASURE, Marker.TRIBUTE, Marker.LOOT_CHEST}) {
                        for (BlockPos p : d.markers(m)) h.assertTrue(d.cells.containsKey(p) && d.cells.get(p).state().hasBlockEntity(), id + ": в точке " + m + " нет сундука");
                    }
                    for (BlockPos p : d.markers(Marker.SUPPLY)) h.assertTrue(d.cells.containsKey(p) && d.cells.get(p).state().hasBlockEntity(), id + ": нет бочки снабжения");
                    // Места, где стоят жители, свободны.
                    for (Marker m : new Marker[]{Marker.THRONE, Marker.STORAGE, Marker.YARD, Marker.HOUSING, Marker.GUARD_POST, Marker.KEEP_DOOR, Marker.BOARD}) {
                        for (BlockPos p : d.markers(m)) {
                            Canvas.Cell at = d.cells.get(p), above = d.cells.get(p.above());
                            h.assertTrue((at == null || !at.state().isSolid()) && (above == null || !above.state().isSolid()), id + ": точка " + m + " занята блоком " + p);
                        }
                    }
                }
                LordsOutpost.LOGGER.info("Чертёж {}/{}: T1 {} блоков, план T2 {} шагов, план T3 {} шагов", layout.id, palette.id,
                        bp.plan(1).size(), bp.plan(2).size(), bp.plan(3).size());
            }
        h.succeed();
    }

    /** План тира превращает готовый предыдущий тир ровно в следующий, при любом повороте. */
    @GameTest(template = "empty")
    public static void plansTransformTiers(GameTestHelper h) {
        for (Layout layout : Layout.VALUES)
            for (Rotation rot : Rotation.values()) {
                FortBlueprint bp = FortBlueprint.get(layout, Palette.TIMBER, rot);
                for (int tier = 2; tier <= FortBlueprint.MAX_TIER; tier++) {
                    Map<BlockPos, BlockState> world = new HashMap<>();
                    for (var e : bp.design(tier - 1).cells.entrySet()) world.put(e.getKey(), e.getValue().state());
                    for (Step s : bp.plan(tier)) world.put(s.pos(), s.state());
                    for (var e : bp.design(tier).cells.entrySet()) {
                        h.assertTrue(world.get(e.getKey()) == e.getValue().state(), layout.id + " T" + tier + " " + rot + ": после плана блок не тот в " + e.getKey());
                    }
                    // Снимок «тир + N шагов» при полном N совпадает с готовым следующим тиром.
                    Map<BlockPos, BlockState> snap = bp.snapshot(tier - 1, bp.plan(tier).size());
                    for (var e : bp.design(tier).cells.entrySet()) h.assertTrue(snap.get(e.getKey()) == e.getValue().state(), "снимок расходится с чертежом");
                }
            }
        h.succeed();
    }

    // ============================== Экономика ==============================

    private static Outpost virtualOutpost(ServerLevel level, OutpostSavedData data, BlockPos center, int skulls, UUID lord) {
        Outpost o = OutpostManager.createManual(data, center, Layout.SQUARE, Palette.TIMBER, Rotation.NONE, Difficulty.bySkulls(skulls), level.getGameTime(), level.random);
        if (lord != null) {
            OutpostManager.conquer(data, o, level.getGameTime());
            OutpostManager.FoundResult r = OutpostManager.found(data, o, lord, "Tester", o.marker(Marker.YARD), level.getGameTime(), level.random);
            if (r != OutpostManager.FoundResult.OK) throw new IllegalStateException("основание не удалось: " + r);
        }
        return o;
    }

    /** Прокручивает экономику аванпоста макро-тиками, как это делает сервер. */
    private static long simulate(ServerLevel level, OutpostSavedData data, Outpost o, long from, double days) {
        long end = from + (long) (days * Simulation.DAY);
        for (long t = from + ALifeSimulationManager.SIM_INTERVAL; t <= end; t += ALifeSimulationManager.SIM_INTERVAL) Simulation.tick(level, data, o, t);
        return end;
    }

    /**
     * Поселение вдали от игроков (чанки не загружены) само добывает ресурсы, растёт, строится и доходит до третьего тира.
     * Заодно проверяются инварианты: склад не уходит в минус, население не превышает предел.
     */
    @GameTest(template = "empty")
    public static void settlementLivesWithoutPlayers(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        UUID lord = UUID.randomUUID();
        Outpost o = virtualOutpost(level, data, FAR, 1, lord);
        try {
            h.assertTrue(o.state == OutpostState.OWNED && data.lordOutpost(lord) == o, "Лорд не записан");
            h.assertTrue(o.count(Role.STEWARD) == 1 && o.count(Role.BUILDER) >= 2, "нет первых поселенцев");
            o.nextRaidAt = Long.MAX_VALUE / 4;
            long now = level.getGameTime();
            o.lastSim = now;
            int pop0 = o.residents.size();
            now = simulate(level, data, o, now, 3);
            h.assertTrue(o.steps > 0, "за три дня не построено ни одного блока");
            h.assertTrue(o.residents.size() > pop0, "население не выросло: " + o.residents.size());
            int day = 3;
            while (o.tier < 3 && day < 120) {
                now = simulate(level, data, o, now, 1);
                day++;
                for (Resource r : Resource.VALUES) h.assertTrue(o.stock(r) >= 0 && o.stock(r) <= o.capacity(r) + 1e-6, "склад вне пределов: " + r + "=" + o.stock(r));
                h.assertTrue(o.residents.size() <= o.populationCap(), "население выше предела");
                if (o.tier == 2 && o.steps == 0) LordsOutpost.LOGGER.info("Симуляция: тир 2 достроен без игрока на {}-й день", day);
            }
            h.assertTrue(o.tier == 3, "за 120 дней поселение не достроилось до третьего тира (тир " + o.tier + ", " + Math.round(o.progress() * 100) + "%)");
            LordsOutpost.LOGGER.info("Симуляция: тир 3 достроен без игрока на {}-й день; жителей {}, дань в ожидании {} стопок", day, o.residents.size(), o.tribute.size());
            h.assertTrue(o.count(Role.MAGE) == 1 || o.residents.size() < o.populationCap(), "на третьем тире нет мага");
            h.assertTrue(!o.tribute.isEmpty(), "дань Лорду не накопилась");
            // Блоки ещё не стоят в мире: чанки выгружены, поэтому «поставленный» тир отстаёт от логического.
            h.assertTrue(o.placedTier <= o.tier, "блоки не могли встать в выгруженных чанках");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Нехватка рождает задание; помощь игрока закрывает его и ускоряет работу; без игрока задание уходит само. */
    @GameTest(template = "empty")
    public static void shortagesBecomeQuests(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(3000, 0, 0), 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            o.setStock(Resource.FOOD, 0);
            o.setStock(Resource.WOOD, 0);
            o.setStock(Resource.STONE, 0);
            QuestBoard.update(o, now);
            Quest food = null, material = null;
            for (Quest q : o.quests) {
                if (q.type == Quest.Type.FOOD) food = q;
                if (q.type == Quest.Type.BUILD_MATERIAL) material = q;
            }
            h.assertTrue(food != null, "нет задания на еду при пустых кладовых");
            h.assertTrue(material != null, "нет задания на материалы при пустом складе");
            Quest done = QuestBoard.deliver(o, Resource.FOOD, food.needed, now);
            h.assertTrue(done == food && o.boosted(Resource.FOOD, now), "сдача не закрыла задание или не дала ускорения");
            h.assertTrue(o.stock(Resource.FOOD) >= food.needed, "сданное не попало на склад");
            // Жители справились сами: материал появился — задание исчезает.
            o.setStock(material.resource, o.capacity(material.resource));
            QuestBoard.update(o, now + 100);
            h.assertTrue(!o.quests.contains(material), "задание не снялось, хотя нехватки больше нет");
        } finally {
            data.remove(o);
        }
        h.succeed();
    }

    /** Виртуальный набег: сильный гарнизон отбивается, беззащитный форт теряет запасы. */
    @GameTest(template = "empty")
    public static void virtualRaids(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(6000, 0, 0), 3, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            for (int i = 0; i < 10; i++) {
                Resident r = OutpostManager.addSettler(o, Role.SHIELDMAN, level.random);
                r.gear = 3;
            }
            int wins = 0;
            for (int i = 0; i < 20; i++) {
                for (Resident r : o.residents) {
                    r.status = Resident.Status.ACTIVE;
                    r.health = 1;
                    r.armor = 1;
                }
                if (Raids.resolve(level, data, o, 0, 6, now, level.random)) wins++;
            }
            h.assertTrue(wins >= 15, "сильный гарнизон отбил слабый набег только " + wins + " раз из 20");
            o.residents.removeIf(r -> r.role.combat);
            o.setStock(Resource.WOOD, 400);
            boolean win = Raids.resolve(level, data, o, 0, 30, now, level.random);
            h.assertTrue(!win && o.stock(Resource.WOOD) < 400, "беззащитный форт не пострадал от набега");
        } finally {
            data.remove(o);
        }
        h.succeed();
    }

    /** Пакт с мародёрами покупается за золото; караван уходит, торгует и возвращается с выручкой. */
    @GameTest(template = "empty")
    public static void pactsAndCaravans(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(9000, 0, 0), 2, UUID.randomUUID());
        Outpost wild = virtualOutpost(level, data, FAR.offset(9700, 0, 300), 3, null);
        try {
            long now = level.getGameTime();
            o.setStock(Resource.GOLD, 0);
            h.assertTrue(Diplomacy.propose(data, o, wild, Pact.TRADE, now) == Diplomacy.Result.NO_GOLD, "пакт без золота");
            h.assertTrue(Caravans.dispatch(data, o, wild, now) == Caravans.Result.NO_PACT, "караван без пакта");
            o.setStock(Resource.GOLD, 500);
            h.assertTrue(Diplomacy.propose(data, o, wild, Pact.MILITARY, now) == Diplomacy.Result.NEED_TRADE_FIRST, "военный пакт без торгового");
            h.assertTrue(Diplomacy.propose(data, o, wild, Pact.TRADE, now) == Diplomacy.Result.OK, "торговый пакт не заключён");
            h.assertTrue(Math.abs(o.stock(Resource.GOLD) - (500 - Diplomacy.cost(wild, Pact.TRADE))) < 1e-6, "цена пакта не списана");
            h.assertTrue(Raids.source(data, o) == wild, "торговый пакт не должен отменять набеги");
            h.assertTrue(Diplomacy.propose(data, o, wild, Pact.MILITARY, now) == Diplomacy.Result.OK, "военный пакт не заключён");
            h.assertTrue(Raids.source(data, o) == null, "союзник по военному пакту остался источником набегов");

            h.assertTrue(Caravans.dispatch(data, o, wild, now) == Caravans.Result.NO_LEADER, "караван без караванщика");
            Resident leader = OutpostManager.addSettler(o, Role.CARAVANEER, level.random);
            o.setStock(Resource.WOOD, 0);
            o.setStock(Resource.STONE, 0);
            o.setStock(Resource.FOOD, 0);
            o.setStock(Resource.IRON, 0);
            o.setStock(Resource.HIDES, 0);
            h.assertTrue(Caravans.dispatch(data, o, wild, now) == Caravans.Result.NO_CARGO, "караван без груза");
            o.setStock(Resource.HIDES, 200);
            o.setStock(Resource.IRON, 200);
            h.assertTrue(Caravans.dispatch(data, o, wild, now) == Caravans.Result.OK, "караван не отправлен");
            h.assertTrue(leader.status == Resident.Status.ON_CARAVAN && o.caravans.size() == 1, "караванщик не в пути");
            Caravan c = o.caravans.get(0);
            double gold = o.stock(Resource.GOLD);
            c.ambushed = true; // засада проверяется отдельно, здесь нужен чистый рейс
            boolean home = false;
            for (int i = 0; i < 4000 && !home; i++) {
                now += 100;
                home = Caravans.advance(level, data, o, wild, c, now, 5, level.random);
                if (c.phase == Caravan.Phase.RETURNING) c.ambushed = true;
            }
            h.assertTrue(home, "караван не вернулся");
            o.caravans.remove(c);
            h.assertTrue(o.stock(Resource.GOLD) > gold, "караван не принёс выручки");
            h.assertTrue(leader.status == Resident.Status.ACTIVE, "караванщик не вернулся к службе");
            h.assertTrue(o.relations.get(wild.id).caravansDone == 1, "рейс не засчитан");
        } finally {
            data.remove(o);
            data.remove(wild);
        }
        h.succeed();
    }

    // ============================== Права ==============================

    /** Гарнизонный Устав: Лорд, союзник, гость и враг получают ровно те права, что описаны. */
    @GameTest(template = "empty")
    public static void accessCharter(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Player lord = h.makeMockPlayer(GameType.SURVIVAL), ally = h.makeMockPlayer(GameType.SURVIVAL),
                guest = h.makeMockPlayer(GameType.SURVIVAL), foe = h.makeMockPlayer(GameType.SURVIVAL);
        Outpost o = virtualOutpost(level, data, FAR.offset(12000, 0, 0), 2, lord.getUUID());
        try {
            o.access.players.put(ally.getUUID(), new AccessRules.Entry("ally", AccessLevel.ALLY));
            o.access.players.put(foe.getUUID(), new AccessRules.Entry("foe", AccessLevel.HOSTILE));
            h.assertTrue(Access.level(data, o, lord.getUUID()) == AccessLevel.LORD, "Лорд не Лорд");
            h.assertTrue(Access.level(data, o, guest.getUUID()) == AccessLevel.GUEST, "незнакомец не гость");
            BlockPos chest = o.marker(Marker.SUPPLY), tribute = o.marker(Marker.TRIBUTE);

            h.assertTrue(ProtectionEvents.canBuild(data, o, lord) && ProtectionEvents.canUse(data, o, lord, tribute), "Лорду что-то запрещено");
            h.assertTrue(!ProtectionEvents.canBuild(data, o, ally), "союзник может ломать");
            h.assertTrue(ProtectionEvents.canUse(data, o, ally, chest), "союзнику закрыты сундуки");
            h.assertTrue(!ProtectionEvents.canUse(data, o, ally, tribute), "союзник открывает Сундук Дани без разрешения");
            o.access.alliesTribute = true;
            h.assertTrue(ProtectionEvents.canUse(data, o, ally, tribute), "переключатель дани не работает");
            h.assertTrue(!ProtectionEvents.canBuild(data, o, guest) && !ProtectionEvents.canUse(data, o, guest, chest), "гостю открыто лишнее");
            h.assertTrue(!ProtectionEvents.canBuild(data, o, foe) && !ProtectionEvents.canUse(data, o, foe, chest), "врагу открыто лишнее");
            o.access.protectBlocks = false;
            h.assertTrue(ProtectionEvents.canBuild(data, o, guest) && ProtectionEvents.canBuild(data, o, foe), "без защиты чужаки должны мочь ломать");
            h.assertTrue(!ProtectionEvents.canBuild(data, o, ally), "союзник не ломает никогда");

            // Ворота: внешние открыты гостю, цитадель — только союзнику и Лорду, враг не проходит нигде.
            h.assertTrue(GateAccess.playerCanPass(data, o, guest, false) && !GateAccess.playerCanPass(data, o, guest, true), "ворота для гостя");
            h.assertTrue(GateAccess.playerCanPass(data, o, ally, true) && GateAccess.playerCanPass(data, o, lord, true), "цитадель для своих");
            h.assertTrue(!GateAccess.playerCanPass(data, o, foe, false), "враг прошёл ворота");

            // Один Лорд — одно владение.
            Outpost second = virtualOutpost(level, data, FAR.offset(13000, 0, 0), 1, null);
            OutpostManager.conquer(data, second, level.getGameTime());
            h.assertTrue(OutpostManager.canFound(data, second, lord.getUUID(), second.marker(Marker.YARD)) == OutpostManager.FoundResult.ALREADY_LORD, "второй аванпост у Лорда");
            h.assertTrue(OutpostManager.canFound(data, second, guest.getUUID(), second.marker(Marker.YARD)) == OutpostManager.FoundResult.OK, "свободный игрок не может основать");
            h.assertTrue(OutpostManager.canFound(data, second, guest.getUUID(), second.center.offset(200, 1, 0)) == OutpostManager.FoundResult.OUTSIDE, "Штандарт вне форта");
            data.remove(second);

            // Законы: смена меняет множители.
            o.law = Law.SHOCK_CONSTRUCTION;
            h.assertTrue(o.law.buildSpeed() == 2.0 && o.law.foodUse() > 1.0, "закон стройки");
        } finally {
            data.remove(o);
        }
        h.succeed();
    }

    /** Запись аванпоста переживает сохранение и загрузку. */
    @GameTest(template = "empty")
    public static void outpostSurvivesSaveLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(15000, 0, 0), 4, UUID.randomUUID());
        try {
            o.name = "Каменный Брод";
            o.law = Law.TRADE_AND_MAGIC;
            o.setStock(Resource.IRON, 77.5);
            o.squads[Squad.WATCH].route.add(new BlockPos(1, 2, 3));
            o.access.players.put(UUID.randomUUID(), new AccessRules.Entry("friend", AccessLevel.ALLY));
            o.log(level.getGameTime(), "lordsoutpost.event.tier_up", 2);
            CompoundTag tag = o.save(level.registryAccess());
            Outpost copy = Outpost.load(tag, level.registryAccess());
            h.assertTrue(copy.id == o.id && copy.center.equals(o.center) && copy.name.equals(o.name), "место или имя");
            h.assertTrue(copy.state == OutpostState.OWNED && o.owner.equals(copy.owner), "владелец");
            h.assertTrue(copy.tier == o.tier && copy.steps == o.steps && copy.layout == o.layout && copy.palette == o.palette, "стройка");
            h.assertTrue(copy.residents.size() == o.residents.size() && copy.law == o.law, "жители или закон");
            h.assertTrue(Math.abs(copy.stock(Resource.IRON) - 77.5) < 1e-6, "склад");
            h.assertTrue(copy.squads[Squad.WATCH].route.size() == 1 && copy.access.players.size() == 1 && copy.chronicle.size() == o.chronicle.size(), "отряды, права или хроника");
        } finally {
            data.remove(o);
        }
        h.succeed();
    }

    // ============================== Форт в мире ==============================

    /**
     * Полный цикл в загруженных чанках: форт встаёт блоками, появляется гарнизон с Капитаном, Капитан гибнет — форт захвачен
     * и падает Штандарт; игрок основывает владение — приходят жители, и стройка продвигается настоящими блоками.
     */
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void fortLifecycleInWorld(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int x = origin.getX() + 640, z = origin.getZ() + 640;
        for (int cx = -4; cx <= 4; cx++)
            for (int cz = -4; cz <= 4; cz++) level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, true);
        int y = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        Outpost o = OutpostManager.createManual(data, new BlockPos(x, y, z), Layout.SQUARE, Palette.TIMBER, Rotation.NONE, Difficulty.FRONTIER_POST, level.getGameTime(), level.random);
        UUID lord = UUID.randomUUID();
        AABB area = new AABB(o.center).inflate(60, 40, 60);
        int[] stage = {0};
        int[] stepsAtFounding = {0};
        h.onEachTick(() -> {
            if (h.getTick() % 150 == 0) {
                LordsOutpost.LOGGER.info("Форт в мире: тик {}, этап {}, online={}, base={}, состояние {}, жителей {}, сущностей {}, steps={}, placed={}, ground={}",
                        h.getTick(), stage[0], o.online, o.basePlaced, o.state, o.residents.size(), level.getEntitiesOfClass(OutpostNpc.class, area).size(),
                        o.steps, o.placed, o.groundTier);
            }
            switch (stage[0]) {
                case 0 -> {
                    if (!o.online || !o.basePlaced) return;
                    h.assertTrue(level.getBlockState(o.marker(Marker.GATE)).is(ModBlocks.OUTPOST_GATE.get()), "в воротах нет решётки");
                    h.assertTrue(level.getBlockEntity(o.marker(Marker.TREASURE)) != null, "нет сундука Капитана");
                    List<CaptainEntity> captains = level.getEntitiesOfClass(CaptainEntity.class, area);
                    List<FighterEntity> fighters = level.getEntitiesOfClass(FighterEntity.class, area);
                    if (captains.isEmpty() || fighters.size() < o.difficulty.garrison) return;
                    h.assertTrue(captains.size() == 1, "Капитанов: " + captains.size());
                    h.assertTrue(captains.get(0).hostileFaction(), "Капитан не враждебен");
                    captains.get(0).hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (o.state != OutpostState.CONQUERED) return;
                    h.assertTrue(o.residents.isEmpty(), "гарнизон не распущен");
                    boolean standard = level.getEntitiesOfClass(ItemEntity.class, area, e -> e.getItem().is(ModItems.LORD_STANDARD.get())).size() == 1;
                    h.assertTrue(standard, "Штандарт Лорда не выпал");
                    OutpostManager.FoundResult r = OutpostManager.found(data, o, lord, "Tester", o.marker(Marker.YARD), level.getGameTime(), level.random);
                    h.assertTrue(r == OutpostManager.FoundResult.OK, "основание: " + r);
                    o.nextRaidAt = Long.MAX_VALUE / 4;
                    o.setStock(Resource.WOOD, 600);
                    o.setStock(Resource.STONE, 600);
                    stepsAtFounding[0] = o.placed;
                    stage[0] = 2;
                }
                case 2 -> {
                    // Мародёры ушли, пришли жители; строители кладут блоки следующего тира.
                    List<OutpostNpc> all = level.getEntitiesOfClass(OutpostNpc.class, area);
                    if (all.stream().anyMatch(OutpostNpc::hostileFaction)) return;
                    long workers = all.stream().filter(n -> n instanceof WorkerEntity).count();
                    long guards = all.stream().filter(n -> n instanceof FighterEntity).count();
                    if (workers < 4 || guards < 2 || o.placed < stepsAtFounding[0] + 5) return;
                    for (OutpostNpc npc : all) {
                        Resident r = npc.resident();
                        h.assertTrue(r != null && npc.getUUID().equals(r.entity), "сущность без записи жителя");
                    }
                    h.assertTrue(all.size() <= o.residents.size(), "сущностей больше, чем жителей: дубли");
                    LordsOutpost.LOGGER.info("Форт в мире: жителей {}, сущностей {}, уложено блоков тира 2: {}", o.residents.size(), all.size(), o.placed);
                    stage[0] = 3;
                    OutpostManager.abandon(data, o, level.getGameTime());
                    data.remove(o);
                    for (OutpostNpc npc : all) npc.discard();
                    for (int cx = -4; cx <= 4; cx++)
                        for (int cz = -4; cz <= 4; cz++) level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, false);
                    h.succeed();
                }
                default -> { }
            }
        });
    }
}
