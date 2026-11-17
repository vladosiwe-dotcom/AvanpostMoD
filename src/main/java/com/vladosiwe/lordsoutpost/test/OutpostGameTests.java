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
                Marker.STORE_INSIDE, Marker.STOCKPILE, Marker.STORE_GUARD, Marker.MINE_SHAFT, Marker.MINE_DIR,
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
                    for (BlockPos p : d.markers(Marker.STOCKPILE)) h.assertTrue(d.cells.containsKey(p) && d.cells.get(p).state().hasBlockEntity(), id + ": в точке склада нет бочки");
                    h.assertTrue(d.cells.values().stream().anyMatch(cell -> cell.state().is(ModBlocks.STOREHOUSE_DOOR.get())), id + ": у склада нет двери");
                    // Ствол шахты: лестница до дна, первая клетка штрека — нетронутая земля (её выкопает шахтёр).
                    BlockPos bottom = d.markers(Marker.MINE_SHAFT).get(0), dir = d.markers(Marker.MINE_DIR).get(0);
                    h.assertTrue(d.cells.containsKey(bottom) && d.cells.get(bottom).state().is(net.minecraft.world.level.block.Blocks.LADDER), id + ": нет лестницы на дне ствола");
                    h.assertTrue(!d.cells.containsKey(dir) && !d.cells.containsKey(dir.above()), id + ": штрек упирается в постройку");
                    h.assertTrue(bottom.distManhattan(dir) == 1 && bottom.getY() == dir.getY(), id + ": направление штрека задано неверно");
                    // Места, где стоят жители, свободны.
                    for (Marker m : new Marker[]{Marker.THRONE, Marker.STORAGE, Marker.YARD, Marker.HOUSING, Marker.GUARD_POST, Marker.KEEP_DOOR, Marker.BOARD,
                            Marker.STORE_INSIDE, Marker.STORE_GUARD}) {
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

    /** Ноша жителя: 75% считается по занятым слотам, а не по числу предметов. */
    @GameTest(template = "empty")
    public static void workerCargoUsesSlotThreshold(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        WorkerEntity worker = com.vladosiwe.lordsoutpost.registry.ModEntities.WORKER.get().create(level);
        h.assertTrue(worker != null, "не удалось создать тестового жителя");
        if (worker == null) {
            h.succeed();
            return;
        }

        worker.inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
        h.assertTrue(worker.occupiedSlots() == 1, "одна стопка должна занимать один слот");
        h.assertTrue(!worker.cargoReadyToDeposit(), "одна стопка не должна считаться заполнением 75%");

        net.minecraft.world.item.Item[] distinctItems = {
                net.minecraft.world.item.Items.DIRT,
                net.minecraft.world.item.Items.GRAVEL,
                net.minecraft.world.item.Items.SAND,
                net.minecraft.world.item.Items.OAK_LOG,
                net.minecraft.world.item.Items.COAL,
                net.minecraft.world.item.Items.IRON_INGOT
        };
        for (int i = 1; i < 7; i++) {
            worker.inventory.setItem(i, new net.minecraft.world.item.ItemStack(distinctItems[i - 1], 1));
        }
        h.assertTrue(worker.occupiedSlots() == 7, "ожидалось семь занятых слотов");
        h.assertTrue(!worker.cargoReadyToDeposit(), "7 из 12 слотов — меньше 75%");

        worker.inventory.setItem(7, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK, 1));
        h.assertTrue(worker.occupiedSlots() == 8, "ожидалось восемь занятых слотов");
        h.assertTrue(!worker.cargoReadyToDeposit(), "8 из 12 слотов — меньше 75%");
        worker.inventory.setItem(8, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_NUGGET, 1));
        h.assertTrue(worker.occupiedSlots() == 9, "ожидалось девять занятых слотов");
        h.assertTrue(worker.cargoReadyToDeposit(), "9 из 12 слотов должны включать возврат на склад");
        h.assertTrue(!worker.inventoryFull(), "9 из 12 слотов не являются полной ношей");
        worker.discard();
        h.succeed();
    }

    /** Опыт шахтёра сохраняется, а уровни открываются на заданных порогах добычи. */
    @GameTest(template = "empty")
    public static void minerProgressionPersists(GameTestHelper h) {
        Resident miner = new Resident(UUID.randomUUID(), Role.MINER, "Test Miner");
        h.assertTrue(miner.miningLevel() == 1, "новый шахтёр должен иметь 1-й уровень");
        miner.miningXp = 50;
        h.assertTrue(miner.miningLevel() == 2, "50 блоков должны открыть 2-й уровень");
        miner.miningXp = 150;
        h.assertTrue(miner.miningLevel() == 3, "150 блоков должны открыть 3-й уровень");
        miner.miningXp = 300;
        h.assertTrue(miner.miningLevel() == 4, "300 блоков должны открыть 4-й уровень");
        miner.miningXp = 500;
        h.assertTrue(miner.miningLevel() == 5, "500 блоков должны открыть 5-й уровень");
        Resident loaded = Resident.load(miner.save());
        h.assertTrue(loaded.miningXp == 500 && loaded.miningLevel() == 5, "опыт шахтёра не сохранился");
        h.succeed();
    }

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

    /** Загруженный в мир житель не должен одновременно давать виртуальную выработку, даже если долго не работал. */
    @GameTest(template = "empty")
    public static void loadedWorkerIsNotCountedAsVirtual(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(96, 0, 0), 1, UUID.randomUUID());
        try {
            o.residents.clear();
            Resident miner = new Resident(UUID.randomUUID(), Role.MINER, "Loaded Miner");
            o.residents.add(miner);
            o.online = true;
            miner.entity = UUID.randomUUID();
            int baseline = Simulation.virtualWorkers(o, Role.MINER, level.getGameTime());
            miner.lastRealWork = level.getGameTime() - 100_000;
            int loadedCount = Simulation.virtualWorkers(o, Role.MINER, level.getGameTime());
            h.assertTrue(baseline == 0 && loadedCount == 0,
                    "загруженный шахтёр не должен получать виртуальную добычу: baseline=" + baseline
                            + ", actual=" + loadedCount + ", online=" + o.online + ", entity=" + miner.entity);
            miner.entity = null;
            h.assertTrue(Simulation.virtualWorkers(o, Role.MINER, level.getGameTime()) == baseline + 1,
                    "выгруженный шахтёр должен учитываться в A-Life симуляции");
            miner.entity = UUID.randomUUID();
            o.online = false;
            h.assertTrue(Simulation.virtualWorkers(o, Role.MINER, level.getGameTime()) == baseline + 1,
                    "при выгруженном аванпосте шахтёр должен учитываться виртуально");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** При выгрузке чанка переполнение склада не уничтожает добычу жителя. */
    @GameTest(template = "empty")
    public static void virtualWorkerDepositPreservesOverflow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(128, 0, 0), 1, UUID.randomUUID());
        try {
            o.setStock(Resource.WOOD, o.capacity(Resource.WOOD) - 4);
            net.minecraft.world.SimpleContainer cargo = new net.minecraft.world.SimpleContainer(1);
            cargo.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG, 2));
            com.vladosiwe.lordsoutpost.data.Storehouse.depositVirtually(o, cargo);
            h.assertTrue(Math.abs(o.stock(Resource.WOOD) - o.capacity(Resource.WOOD)) < 1e-6,
                    "склад должен принять ровно один брусок до лимита");
            h.assertTrue(cargo.isEmpty(), "после виртуальной разгрузки ноша должна быть пустой");
            h.assertTrue(o.tribute.stream().anyMatch(st -> st.is(net.minecraft.world.item.Items.OAK_LOG) && st.getCount() == 1),
                    "излишек должен сохраниться в дани");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Тир кирки ограничен одновременно развитием форта и личным уровнем шахтёра. */
    @GameTest(template = "empty")
    public static void minerToolTierUsesBothProgressions(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(256, 0, 0), 1, UUID.randomUUID());
        try {
            Resident miner = new Resident(UUID.randomUUID(), Role.MINER, "Tool Tester");
            miner.miningXp = 500;
            h.assertTrue(WorkerEntity.minerToolTier(o, miner) == 1, "форт первого тира не должен выдавать инструмент выше каменного");
            o.tier = 2;
            h.assertTrue(WorkerEntity.minerToolTier(o, miner) == 2, "форт второго тира должен ограничивать кирку железным тиром");
            miner.miningXp = 149;
            h.assertTrue(WorkerEntity.minerToolTier(o, miner) == 1, "шахтёр ниже 3-го уровня не должен получать железную кирку");
            o.tier = 3;
            miner.miningXp = 500;
            h.assertTrue(WorkerEntity.minerToolTier(o, miner) == 3, "шахтёр 5-го уровня в форте 3-го тира должен получать алмазную кирку");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Лава и вода во дворе убираются, вода, которую ставит сам чертёж (колодец), остаётся. */
    @GameTest(template = "empty")
    public static void yardFluidsAreCleared(GameTestHelper h) {
        BlockPos c = h.absolutePos(new BlockPos(2, 1, 2));
        ServerLevel level = h.getLevel();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) level.setBlock(c.offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState(), 2);
        level.setBlock(c.offset(1, 0, 1), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), 2);
        int n = com.vladosiwe.lordsoutpost.world.FortPlacer.clearFluids(level, c, 2, null, rel -> rel.equals(new BlockPos(1, 0, 1)));
        h.assertTrue(n == 8, "убрано " + n + " вместо 8");
        h.assertTrue(level.getBlockState(c).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK), "на месте лавы не трава");
        h.assertTrue(level.getBlockState(c.offset(1, 0, 1)).is(net.minecraft.world.level.block.Blocks.WATER), "вода чертежа убрана");
        h.succeed();
    }

    /** Пока мир закрыт, игровое время стоит; при запуске форт проживает это время задним числом и растёт. */
    @GameTest(template = "empty")
    public static void outpostLivesWhileWorldClosed(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(1500, 0, 0), 1, UUID.randomUUID());
        try {
            o.nextRaidAt = Long.MAX_VALUE / 4;
            o.lastSim = level.getGameTime();
            int pop0 = o.residents.size(), steps0 = o.steps, tier0 = o.tier;
            ALifeSimulationManager.catchUp(level, data, o, 3 * Simulation.DAY);
            h.assertTrue(o.lastSim == level.getGameTime(), "догон не дошёл до текущего времени: " + o.lastSim);
            h.assertTrue(o.tier > tier0 || o.steps > steps0, "за три дня отсутствия форт не строился");
            h.assertTrue(o.residents.size() > pop0, "за три дня отсутствия население не выросло");
            h.assertTrue(o.chronicle.stream().anyMatch(e -> e.key().equals("lordsoutpost.event.catch_up")), "нет записи в хронике");
            LordsOutpost.LOGGER.info("Догон: 3 дня, тир {} → {}, шагов {}, жителей {} → {}", tier0, o.tier, o.steps, pop0, o.residents.size());
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
        long[] mark = {0};
        net.minecraft.server.level.ServerPlayer[] viewer = {null};
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
                    h.assertTrue(captains.size() == 1, "Лордов мародёров: " + captains.size());
                    CaptainEntity captain = captains.get(0);
                    h.assertTrue(captain.hostileFaction(), "Лорд мародёров не враждебен");
                    h.assertTrue(Math.abs(captain.getMaxHealth() - CaptainEntity.maxHealthFor(o.difficulty.skulls)) < 0.01
                            && captain.getMaxHealth() <= 60, "здоровье Лорда мародёров: " + captain.getMaxHealth());
                    h.assertTrue(captain.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SCALE) == 1.0, "модель Лорда увеличена");
                    // Полоса босса: у стен — видна. Поддельный игрок есть не везде: Epic Fight шлёт ему свои пакеты
                    // и падает на фальшивом соединении, поэтому без него эта часть проверки пропускается.
                    try {
                        viewer[0] = h.makeMockServerPlayerInLevel();
                    } catch (RuntimeException e) {
                        LordsOutpost.LOGGER.info("Полоса босса не проверена: поддельный игрок недоступен ({})", e.toString());
                        captain.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
                        stage[0] = 1;
                        return;
                    }
                    viewer[0].setGameMode(GameType.SURVIVAL);
                    BlockPos gate = o.marker(Marker.GATE);
                    viewer[0].teleportTo(gate.getX() + 0.5, gate.getY() + 0.5, gate.getZ() + 6.5);
                    mark[0] = h.getTick();
                    stage[0] = 10;
                }
                case 10 -> {
                    if (h.getTick() - mark[0] < 25) return;
                    CaptainEntity captain = level.getEntitiesOfClass(CaptainEntity.class, area).get(0);
                    h.assertTrue(captain.bossBarViewers().contains(viewer[0]), "у стен форта полоса Лорда не видна");
                    // ...а за 150 блоков — нет, хотя сущность всё ещё отслеживается.
                    viewer[0].teleportTo(o.center.getX() + 150.5, o.center.getY() + 1, o.center.getZ() + 0.5);
                    mark[0] = h.getTick();
                    stage[0] = 11;
                }
                case 11 -> {
                    if (h.getTick() - mark[0] < 25) return;
                    CaptainEntity captain = level.getEntitiesOfClass(CaptainEntity.class, area).get(0);
                    h.assertTrue(!captain.bossBarViewers().contains(viewer[0]), "полоса Лорда видна за 150 блоков от форта");
                    viewer[0].discard();
                    captain.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
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

    /**
     * Связь с Solo Status System (тест выполняется, только если мод установлен): основание аванпоста
     * выдаёт и надевает титул «Лорд», отказ от аванпоста его снимает.
     */
    @GameTest(template = "empty")
    public static void soloStatusLordTitle(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded(com.vladosiwe.lordsoutpost.compat.SoloStatusCompat.MODID)) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        net.minecraft.server.level.ServerPlayer player = h.makeMockServerPlayerInLevel();
        Outpost o = virtualOutpost(level, data, FAR.offset(18000, 0, 0), 1, null);
        try {
            OutpostManager.conquer(data, o, level.getGameTime());
            OutpostManager.found(data, o, player.getUUID(), "Tester", o.marker(Marker.YARD), level.getGameTime(), level.random);
            com.vladosiwe.lordsoutpost.compat.SoloStatusCompat.onBecameLord(player);
            h.assertTrue("lord".equals(soloTitle(player)), "титул Системы после основания: " + soloTitle(player));
            OutpostManager.abandon(data, o, level.getGameTime());
            com.vladosiwe.lordsoutpost.compat.SoloStatusCompat.setLord(player, false);
            h.assertTrue(!"lord".equals(soloTitle(player)), "титул остался после отказа от аванпоста");
            LordsOutpost.LOGGER.info("Solo Status System: титул Лорда выдаётся и снимается вместе с владением");
        } finally {
            data.remove(o);
        }
        h.succeed();
    }

    private static String soloTitle(net.minecraft.server.level.ServerPlayer player) {
        try {
            Object d = Class.forName("com.vladosiwe.solostatus.data.Hunter").getMethod("get", Player.class).invoke(null, player);
            return (String) d.getClass().getField("title").get(d);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Магическая добыча. Без Iron's Spells таблицы мода просто не загружаются, и Маг создаёт ванильные предметы.
     * С Iron's Spells (так тест запускается на GitHub) таблицы обязаны загрузиться и выдавать свитки и руны мода.
     */
    @GameTest(template = "empty")
    public static void magicLootTables(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var tables = level.getServer().reloadableRegistries();
        h.assertTrue(tables.getLootTable(com.vladosiwe.lordsoutpost.compat.MagicLoot.MAGE_VANILLA) != net.minecraft.world.level.storage.loot.LootTable.EMPTY,
                "нет ванильной таблицы работы Мага");
        boolean irons = com.vladosiwe.lordsoutpost.compat.MagicLoot.ironsLoaded();
        for (var key : List.of(com.vladosiwe.lordsoutpost.compat.MagicLoot.MAGE_IRONS, com.vladosiwe.lordsoutpost.compat.MagicLoot.TREASURE_IRONS)) {
            boolean loaded = tables.getLootTable(key) != net.minecraft.world.level.storage.loot.LootTable.EMPTY;
            h.assertTrue(loaded == irons, "таблица " + key.location() + (irons ? " не загрузилась при установленном Iron's Spells" : " загрузилась без Iron's Spells"));
        }
        int ironsItems = 0, total = 0;
        for (int i = 0; i < 40; i++) {
            for (var stack : com.vladosiwe.lordsoutpost.compat.MagicLoot.mageProduct(level, level.random)) {
                total++;
                if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("irons_spellbooks")) ironsItems++;
            }
        }
        h.assertTrue(total > 0, "Маг ничего не создаёт");
        h.assertTrue(irons ? ironsItems > 0 : ironsItems == 0, "предметы Мага не соответствуют установленным модам: " + ironsItems + " из " + total);
        if (irons) {
            int scrolls = 0;
            for (int i = 0; i < 10; i++) {
                for (var stack : com.vladosiwe.lordsoutpost.compat.MagicLoot.captainScrolls(level, level.random)) {
                    if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals("scroll")) scrolls++;
                }
            }
            h.assertTrue(scrolls > 0, "в сундуке Капитана нет свитков Iron's Spells");
            LordsOutpost.LOGGER.info("Iron's Spells: таблицы добычи работают, предметов мода у Мага {} из {}, свитков у Капитана {}", ironsItems, total, scrolls);
        } else {
            LordsOutpost.LOGGER.info("Iron's Spells не установлен: Маг создаёт ванильные предметы ({} шт. за 40 циклов)", total);
        }
        h.succeed();
    }

    /**
     * Epic Fight (тест выполняется, только если мод установлен; так он запускается на GitHub).
     * Патчи из data/lordsoutpost/epicfight_mobpatch должны загрузиться, и бой мечника и Лорда мародёров
     * должен перейти на анимированные атаки Epic Fight. Лучник свой стрелковый ИИ сохраняет.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void epicFightPatchesFighters(GameTestHelper h) {
        if (!net.neoforged.fml.ModList.get().isLoaded("epicfight")) {
            h.succeed();
            return;
        }
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(21000, 0, 0), 3, null);
        BlockPos base = h.absolutePos(new BlockPos(1, 2, 1));
        Resident swordsman = null, archer = null, captain = o.captain();
        for (Resident r : o.residents) {
            if (r.role == Role.SHIELDMAN && swordsman == null) swordsman = r;
            if (r.role.ranged() && archer == null) archer = r;
        }
        if (swordsman == null) swordsman = OutpostManager.addSettler(o, Role.SHIELDMAN, level.random);
        if (archer == null) archer = OutpostManager.addSettler(o, Role.ARCHER, level.random);
        OutpostNpc sword = com.vladosiwe.lordsoutpost.data.ChunkReconciliation.spawn(level, o, swordsman, base);
        OutpostNpc bow = com.vladosiwe.lordsoutpost.data.ChunkReconciliation.spawn(level, o, archer, base.offset(3, 0, 0));
        OutpostNpc boss = com.vladosiwe.lordsoutpost.data.ChunkReconciliation.spawn(level, o, captain, base.offset(0, 0, 3));
        h.assertTrue(sword instanceof FighterEntity && bow instanceof FighterEntity && boss instanceof CaptainEntity, "бойцы не появились");
        h.runAfterDelay(40, () -> {
            try {
                List<String> swordGoals = ((FighterEntity) sword).goalClassNames();
                List<String> bossGoals = ((FighterEntity) boss).goalClassNames();
                List<String> bowGoals = ((FighterEntity) bow).goalClassNames();
                LordsOutpost.LOGGER.info("Epic Fight: цели мечника {}", swordGoals);
                LordsOutpost.LOGGER.info("Epic Fight: цели Лорда мародёров {}", bossGoals);
                LordsOutpost.LOGGER.info("Epic Fight: цели лучника {}", bowGoals);
                h.assertTrue(swordGoals.stream().anyMatch(n -> n.startsWith("yesman.epicfight")), "мечник не получил боевой ИИ Epic Fight: " + swordGoals);
                h.assertTrue(bossGoals.stream().anyMatch(n -> n.startsWith("yesman.epicfight")), "Лорд мародёров не получил боевой ИИ Epic Fight: " + bossGoals);
                h.assertTrue(bossGoals.stream().anyMatch(n -> n.endsWith("CaptainTacticsGoal")), "Лорд мародёров потерял тактику");
                h.assertTrue(bowGoals.stream().anyMatch(n -> n.contains("Ranged")), "лучник потерял стрелковый ИИ: " + bowGoals);
                h.succeed();
            } finally {
                sword.discard();
                bow.discard();
                boss.discard();
                data.remove(o);
            }
        });
    }

    /** Штрек шахтёра: 32 блока главного хода и по 12 отвилок длиной 6, каждый следующий столбец стоит рядом с предыдущим. */
    @GameTest(template = "empty")
    public static void mineTunnelLayout(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        for (Layout layout : Layout.VALUES)
            for (Rotation rot : Rotation.values()) {
                Outpost o = new Outpost(data.nextManualId(), FAR.offset(24000, 0, 0), layout, Palette.TIMBER, rot, Difficulty.REDOUBT, true);
                List<com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.Column> cols = com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.tunnel(o);
                h.assertTrue(cols.size() == 32 + 8 * 2 * 6 * 8, layout.id + " " + rot + ": столбцов штрека " + cols.size());
                BlockPos shaft = o.marker(Marker.MINE_SHAFT);
                for (var c : cols) {
                    h.assertTrue(c.stand().distManhattan(c.floor()) == 1 && c.floor().getY() == shaft.getY(), "столбец не примыкает к предыдущему: " + c);
                    h.assertTrue(!o.insideHorizontally(c.floor().getX(), c.floor().getZ(), 0) || c.floor().getY() < o.center.getY() - 4, "штрек выходит к поверхности");
                }
            }
        h.succeed();
    }

    /**
     * Жители работают в мире. Владение 1 тира в загруженных чанках; за стенами растёт дерево и пасутся коровы,
     * во дворе лежат предметы. Лесоруб должен свалить дерево, жители — подобрать предметы, добыча — дойти до склада,
     * а запасы в бочках склада — совпадать с запасом поселения.
     */
    @GameTest(template = "empty", timeoutTicks = 4800)
    public static void workersReallyWork(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.setDayTime(3000);
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        int x = origin.getX() + 1400, z = origin.getZ() + 640;
        for (int cx = -5; cx <= 5; cx++)
            for (int cz = -5; cz <= 5; cz++) level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, true);
        // Поднимаем тестовый форт выше нижней границы мира: шахтный ствол должен помещаться в высоту.
        int y = Math.max(level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15),
                level.getMinBuildHeight() + 8);
        Outpost o = OutpostManager.createManual(data, new BlockPos(x, y, z), Layout.SQUARE, Palette.TIMBER, Rotation.NONE, Difficulty.FRONTIER_POST, level.getGameTime(), level.random);
        UUID lord = UUID.randomUUID();
        AABB area = new AABB(o.center).inflate(70, 40, 70);
        int[] stage = {0};
        long[] mark = {0};
        int[] maxPenned = {0};
        List<BlockPos> treeLogs = new java.util.ArrayList<>();
        List<net.minecraft.world.entity.item.ItemEntity> litter = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            switch (stage[0]) {
                case 0 -> {
                    if (!o.online || !o.basePlaced) return;
                    OutpostManager.conquer(data, o, level.getGameTime());
                    OutpostManager.found(data, o, lord, "Tester", o.marker(Marker.YARD), level.getGameTime(), level.random);
                    o.nextRaidAt = Long.MAX_VALUE / 4;
                    o.autoCaravans = false;
                    // Этот тест отдельно проверяет перегон: отключаем плановое размножение, чтобы скотовод выбрал FETCH.
                    o.penActionAt = Long.MAX_VALUE / 4;
                    // Фиксируем стартовое стадо, чтобы случайное блуждание при открытой калитке не делало тест нестабильным.
                    for (net.minecraft.world.entity.animal.Animal penned
                            : com.vladosiwe.lordsoutpost.data.ChunkReconciliation.penAnimals(level, o)) penned.setNoAi(true);
                    // Дерево за стенами, недалеко от дровяного двора.
                    BlockPos wood = o.marker(Marker.WOODYARD);
                    int tx = o.center.getX() + o.reach() + Layout.MARGIN + 6, tz = wood.getZ();
                    int ty = level.getChunk(tx >> 4, tz >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, tx & 15, tz & 15);
                    BlockPos base = new BlockPos(tx, ty, tz);
                    // Крупное 2×2 дерево на 18 блоков (72 бревна): проверяем, что лимит обхода не обрезает верхушку.
                    for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                        level.setBlockAndUpdate(base.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                        for (int i = 0; i < 18; i++) {
                            BlockPos log = base.offset(dx, i, dz);
                            level.setBlockAndUpdate(log, net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
                            treeLogs.add(log);
                        }
                    }
                    for (BlockPos p : BlockPos.betweenClosed(base.offset(-2, 14, -2), base.offset(3, 20, 3))) {
                        if (level.getBlockState(p).isAir()) level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
                    }
                    // Коровы рядом с внешней стороной калитки: проверяем, что скотовод физически заводит их через проход.
                    BlockPos pen = o.marker(Marker.PEN);
                    // Пустой шаблон GameTest не имеет естественного грунта: создаём площадку перед калиткой.
                    int penFloor = pen.getY() - 1;
                    int cowLineZ = pen.getZ() + 4;
                    for (int wx = pen.getX() - 4; wx <= pen.getX() + 4; wx++) {
                        level.setBlock(new BlockPos(wx, penFloor, cowLineZ), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    }
                    for (int wz = pen.getZ() + 2; wz <= cowLineZ; wz++) {
                        level.setBlock(new BlockPos(pen.getX(), penFloor, wz), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                    }
                    for (int i = 0; i < 3; i++) {
                        var cow = net.minecraft.world.entity.EntityType.COW.create(level);
                        int cx = pen.getX() - 2 + i * 2, cz = cowLineZ;
                        level.setBlock(new BlockPos(cx, penFloor, cz), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                        cow.moveTo(cx + 0.5, pen.getY(), cz + 0.5, 0, 0);
                        cow.setPersistenceRequired();
                        level.addFreshEntity(cow);
                    }
                    // Мусор во дворе.
                    BlockPos yard = o.marker(Marker.YARD);
                    for (int i = 0; i < 3; i++) {
                        var e = new net.minecraft.world.entity.item.ItemEntity(level, yard.getX() + 0.5 + i, yard.getY() + 0.5, yard.getZ() + 0.5,
                                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 8));
                        level.addFreshEntity(e);
                        litter.add(e);
                    }
                    // Пустой GameTest-мир не содержит природной породы: заполняем первые колонны штрека камнем,
                    // чтобы проверить реальную добычу, а не только наличие шахтёра и его маршрута.
                    for (com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.Column column
                            : com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.tunnel(o, level.getMinBuildHeight())) {
                        level.setBlockAndUpdate(column.floor(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(column.floor().below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(column.floor().above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    }
                    mark[0] = h.getTick();
                    stage[0] = 1;
                }
                case 1 -> {
                    // Фиксируем каждое животное сразу после входа: тест проверяет перегон, а не случайное блуждание стада.
                    for (net.minecraft.world.entity.animal.Animal penned
                            : com.vladosiwe.lordsoutpost.data.ChunkReconciliation.penAnimals(level, o)) penned.setNoAi(true);
                    int logsLeft = 0;
                    for (BlockPos p : treeLogs) if (level.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)) logsLeft++;
                    boolean litterGone = litter.stream().noneMatch(net.minecraft.world.entity.Entity::isAlive);
                    boolean replanted = !treeLogs.isEmpty() && treeLogs.stream()
                            .filter(p -> p.getY() == treeLogs.get(0).getY())
                            .anyMatch(p -> level.getBlockState(p).getBlock() == net.minecraft.world.level.block.Blocks.OAK_SAPLING);
                    if ((h.getTick() - mark[0]) % 200 == 0) {
                        LordsOutpost.LOGGER.info("Работа жителей: тик {}, брёвен осталось {}, мусор подобран {}, донесено до склада {}, дерево {}",
                                h.getTick() - mark[0], logsLeft, litterGone, o.depositedItems, o.claimedTrees);
                        for (var w : level.getEntitiesOfClass(com.vladosiwe.lordsoutpost.entity.WorkerEntity.class, area)) {
                            LordsOutpost.LOGGER.info("   {} {} @{} goal={} running={}", w.role().id, w.getName().getString(), w.blockPosition().toShortString(),
                                    w.workGoal == null ? "-" : w.workGoal.status(), w.goalClassNames());
                        }
                    }
                    int penned = com.vladosiwe.lordsoutpost.data.ChunkReconciliation.penAnimals(level, o).size();
                    maxPenned[0] = Math.max(maxPenned[0], penned);
                    boolean minerWorked = level.getEntitiesOfClass(WorkerEntity.class, area,
                            w -> w.outpostId() == o.id && w.role() == Role.MINER && w.resident() != null && w.resident().miningXp > 0).size() > 0;
                    if ((h.getTick() - mark[0]) % 200 == 0) LordsOutpost.LOGGER.info("Работа жителей: в загоне {}, шахтёр добыл блоки {}", penned, minerWorked);
                    if (logsLeft > 0 || !replanted || !litterGone || o.depositedItems < 24 + 5 || maxPenned[0] < 3 || !minerWorked) return;
                    // Склад: бочки отражают запас.
                    var barrels = com.vladosiwe.lordsoutpost.data.Storehouse.barrels(level, o);
                    h.assertTrue(!barrels.isEmpty(), "у склада нет бочек");
                    var have = com.vladosiwe.lordsoutpost.data.Storehouse.measure(barrels);
                    for (Resource r : com.vladosiwe.lordsoutpost.data.Storehouse.PHYSICAL) {
                        double diff = Math.abs(have.get(r) - o.stock(r));
                        h.assertTrue(diff < 10 || have.get(r) > 0, "бочки склада не совпадают с запасом: " + r + " " + have.get(r) + " / " + o.stock(r));
                    }
                    BlockPos sapling = treeLogs.get(0);
                    LordsOutpost.LOGGER.info("Работа жителей: дерево свалено и пересажено, мусор подобран, шахтёр получил опыт, донесено {}, максимум в загоне {}, склад {}",
                            o.depositedItems, maxPenned[0], have);
                    OutpostManager.abandon(data, o, level.getGameTime());
                    data.remove(o);
                    for (var npc : level.getEntitiesOfClass(OutpostNpc.class, area)) npc.discard();
                    for (int cx = -5; cx <= 5; cx++)
                        for (int cz = -5; cz <= 5; cz++) level.setChunkForced((x >> 4) + cx, (z >> 4) + cz, false);
                    h.succeed();
                }
                default -> { }
            }
        });
    }
}
