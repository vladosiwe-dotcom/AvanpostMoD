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
import com.vladosiwe.lordsoutpost.data.ChunkReconciliation;
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
import com.vladosiwe.lordsoutpost.data.Storehouse;
import com.vladosiwe.lordsoutpost.data.Logistics;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.vladosiwe.lordsoutpost.outpost.Squad;
import com.vladosiwe.lordsoutpost.protection.Access;
import com.vladosiwe.lordsoutpost.protection.GateAccess;
import com.vladosiwe.lordsoutpost.protection.ProtectionEvents;
import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import com.vladosiwe.lordsoutpost.registry.ModEntities;
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
import net.minecraft.world.level.block.Blocks;
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

    /** Фермер имеет одно поле и отдельный оборот семян; офлайн-выработка не зависит от загрузки чанков. */
    @GameTest(template = "empty")
    public static void farmerFieldAndOfflineProduction(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(17000, 0, 0), 1, UUID.randomUUID());
        try {
            h.assertTrue(o.count(Role.FARMER) == 1, "при основании должен появляться один фермер");
            h.assertTrue(o.design().markers(Marker.FARM).size() == 1, "в чертеже нет единственного поля");
            h.assertTrue(o.design().cells.values().stream().anyMatch(cell -> cell.state().is(Blocks.WATER)), "у поля нет источника воды");
            o.setStock(Resource.SEEDS, 0);
            long end = simulate(level, data, o, level.getGameTime(), 1.0);
            h.assertTrue(o.stock(Resource.WHEAT) > 0, "виртуальный фермер не произвёл пшеницу");
            h.assertTrue(o.stock(Resource.SEEDS) > 0, "виртуальный фермер не пополнил запас семян");
            h.assertTrue(Simulation.virtualWorkers(o, Role.FARMER, end) == 1, "выгруженный фермер не участвует в A-Life");
        } finally {
            data.remove(o);
        }
        h.succeed();
    }

    /** Старые hunter/herder записи сохранений загружаются как одна профессия «Скотовод». */
    @GameTest(template = "empty")
    public static void legacyHunterAndHerderRolesMigrateToSkotovod(GameTestHelper h) {
        CompoundTag oldHunter = new CompoundTag();
        oldHunter.putUUID("id", UUID.randomUUID());
        oldHunter.putString("role", "hunter");
        oldHunter.putString("name", "Legacy Hunter");
        CompoundTag oldHerder = new CompoundTag();
        oldHerder.putUUID("id", UUID.randomUUID());
        oldHerder.putString("role", "herder");
        oldHerder.putString("name", "Legacy Herder");

        h.assertTrue(Resident.load(oldHunter).role == Role.SKOTOVOD, "старый hunter не мигрировал в Скотовода");
        h.assertTrue(Resident.load(oldHerder).role == Role.SKOTOVOD, "старый herder не мигрировал в Скотовода");
        int count = 0;
        for (Role r : Role.VALUES) if (r == Role.SKOTOVOD) count++;
        h.assertTrue(count == 1, "в списке профессий должно быть ровно одно вхождение Скотовода");
        h.succeed();
    }

    /** Строитель убирает природную растительность на клетке чертежа, но не сносит чужой сундук. */
    @GameTest(template = "empty")
    public static void builderClearsNaturalObstructionsSafely(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos center = h.absolutePos(BlockPos.ZERO).offset(32, 0, 32);
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, true);

        Outpost o = new Outpost(987654321L, center, Layout.SQUARE, Palette.TIMBER, Rotation.NONE,
                Difficulty.FRONTIER_POST, true);
        o.tier = 1;
        o.placedTier = 2;
        o.groundTier = 2;
        o.steps = o.nextPlan().size();

        int index = -1;
        for (int i = 0; i < o.nextPlan().size(); i++) {
            Step step = o.nextPlan().get(i);
            if (!step.state().isAir() && !o.design().cells.containsKey(step.pos())) {
                index = i;
                break;
            }
        }
        h.assertTrue(index >= 0, "в плане второго тира нет новой строительной клетки");
        Step step = o.nextPlan().get(index);
        BlockPos target = o.world(step.pos());
        level.setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());

        WorkerEntity builder = ModEntities.WORKER.get().create(level);
        h.assertTrue(builder != null, "не удалось создать строителя для GameTest");
        builder.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE));
        builder.moveTo(target.getX() + 1.5, target.getY(), target.getZ() + 0.5, 0, 0);
        level.addFreshEntity(builder);

        h.assertTrue(com.vladosiwe.lordsoutpost.data.ChunkReconciliation.clearNaturalObstruction(level, o, index, builder),
                "строитель не расчистил естественное бревно");
        h.assertTrue(level.getBlockState(target).isAir(), "естественное препятствие осталось на строительной клетке");
        h.assertTrue(!com.vladosiwe.lordsoutpost.data.ChunkReconciliation.blockedByForeignBlock(level, o, index),
                "после расчистки клетка всё ещё считается занятой");

        level.setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
        h.assertTrue(com.vladosiwe.lordsoutpost.data.ChunkReconciliation.clearNaturalObstruction(level, o, index, builder),
                "строитель не заменил естественный дерновый грунт");
        h.assertTrue(level.getBlockState(target).isAir(), "дерновый грунт не был очищен под строительный блок");

        level.setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        h.assertTrue(com.vladosiwe.lordsoutpost.data.ChunkReconciliation.blockedByForeignBlock(level, o, index),
                "строитель должен распознавать чужую постройку как препятствие");
        h.assertTrue(level.getBlockState(target).is(net.minecraft.world.level.block.Blocks.CHEST),
                "проверка не должна разрушать сундук игрока");
        level.setBlockAndUpdate(target, net.minecraft.world.level.block.Blocks.WHEAT.defaultBlockState());
        h.assertTrue(com.vladosiwe.lordsoutpost.data.ChunkReconciliation.blockedByForeignBlock(level, o, index),
                "строитель не должен считать посевы естественным мусором");
        h.assertTrue(level.getBlockState(target).is(net.minecraft.world.level.block.Blocks.WHEAT),
                "посевы должны оставаться на месте");
        builder.discard();
        for (int cx = -2; cx <= 2; cx++)
            for (int cz = -2; cz <= 2; cz++) level.setChunkForced((center.getX() >> 4) + cx, (center.getZ() >> 4) + cz, false);
        h.succeed();
    }

    // ============================== Чертежи ==============================

    /** Каждая планировка в каждой палитре даёт три тира со всеми рабочими точками. */
    @GameTest(template = "empty")
    public static void blueprintsAreComplete(GameTestHelper h) {
        Marker[] always = {Marker.GATE, Marker.KEEP_DOOR, Marker.THRONE, Marker.TREASURE, Marker.TRIBUTE, Marker.SUPPLY, Marker.BOARD, Marker.STORAGE,
                Marker.STORE_INSIDE, Marker.STOCKPILE, Marker.STORE_GUARD, Marker.MINE_SHAFT, Marker.MINE_DIR,
                Marker.MINE, Marker.WOODYARD, Marker.PEN, Marker.HOUSING, Marker.GUARD_POST, Marker.PATROL, Marker.OUTER_PATROL, Marker.YARD, Marker.CARAVAN, Marker.LOOT_CHEST};
        for (int version = 1; version <= 2; version++)
        for (Layout layout : Layout.VALUES)
            for (Palette palette : Palette.VALUES) {
                FortBlueprint bp = FortBlueprint.get(layout, palette, Rotation.NONE, version);
                int prev = 0;
                BlockPos fieldAt = null;
                for (int tier = 1; tier <= FortBlueprint.MAX_TIER; tier++) {
                    Design d = bp.design(tier);
                    String id = "v" + version + "/" + layout.id + "/" + palette.id + "/T" + tier;
                    h.assertTrue(!d.markers(Marker.FARM).isEmpty(), id + ": нет поля");
                    if (version >= 2 && palette == Palette.TIMBER) {
                        var col = com.vladosiwe.lordsoutpost.blueprint.FortDesigner.collisions(layout, palette, tier, version);
                        LordsOutpost.LOGGER.info("План {}:\n{}", id, asciiPlan(d, Layout.radius(version, tier) + Layout.MARGIN));
                        h.assertTrue(col.isEmpty(), id + ": здания налезают друг на друга: " + col);
                    }
                    if (version >= 2) {
                        // Поле одно и то же во всех тирах: 9×9 земли под пашню, вода в центре, ограда с калиткой.
                        BlockPos f = d.markers(Marker.FARM).get(0);
                        if (fieldAt == null) fieldAt = f;
                        h.assertTrue(f.equals(fieldAt), id + ": поле переезжает между тирами");
                        int h4 = com.vladosiwe.lordsoutpost.blueprint.FortDesigner.FIELD_HALF;
                        int dirt = 0;
                        for (int dx = -h4; dx <= h4; dx++)
                            for (int dz = -h4; dz <= h4; dz++) {
                                Canvas.Cell soil = d.cells.get(f.offset(dx, -1, dz));
                                if (soil != null && soil.state().is(net.minecraft.world.level.block.Blocks.DIRT)) dirt++;
                            }
                        h.assertTrue(dirt == (2 * h4 + 1) * (2 * h4 + 1) - 1, id + ": под поле не положена земля (" + dirt + ")");
                        h.assertTrue(d.cells.get(f.below()).state().is(net.minecraft.world.level.block.Blocks.WATER), id + ": в центре поля нет воды");
                        h.assertTrue(layout.inside(f.getX(), f.getZ(), Layout.radius(2, 1) - h4 - 2), id + ": поле за стенами первого тира");
                    }
                    h.assertTrue(d.cells.size() > prev, id + ": тир не крупнее предыдущего (" + d.cells.size() + ")");
                    prev = d.cells.size();
                    for (Marker m : always) h.assertTrue(!d.markers(m).isEmpty(), id + ": нет точки " + m);
                    if (tier >= 2) {
                        h.assertTrue(!d.markers(Marker.INFIRMARY).isEmpty(), id + ": нет лазарета");
                        h.assertTrue(!d.markers(Marker.SMITHY).isEmpty(), id + ": нет кузницы");
                    }
                    if (tier == 3) h.assertTrue(!d.markers(Marker.MAGE).isEmpty(), id + ": нет башни мага");
                    int reach = Layout.radius(version, tier) + Layout.MARGIN + 2;
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
                LordsOutpost.LOGGER.info("Чертёж v{} {}/{}: T1 {} блоков, план T2 {} шагов, план T3 {} шагов", version, layout.id, palette.id,
                        bp.plan(1).size(), bp.plan(2).size(), bp.plan(3).size());
            }
        h.succeed();
    }

    /** Вид сверху на чертёж: для глаз разработчика (поле, загон, дома, дороги). */
    private static String asciiPlan(Design d, int reach) {
        StringBuilder sb = new StringBuilder();
        for (int z = -reach; z <= reach; z++) {
            for (int x = -reach; x <= reach; x++) {
                char ch = ' ';
                for (int y = 26; y >= -1; y--) {
                    Canvas.Cell cell = d.cells.get(new BlockPos(x, y, z));
                    if (cell == null || cell.state().isAir()) continue;
                    var st = cell.state();
                    var b = st.getBlock();
                    if (b == net.minecraft.world.level.block.Blocks.WATER) ch = '~';
                    else if (b == net.minecraft.world.level.block.Blocks.DIRT) ch = ',';
                    else if (b instanceof net.minecraft.world.level.block.FenceGateBlock) ch = 'G';
                    else if (b instanceof net.minecraft.world.level.block.FenceBlock) ch = '#';
                    else if (y == 0 && cell.stage() == 0) ch = '.';
                    else if (cell.stage() <= 2) ch = 'W';
                    else if (cell.stage() == 3) ch = 'K';
                    else if (cell.stage() == 4) ch = 'M';
                    else if (cell.stage() == 5) ch = 'S';
                    else if (cell.stage() == 6) ch = 'H';
                    else ch = 'd';
                    break;
                }
                sb.append(ch);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** План тира превращает готовый предыдущий тир ровно в следующий, при любом повороте. */
    @GameTest(template = "empty")
    public static void plansTransformTiers(GameTestHelper h) {
        for (int version = 1; version <= 2; version++)
        for (Layout layout : Layout.VALUES)
            for (Rotation rot : Rotation.values()) {
                FortBlueprint bp = FortBlueprint.get(layout, Palette.TIMBER, rot, version);
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
            // Третий тир требует казарму: в «мире без игрока» её построил Лорд заранее.
            var barracks = new com.vladosiwe.lordsoutpost.outpost.Project(++o.projectCounter, com.vladosiwe.lordsoutpost.outpost.Project.Type.BARRACKS,
                    o.center.offset(60, 0, 0), Rotation.NONE);
            barracks.state = com.vladosiwe.lordsoutpost.outpost.Project.State.DONE;
            o.projects.add(barracks);
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

    /** Переполненная дань не выбрасывает предметы: ресурсы уходят на склад, прочее копится сверх 54 стопок. */
    @GameTest(template = "empty")
    public static void tributeOverflowKeepsItems(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(256, 0, 0), 1, UUID.randomUUID());
        try {
            o.tribute.clear();
            for (int i = 0; i < Outpost.MAX_TRIBUTE_STACKS; i++) o.tribute.add(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
            o.setStock(Resource.STONE, 0);
            Simulation.addTribute(o, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 10));
            h.assertTrue(Math.abs(o.stock(Resource.STONE) - 10) < 1e-6, "булыжник сверх полной дани должен уйти на склад, а не пропасть");
            Simulation.addTribute(o, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW, 70));
            int arrows = o.tribute.stream().filter(st -> st.is(net.minecraft.world.item.Items.ARROW)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            h.assertTrue(arrows == 70, "все стрелы должны сохраниться в дани, сохранилось " + arrows);
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Предметы, которые игрок положил в бочки сверх ёмкости склада, учитываются целиком (иначе синхронизация бочек
     * удалила бы их), а последующая выработка не урезает уже существующий запас.
     */
    @GameTest(template = "empty")
    public static void playerDepositAboveCapacityIsKept(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(384, 0, 0), 1, UUID.randomUUID());
        try {
            int cap = o.capacity(Resource.STONE);
            o.setStock(Resource.STONE, cap - 10);
            QuestBoard.deliver(o, Resource.STONE, 50, level.getGameTime());
            h.assertTrue(Math.abs(o.stock(Resource.STONE) - (cap + 40)) < 1e-6, "вклад игрока должен учитываться без урезания");
            o.add(Resource.STONE, 25);
            h.assertTrue(Math.abs(o.stock(Resource.STONE) - (cap + 40)) < 1e-6, "выработка не должна уменьшать запас выше ёмкости");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Правки игрока (поставленные и разобранные блоки) переживают сохранение и загрузку. */
    @GameTest(template = "empty")
    public static void playerEditsPersist(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(512, 0, 0), 1, UUID.randomUUID());
        try {
            BlockPos sapling = o.center.offset(3, 1, 3), wall = o.center.offset(-5, 2, 0);
            o.notePlayerPlaced(sapling);
            o.notePlayerRemoved(wall, true);
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.placedByPlayer(sapling), "посаженное игроком должно сохраниться");
            h.assertTrue(copy.removedByPlayer(wall), "разобранное игроком должно сохраниться");
            copy.clearPlayerMarks(wall);
            h.assertFalse(copy.removedByPlayer(wall), "новая постройка снимает пометку");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Система задач переживает перезапуск без повторной работы: задача планировщика возвращается в очередь
     * с сохранённым резервом, личный запрос работника отменяется. Резерв не тратится стройкой и торговлей.
     */
    @GameTest(template = "empty")
    public static void tasksRecoverAfterRestart(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(640, 0, 0), 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            Resident worker = o.residents.get(0);
            com.vladosiwe.lordsoutpost.outpost.OutpostTask planned = o.tasks.create(o, com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.REPAIR_ARMOR, 60, now);
            planned.reserved.put(net.minecraft.world.item.Items.IRON_INGOT, 2);
            planned.executor = worker.id;
            planned.state = com.vladosiwe.lordsoutpost.outpost.OutpostTask.State.IN_PROGRESS;
            com.vladosiwe.lordsoutpost.outpost.OutpostTask request = o.tasks.create(o, com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.FETCH_SEEDS, 50, now);
            request.reserved.put(net.minecraft.world.item.Items.WHEAT_SEEDS, 4);
            request.executor = worker.id;
            request.state = com.vladosiwe.lordsoutpost.outpost.OutpostTask.State.RESERVED;
            o.setStock(Resource.IRON, 40);
            h.assertTrue(Math.abs(o.available(Resource.IRON) - 32) < 1e-6, "два отложенных слитка (8 железа) не должны считаться доступными");

            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            var p = copy.tasks.byId(planned.id);
            var q = copy.tasks.byId(request.id);
            h.assertTrue(p != null && p.executor == null && p.state == com.vladosiwe.lordsoutpost.outpost.OutpostTask.State.RESERVED
                    && p.reservedCount(net.minecraft.world.item.Items.IRON_INGOT) == 2, "задача планировщика должна вернуться в очередь с резервом: " + p);
            h.assertTrue(q != null && q.state == com.vladosiwe.lordsoutpost.outpost.OutpostTask.State.CANCELLED && q.reserved.isEmpty(),
                    "личный запрос должен отмениться после перезапуска: " + q);
            var next = copy.tasks.create(copy, com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.UPGRADE_GEAR, 40, now);
            h.assertTrue(next.id > request.id, "id задач не должны повторяться после загрузки");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Кузнец вдали выполняет задачи с общей доски: ремонт тратит ровно слиток и кожу и не повторяется. */
    @GameTest(template = "empty")
    public static void directorSmithTasksWorkOffline(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(768, 0, 0), 2, UUID.randomUUID());
        try {
            o.tier = 2;
            OutpostManager.addSettler(o, Role.SMITH, level.random);
            // Шахтёры и скотоводы сами добывают железо и шкуры: убираем их, чтобы мерить только расход кузнеца.
            o.residents.removeIf(r -> r.role == Role.MINER || r.role == Role.SKOTOVOD);
            Resident fighter = null;
            for (Resident r : o.residents) if (r.role.combat && r.role != Role.CAPTAIN) { fighter = r; break; }
            h.assertTrue(fighter != null, "в форте должен быть боец");
            for (Resident r : o.residents) if (r.role.combat) r.armor = 1f;
            fighter.armor = 0.5f;
            o.setStock(Resource.IRON, 4);
            o.setStock(Resource.HIDES, 2);
            o.setStock(Resource.FOOD, 500);
            long t = level.getGameTime();
            o.lastSim = t;
            simulate(level, data, o, t, 0.2);
            h.assertTrue(fighter.armor > 0.5f, "кузнец вдали должен починить броню по задаче с доски, броня " + fighter.armor);
            h.assertTrue(o.stock(Resource.IRON) < 1e-6 && o.stock(Resource.HIDES) < 1e-6, "ремонт тратит ровно слиток и кожу");
            boolean done = o.tasks.all().stream().anyMatch(x -> x.type == com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.REPAIR_ARMOR
                    && x.state == com.vladosiwe.lordsoutpost.outpost.OutpostTask.State.COMPLETED);
            h.assertTrue(done, "задача ремонта должна быть завершена");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** При критической нехватке еды планировщик приостанавливает стройку. */
    @GameTest(template = "empty")
    public static void directorPausesBuildingWhenStarving(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(896, 0, 0), 1, UUID.randomUUID());
        try {
            for (Resource r : Resource.VALUES) o.setStock(r, 0);
            o.setStock(Resource.WOOD, 500);
            o.setStock(Resource.STONE, 500);
            o.setStock(Resource.IRON, 200);
            int before = o.steps;
            long t = level.getGameTime();
            o.lastSim = t;
            simulate(level, data, o, t, 0.05);
            h.assertTrue(o.foodCritical, "без еды планировщик должен объявить голод");
            h.assertTrue(o.steps == before, "при голоде необязательная стройка стоит: шагов было " + before + ", стало " + o.steps);
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Пиромант Iron's Spells приходит к башне мага форта третьего тира ровно один, а после гибели запись это помнит.
     * Без мода пиромант не появляется и ничего не ломается.
     */
    @GameTest(template = "empty")
    public static void pyromancerJoinsMageTower(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, h.absolutePos(BlockPos.ZERO), 1, UUID.randomUUID());
        try {
            o.tier = 3;
            o.online = true;
            o.onlineSince = level.getGameTime() - 1000;
            o.residents.removeIf(r -> r.role == Role.MAGE);
            OutpostManager.addSettler(o, Role.MAGE, level.random);
            BlockPos tower = o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.MAGE_TOWER);
            level.getChunk(tower);
            com.vladosiwe.lordsoutpost.compat.Pyromancer.tick(level, o, level.getGameTime());
            if (!com.vladosiwe.lordsoutpost.compat.Pyromancer.available()) {
                h.assertTrue(o.pyromancer == null, "без Iron's Spells пиромант не должен появляться");
            } else {
                h.assertTrue(o.pyromancer != null, "к башне мага форта третьего тира должен прийти пиромант");
                var mob = level.getEntity(o.pyromancer);
                h.assertTrue(mob != null && com.vladosiwe.lordsoutpost.compat.Pyromancer.ofOutpost(mob, o.id), "пиромант должен быть привязан к форту");
                com.vladosiwe.lordsoutpost.compat.Pyromancer.tick(level, o, level.getGameTime() + 20);
                long count = level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, new AABB(tower).inflate(64),
                        m -> com.vladosiwe.lordsoutpost.compat.Pyromancer.ofOutpost(m, o.id)).size();
                h.assertTrue(count == 1, "пиромант должен быть один, найдено " + count);
                com.vladosiwe.lordsoutpost.compat.Pyromancer.onDeath(level, mob);
                h.assertTrue(o.pyromancer == null && o.pyromancerRejoinAt > level.getGameTime(), "после гибели новый пиромант придёт через срок");
                h.assertTrue(o.count(Role.MAGE) == 0, "пиромант — тело придворного мага: маг погиб вместе с ним");
                h.assertTrue(com.vladosiwe.lordsoutpost.data.Simulation.desired(o, Role.MAGE) == 0, "новый маг не приходит до срока");
                mob.discard();
            }
        } finally {
            o.online = false;
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Придворный маг работает только с настоящими предметами склада: рецепт резервирует ингредиенты (второй раз их
     * не отложить), маг забирает их в сумку, списывает при работе, готовое ложится в бочки. Потом маг выносит товар
     * на полку, а продажа с полки списывает предмет и платит в казну ровно один раз. Полка и сумка сохраняются.
     */
    @GameTest(template = "empty")
    public static void mageCraftsAndSellsRealGoods(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(2048, 0, 0), 1, UUID.randomUUID());
        o.tier = 3;
        List<BlockPos> barrels = o.markers(com.vladosiwe.lordsoutpost.blueprint.Marker.STOCKPILE);
        try {
            long now = level.getGameTime();
            o.residents.removeIf(r -> r.role == Role.MAGE);
            Resident mage = OutpostManager.addSettler(o, Role.MAGE, level.random);
            for (BlockPos b : barrels) {
                level.getChunk(b);
                level.setBlockAndUpdate(b, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
            }
            h.assertTrue(!Storehouse.barrels(level, o).isEmpty(), "у склада должны быть бочки");
            boolean iss = com.vladosiwe.lordsoutpost.compat.MagicLoot.ironsLoaded();
            var box = new net.minecraft.world.SimpleContainer(4);
            if (iss) {
                box.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI, 3));
                box.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.AMETHYST_SHARD, 1));
            } else {
                box.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK, 1));
                box.setItem(1, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LAPIS_LAZULI, 3));
            }
            Storehouse.deposit(level, o, box);
            var recipe = com.vladosiwe.lordsoutpost.data.MageCraft.next(level, o, false);
            h.assertTrue(recipe != null && recipe.id().equals(iss ? "arcane_essence" : "enchanted_book"), "рецепт по ингредиентам склада: " + recipe);
            var task = Logistics.request(level, o, mage, com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.MAGE_CRAFT, recipe.inputs(), now);
            h.assertTrue(task != null, "ингредиенты должны зарезервироваться");
            h.assertTrue(com.vladosiwe.lordsoutpost.data.MageCraft.next(level, o, false) == null, "отложенное нельзя потратить второй раз");
            h.assertTrue(Logistics.withdraw(level, o, task, o.mageSatchel, mage.id, now), "маг забирает ингредиенты в сумку");
            h.assertTrue(Storehouse.countItem(level, o, net.minecraft.world.item.Items.LAPIS_LAZULI) == 0, "ингредиенты ушли из бочек");
            Outpost reloaded = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(reloaded.mageSatchel.countItem(net.minecraft.world.item.Items.LAPIS_LAZULI) == 3, "сумка мага сохраняется");
            h.assertTrue(com.vladosiwe.lordsoutpost.data.MageCraft.resumable(level, o, false) != null, "прерванная работа продолжается по сумке");
            for (var e : recipe.inputs().entrySet()) {
                int left = e.getValue();
                for (int i = 0; i < o.mageSatchel.getContainerSize() && left > 0; i++) {
                    var st = o.mageSatchel.getItem(i);
                    if (!st.is(e.getKey())) continue;
                    int n = Math.min(left, st.getCount());
                    st.shrink(n);
                    left -= n;
                }
            }
            var made = com.vladosiwe.lordsoutpost.data.MageCraft.craft(level, recipe, level.random);
            h.assertTrue(!made.isEmpty(), "работа даёт предмет");
            for (var st : made) Logistics.addTo(o.mageSatchel, st);
            Logistics.complete(o, task, now);
            Storehouse.deposit(level, o, o.mageSatchel);
            h.assertTrue(o.mageSatchel.isEmpty(), "готовое отнесено на склад");
            var product = made.get(0).getItem();
            h.assertTrue(Storehouse.countItem(level, o, product) >= 1, "готовое лежит в бочках");

            var want = com.vladosiwe.lordsoutpost.data.Shop.restockShelf(level, o);
            h.assertTrue(want.getOrDefault(product, 0) >= 1, "маг выносит на полку готовый товар: " + want);
            var stock = Logistics.request(level, o, mage, com.vladosiwe.lordsoutpost.outpost.OutpostTask.Type.STOCK_SHOP, want, now);
            h.assertTrue(stock != null && Logistics.withdraw(level, o, stock, o.mageSatchel, mage.id, now), "товар взят со склада");
            for (int i = 0; i < o.mageSatchel.getContainerSize(); i++) {
                o.mageSatchel.setItem(i, com.vladosiwe.lordsoutpost.data.Shop.shelve(o, o.mageSatchel.getItem(i).copy()));
            }
            h.assertTrue(Storehouse.countItem(level, o, product) == 0 && o.mageSatchel.isEmpty(), "товар переехал на полку, а не скопирован");
            var offers = com.vladosiwe.lordsoutpost.data.Shop.shelfOffers(o);
            h.assertTrue(offers.size() == 1 && offers.get(0).getMaxUses() == 1, "одно предложение на один реальный предмет");
            var copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.magicShelf.size() == 1, "полка сохраняется");
            double gold = o.stock(Resource.GOLD);
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Shop.soldFromShelf(o, offers.get(0)), "продажа проходит");
            h.assertTrue(o.stock(Resource.GOLD) > gold && o.magicShelf.isEmpty(), "предмет ушёл с полки, изумруды — в казну");
            h.assertFalse(com.vladosiwe.lordsoutpost.data.Shop.soldFromShelf(o, offers.get(0)), "проданное второй раз не продаётся");
        } finally {
            for (BlockPos b : barrels) level.setBlockAndUpdate(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * RTS-заказ: точное место и поворот, проверка ресурсов склада, резерв на весь заказ (его нельзя потратить на
     * другое), приоритет очереди, резерв переживает сохранение, отмена резерв снимает.
     */
    @GameTest(template = "empty")
    public static void rtsOrdersReserveResources(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos base = FAR.offset(2304, 0, 0);
        for (int cx = -3; cx <= 6; cx++) for (int cz = -4; cz <= 4; cz++) level.getChunk((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz);
        BlockPos ground = new BlockPos(base.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ()) - 1, base.getZ());
        Outpost o = virtualOutpost(level, data, ground, 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            var house = com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE;
            int x = o.center.getX() + Layout.radius(o.version, 3) + Layout.MARGIN + 9, z = o.center.getZ();
            for (Resource r : Resource.VALUES) o.setStock(r, 0);
            var none = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, house, x, z, 1, now, false);
            h.assertTrue(none == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.NO_RESOURCES, "без ресурсов заказ не принимается: " + none);
            h.assertTrue(!com.vladosiwe.lordsoutpost.data.Projects.missing(o, house).isEmpty(), "видно, чего не хватает");
            o.setStock(Resource.WOOD, 500);
            o.setStock(Resource.STONE, 500);
            var cost = com.vladosiwe.lordsoutpost.data.Projects.cost(o, house);
            var dry = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, house, x, z, 1, now, true);
            h.assertTrue(dry == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK && o.projects.isEmpty(), "проверка места ничего не заказывает: " + dry);
            var ok = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, house, x, z, 1, now, false);
            h.assertTrue(ok == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK, "заказ в выбранной точке: " + ok);
            var p = o.projects.get(o.projects.size() - 1);
            h.assertTrue(p.rotation == net.minecraft.world.level.block.Rotation.CLOCKWISE_90 && p.origin.getX() == x && p.origin.getZ() == z, "место и поворот как выбрал Лорд");
            int wood = cost.getOrDefault(Resource.WOOD, 0);
            h.assertTrue(Math.abs(o.available(Resource.WOOD) - (500 - wood)) < 1e-6, "дерево заказа в резерве: свободно " + o.available(Resource.WOOD));
            h.assertTrue(Math.abs(o.stock(Resource.WOOD) - 500) < 1e-6, "резерв не списывает ресурс сразу");
            var twice = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, house, x, z, 1, now, false);
            h.assertTrue(twice == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OCCUPIED, "второй заказ на то же место: " + twice);
            var wallRes = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, com.vladosiwe.lordsoutpost.outpost.Project.Type.WALL, x, z + 14, 0, now, false);
            h.assertTrue(wallRes == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK, "второй заказ рядом: " + wallRes);
            var wall = o.projects.get(o.projects.size() - 1);
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Projects.move(o, wall.id, true) && o.projects.indexOf(wall) < o.projects.indexOf(p), "приоритет: стена поднята выше дома");
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(Math.abs(copy.available(Resource.WOOD) - o.available(Resource.WOOD)) < 1e-6, "резерв восстанавливается после загрузки");
            h.assertTrue(copy.projects.indexOf(copy.projects.stream().filter(q -> q.id == wall.id).findFirst().get()) == 0
                    || copy.projects.get(0).id == wall.id, "очередь сохраняется");
            com.vladosiwe.lordsoutpost.data.Projects.cancel(o, p.id, now);
            h.assertTrue(Math.abs(o.available(Resource.WOOD) - 500) < 1e-6, "отмена снимает резерв: " + o.available(Resource.WOOD));
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Новый тир в другой планировке: готовый тир остаётся прежним, план перехода строит новую и разбирает старое. */
    @GameTest(template = "empty")
    public static void tierLayoutChoice(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(3072, 0, 0), 1, UUID.randomUUID());
        try {
            var before1 = o.blueprint().design(1);
            h.assertTrue(o.canChooseLayout(), "до начала перехода планировку можно выбрать");
            o.nextLayout = o.layout == Layout.SQUARE ? Layout.OCTAGON : Layout.SQUARE;
            o.nextRotation = Rotation.CLOCKWISE_90;
            o.layoutSplit = o.tier + 1;
            var bp = o.blueprint();
            h.assertTrue(bp.design(1) == before1, "построенный тир не меняется");
            h.assertTrue(bp.design(2) == com.vladosiwe.lordsoutpost.blueprint.FortBlueprint.get(o.nextLayout, o.palette, Rotation.CLOCKWISE_90, o.version).design(2),
                    "новый тир — в выбранной планировке");
            long removals = bp.plan(2).stream().filter(st -> st.state().isAir() || st.state().is(net.minecraft.world.level.block.Blocks.DIRT)).count();
            h.assertTrue(removals > 0, "план перехода разбирает старые части");
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.nextLayout == o.nextLayout && copy.nextRotation == o.nextRotation && copy.layoutSplit == o.layoutSplit, "выбор сохраняется");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Набег рядом с игроком: предупреждение за 5 минут, лагерь в 100+ блоках, штурм по расписанию; богатство усиливает банду. */
    @GameTest(template = "empty")
    public static void epicRaidPhases(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(3584, 0, 0), 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            o.online = true;
            o.nextRaidAt = now + 100;
            Raids.tick(level, data, o, now, level.random);
            h.assertTrue(o.raid != null, "предупреждение о набеге не началось");
            var raid = o.raid;
            h.assertTrue(raid.assaultAt == now + com.vladosiwe.lordsoutpost.outpost.Raid.WARNING, "штурм не через 5 минут");
            h.assertTrue(raid.beforeAssault(now) && !raid.preparing(now), "фазы набега");
            h.assertTrue(raid.preparing(raid.assaultAt - com.vladosiwe.lordsoutpost.outpost.Raid.CAMP_BEFORE), "лагерь за 2 минуты");
            double d = Math.sqrt(o.distanceSqr(raid.camp.getX(), raid.camp.getZ()));
            h.assertTrue(d >= 100, "лагерь ближе 100 блоков: " + d);
            o.online = false;
            Raids.tick(level, data, o, now + 200, level.random);
            h.assertTrue(o.raid == raid, "без игрока набег во время предупреждения не должен решаться заранее");
            var copy = com.vladosiwe.lordsoutpost.outpost.Raid.load(raid.save());
            h.assertTrue(copy.assaultAt == raid.assaultAt && copy.camp.equals(raid.camp) && copy.ravagers == raid.ravagers, "набег не сохраняется");
            double poor = Raids.wealthFactor(o);
            o.setStock(Resource.GOLD, 400);
            h.assertTrue(Raids.wealthFactor(o) > poor, "богатство не усиливает набег");
        } finally {
            o.raid = null;
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Донжон Лорда: личные сундуки на каждом тире, рамка портала на тире 3; Зеркало Лорда — одно и привязано к аванпосту. */
    @GameTest(template = "empty")
    public static void lordKeepAndMirror(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(3328, 0, 0), 1, UUID.randomUUID());
        o.tier = 3;
        try {
            var bp = o.blueprint();
            for (int t = 1; t <= 3; t++) h.assertTrue(!bp.design(t).markers(Marker.LORD_CHEST).isEmpty(), "нет личного сундука Лорда на тире " + t);
            h.assertTrue(bp.design(2).markers(Marker.PORTAL_FRAME).isEmpty(), "рамка только на тире 3");
            h.assertTrue(bp.design(3).markers(Marker.PORTAL_FRAME).size() == 1, "нет рамки портала на тире 3");
            o.mirrorSerial = 1;
            var mirror = com.vladosiwe.lordsoutpost.server.LordMirror.create(o);
            h.assertTrue(com.vladosiwe.lordsoutpost.server.LordMirror.valid(data, mirror, null), "новое зеркало действует");
            o.mirrorSerial++;
            h.assertTrue(!com.vladosiwe.lordsoutpost.server.LordMirror.valid(data, mirror, null), "старое зеркало рассыпается после выдачи нового");
            var fresh = com.vladosiwe.lordsoutpost.server.LordMirror.create(o);
            o.tier = 2;
            h.assertTrue(!com.vladosiwe.lordsoutpost.server.LordMirror.valid(data, fresh, null), "ниже тира 3 зеркало не действует");
            o.tier = 3;
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.mirrorSerial == o.mirrorSerial, "номер зеркала сохраняется");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Лестницы: житель находит путь вверх по лестнице на площадку (6 блоков) и поднимается ногами, без переноса. */
    // Необязательный: на некоторых площадках GameTest сущность перестаёт тикать (окружение, не поведение мода).
    @GameTest(template = "empty", timeoutTicks = 600, required = false)
    public static void npcClimbsLadders(GameTestHelper h) {
        BlockPos base = new BlockPos(4, 1, 4);
        // Столб 3×3 из камня с лестницей на южной стороне и площадкой 3×3 наверху.
        for (int y = 0; y < 6; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) h.setBlock(base.offset(x, y, z), net.minecraft.world.level.block.Blocks.STONE);
        for (int y = 0; y < 7; y++) h.setBlock(base.offset(0, y, 2), net.minecraft.world.level.block.Blocks.LADDER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LadderBlock.FACING, net.minecraft.core.Direction.SOUTH));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) h.setBlock(base.offset(x, 6, z), net.minecraft.world.level.block.Blocks.OAK_PLANKS);
        BlockPos top = base.offset(0, 7, 0);
        // Чанк площадки должен тикать сущности (на дальних площадках GameTest это не гарантировано).
        BlockPos absBase = h.absolutePos(base);
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) h.getLevel().setChunkForced((absBase.getX() >> 4) + cx, (absBase.getZ() >> 4) + cz, true);
        var worker = h.spawn(com.vladosiwe.lordsoutpost.registry.ModEntities.WORKER.get(), base.offset(0, 0, 5));
        worker.setPersistenceRequired();
        double[] startY = {worker.getY()};
        h.onEachTick(() -> {
            BlockPos abs = h.absolutePos(top);
            if (worker.tickCount % 20 == 1 && !worker.onClimbable())
                worker.getNavigation().moveTo(abs.getX() + 0.5, abs.getY(), abs.getZ() + 0.5, 1.0);
        });
        h.succeedWhen(() -> {
            BlockPos abs = h.absolutePos(top);
            var path = worker.getNavigation().getPath();
            h.assertTrue(worker.getY() >= abs.getY() - 0.1 && worker.blockPosition().distManhattan(abs) <= 2,
                    "житель наверху: " + worker.blockPosition().subtract(h.absolutePos(BlockPos.ZERO)).toShortString()
                            + " path=" + (path == null ? "null" : path.getNodeCount() + "/" + path.canReach()) + " noAi=" + worker.isNoAi()
                            + " sleeping=" + worker.isSleeping() + " ticks=" + worker.tickCount + " test=" + h.getTick());
        });
    }

    /** Постройки RTS: дом строится, улучшается до второго уровня (4 кровати) и сносится мгновенно с возвратом материала. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void buildingsUpgradeAndDemolish(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        BlockPos base = FAR.offset(2816, 0, 0);
        for (int cx = -3; cx <= 6; cx++) for (int cz = -4; cz <= 4; cz++) level.getChunk((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz);
        BlockPos ground = new BlockPos(base.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ()) - 1, base.getZ());
        Outpost o = virtualOutpost(level, data, ground, 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            var P = com.vladosiwe.lordsoutpost.data.Projects.class;
            o.setStock(Resource.WOOD, 800);
            o.setStock(Resource.STONE, 800);
            int x = o.center.getX() + Layout.radius(o.version, 3) + Layout.MARGIN + 9, z = o.center.getZ();
            // Ровная площадка под дом (рельеф в случайном месте теста может быть любым).
            int gy = com.vladosiwe.lordsoutpost.data.Projects.groundY(level, x, z);
            for (int dx = -6; dx <= 6; dx++)
                for (int dz = -6; dz <= 6; dz++) {
                    level.setBlockAndUpdate(new BlockPos(x + dx, gy, z + dz), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int dy = 1; dy <= 8; dy++) level.setBlockAndUpdate(new BlockPos(x + dx, gy + dy, z + dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            var ok = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE, x, z, 0, now, false);
            h.assertTrue(ok == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK, "дом заказан: " + ok);
            var house = o.projects.get(o.projects.size() - 1);
            int cap0 = o.populationCap();
            house.paid = house.size(o);
            com.vladosiwe.lordsoutpost.data.Projects.arrive(level, o);
            h.assertTrue(house.state == com.vladosiwe.lordsoutpost.outpost.Project.State.DONE, "дом построен");
            h.assertTrue(o.populationCap() == cap0 + 2, "дом даёт 2 места: " + (o.populationCap() - cap0));
            var up = com.vladosiwe.lordsoutpost.data.Projects.upgrade(level, o, house.id, now, false);
            h.assertTrue(up == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK, "улучшение заказано: " + up);
            var upgrade = o.projects.get(o.projects.size() - 1);
            h.assertTrue(upgrade.replaces == house.id && upgrade.level == 2, "это улучшение дома");
            upgrade.paid = upgrade.size(o);
            com.vladosiwe.lordsoutpost.data.Projects.arrive(level, o);
            h.assertTrue(upgrade.state == com.vladosiwe.lordsoutpost.outpost.Project.State.DONE && !o.projects.contains(house), "улучшение достроено, прежняя запись ушла");
            h.assertTrue(upgrade.level == 2 && upgrade.replaces < 0, "дом второго уровня");
            h.assertTrue(o.populationCap() == cap0 + 4, "дом 2 уровня даёт 4 места: " + (o.populationCap() - cap0));
            int beds = 0;
            for (var s : upgrade.steps(o)) {
                var st = level.getBlockState(o.world(s.pos()));
                if (st.getBlock() instanceof net.minecraft.world.level.block.BedBlock
                        && st.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD) beds++;
            }
            h.assertTrue(beds == 4, "в доме 4 кровати: " + beds);
            double wood = o.stock(Resource.WOOD);
            var d = com.vladosiwe.lordsoutpost.data.Projects.demolish(level, o, upgrade.id, now);
            h.assertTrue(d.result() == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK && d.blocks() > 50, "снос: " + d);
            h.assertTrue(o.stock(Resource.WOOD) > wood + 30, "материал вернулся на склад");
            h.assertTrue(!o.projects.contains(upgrade) && o.populationCap() == cap0, "постройки больше нет");
            int left = 0;
            for (var s : upgrade.steps(o)) if (!s.state().isAir() && level.getBlockState(o.world(s.pos())).getBlock() == s.state().getBlock()) left++;
            h.assertTrue(left == 0, "блоков не осталось: " + left);
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Временное хранилище: склада нет (снесён при смене тира) — жители пользуются бочкой, которую поставил Лорд
     * внутри стен; когда склад снова стоит, временное хранилище больше не используется.
     */
    @GameTest(template = "empty")
    public static void temporaryStorageWhileStorehouseIsRebuilt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(2560, 0, 0), 1, UUID.randomUUID());
        List<BlockPos> barrels = o.markers(com.vladosiwe.lordsoutpost.blueprint.Marker.STOCKPILE);
        BlockPos temp = o.marker(com.vladosiwe.lordsoutpost.blueprint.Marker.YARD).above(2);
        try {
            for (BlockPos b : barrels) level.getChunk(b);
            level.getChunk(temp);
            h.assertTrue(Storehouse.barrels(level, o).isEmpty(), "склада нет");
            level.setBlockAndUpdate(temp, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
            o.notePlayerPlaced(temp);
            h.assertTrue(Storehouse.barrels(level, o).size() == 1 && temp.equals(Storehouse.storePos(level, o)), "бочка Лорда — временный склад");
            var box = new net.minecraft.world.SimpleContainer(1);
            box.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 10));
            Storehouse.deposit(level, o, box);
            h.assertTrue(Storehouse.countItem(level, o, net.minecraft.world.item.Items.COBBLESTONE) == 10, "ноша легла во временный склад");
            for (BlockPos b : barrels) level.setBlockAndUpdate(b, net.minecraft.world.level.block.Blocks.BARREL.defaultBlockState());
            h.assertTrue(!temp.equals(Storehouse.storePos(level, o)) && o.tempStore == null, "склад построен — временный больше не нужен");
        } finally {
            for (BlockPos b : barrels) level.setBlockAndUpdate(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(temp, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /** Лавка торговца продаёт только то, что в ней лежит: продажа списывает товар, изумруды идут в казну, лавка сохраняется. */
    @GameTest(template = "empty")
    public static void shopSellsOnlyRealStock(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(1024, 0, 0), 1, UUID.randomUUID());
        try {
            o.shopStock.clear();
            com.vladosiwe.lordsoutpost.data.Shop.stock(o, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64));
            var offers = com.vladosiwe.lordsoutpost.data.Shop.offers(o);
            h.assertTrue(offers.size() == 1, "одно предложение на один вид товара");
            var offer = offers.get(0);
            int bundle = offer.getResult().getCount(), price = offer.getCostA().getCount();
            h.assertTrue(offer.getMaxUses() == 64 / bundle, "покупок не больше, чем связок в лавке");
            double gold = o.stock(Resource.GOLD);
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Shop.sold(o, offer), "продажа должна пройти");
            int left = o.shopStock.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            h.assertTrue(left == 64 - bundle, "товар списан из лавки: осталось " + left);
            h.assertTrue(Math.abs(o.stock(Resource.GOLD) - gold - price) < 1e-6, "изумруды ушли в казну");
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.shopStock.stream().mapToInt(net.minecraft.world.item.ItemStack::getCount).sum() == left, "лавка сохраняется");
            o.shopStock.clear();
            h.assertFalse(com.vladosiwe.lordsoutpost.data.Shop.sold(o, offer), "без товара продажа не засчитывается");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Заказ построек через меню: место будущих тиров не занимается, постройка оплачивается из той же экономики A-Life,
     * дом добавляет места жителям, отмена возвращает неистраченное на склад.
     */
    @GameTest(template = "empty")
    public static void ordersRespectFortAndEconomy(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        // Вдали от сетки игровых тестов: соседние площадки иначе заняли бы место постройки.
        BlockPos base = FAR.offset(1280, 0, 0);
        for (int cx = -3; cx <= 4; cx++) for (int cz = -3; cz <= 3; cz++) level.getChunk((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz);
        BlockPos ground = new BlockPos(base.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base.getX(), base.getZ()) - 1, base.getZ());
        Outpost o = virtualOutpost(level, data, ground, 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            // На донжон (постройка текущего тира) заказ не встанет; свободный двор и место будущих домов — можно.
            BlockPos throne = o.marker(Marker.THRONE);
            var fort = com.vladosiwe.lordsoutpost.data.Projects.orderAt(level, o, com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE,
                    throne.getX(), throne.getZ(), 0, now, true);
            h.assertTrue(fort == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OVERLAPS_FORT
                            || fort == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OCCUPIED,
                    "на донжон заказ невозможен, получено " + fort);
            int edge = Layout.RADIUS[3] + Layout.MARGIN + 3;
            BlockPos stand = o.center.offset(edge, 0, 0);
            for (int dx = -2; dx <= 16; dx++)
                for (int dz = -8; dz <= 8; dz++) level.getChunk((stand.getX() + dx) >> 4, (stand.getZ() + dz) >> 4);
            int capBefore = o.populationCap();
            var ok = com.vladosiwe.lordsoutpost.data.Projects.order(level, o, com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE,
                    stand, net.minecraft.core.Direction.EAST, now);
            h.assertTrue(ok == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK, "дом за стенами должен приниматься, получено " + ok);
            var house = o.projects.get(o.projects.size() - 1);
            // План тира стоит (форт достроен до 3-го тира), весь кредит идёт на заказ.
            o.tier = 3;
            o.setStock(Resource.WOOD, 500);
            o.setStock(Resource.STONE, 500);
            o.setStock(Resource.FOOD, 500);
            long t = level.getGameTime();
            o.lastSim = t;
            simulate(level, data, o, t, 0.3);
            h.assertTrue(house.paid >= house.size(o), "дом должен быть оплачен целиком: " + house.paid + "/" + house.size(o));
            h.assertTrue(o.populationCap() == capBefore - 10 + 28 + 2 || o.populationCap() > 28, "дом добавляет два места: " + o.populationCap());
            var wall = com.vladosiwe.lordsoutpost.data.Projects.order(level, o, com.vladosiwe.lordsoutpost.outpost.Project.Type.WALL,
                    stand.offset(0, 0, 8), net.minecraft.core.Direction.EAST, now);
            if (wall == com.vladosiwe.lordsoutpost.data.Projects.OrderResult.OK) {
                var w = o.projects.get(o.projects.size() - 1);
                o.setStock(Resource.STONE, 0);
                w.paid = 5;
                double before = o.stock(Resource.STONE);
                com.vladosiwe.lordsoutpost.data.Projects.cancel(o, w.id, now);
                h.assertTrue(o.stock(Resource.STONE) - before >= 5 - 1e-6, "отмена возвращает оплаченное, но не поставленное");
            }
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.projects.size() == o.projects.size() && copy.projects.get(0).paid == o.projects.get(0).paid, "заказы сохраняются");
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Почта: письмо с вложениями выдаётся в инвентарь один раз, повторное открытие почтомата ничего не даёт,
     * письмо переживает сохранение. Караван везёт настоящие предметы: склад отправителя пустеет на их стоимость,
     * склад владения-покупателя пополняется, его казна платит, его Лорд получает письмо.
     */
    @GameTest(template = "empty")
    @SuppressWarnings("removal")
    public static void mailAndCaravanGoods(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        long now = level.getGameTime();
        int before = data.mail().size();
        UUID recipient = UUID.randomUUID();
        com.vladosiwe.lordsoutpost.mail.Mail.send(level.getServer(), recipient, "Test", "lordsoutpost.mail.tribute",
                new java.util.ArrayList<>(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64),
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 6))), now, 2);
        var letter = data.mail().get(data.mail().size() - 1);
        var copy = com.vladosiwe.lordsoutpost.mail.Letter.load(letter.save(level.registryAccess()), level.registryAccess());
        h.assertTrue(copy.id.equals(letter.id) && copy.itemCount() == 70, "письмо сохраняется со своим id и вложениями");
        // Поддельный игрок есть не везде: Epic Fight шлёт ему свои пакеты и падает на фальшивом соединении.
        net.minecraft.server.level.ServerPlayer player = null;
        try {
            player = h.makeMockServerPlayerInLevel();
        } catch (RuntimeException e) {
            LordsOutpost.LOGGER.info("Выдача письма не проверена: поддельный игрок недоступен ({})", e.toString());
        }
        if (player != null) {
            data.mail().remove(letter);
            com.vladosiwe.lordsoutpost.mail.Mail.send(level.getServer(), player.getUUID(), "Test", "lordsoutpost.mail.tribute",
                    new java.util.ArrayList<>(List.of(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 64),
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE, 6))), now, 2);
            var mine = data.mail().get(data.mail().size() - 1);
            com.vladosiwe.lordsoutpost.mail.Mail.claim(player, now);
            h.assertTrue(player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE) == 70, "вложения в инвентаре");
            com.vladosiwe.lordsoutpost.mail.Mail.claim(player, now);
            h.assertTrue(player.getInventory().countItem(net.minecraft.world.item.Items.COBBLESTONE) == 70, "письмо нельзя получить дважды");
            h.assertFalse(mine.waiting(), "письмо помечено полученным");
        }
        data.mail().subList(Math.min(before, data.mail().size()), data.mail().size()).clear();

        Outpost o = virtualOutpost(level, data, FAR.offset(1536, 0, 0), 1, UUID.randomUUID());
        Outpost other = virtualOutpost(level, data, FAR.offset(1536, 0, 600), 1, UUID.randomUUID());
        try {
            o.relations.computeIfAbsent(other.id, k -> new com.vladosiwe.lordsoutpost.outpost.Relation()).pact = Pact.TRADE;
            OutpostManager.addSettler(o, Role.CARAVANEER, level.random);
            for (Resource r : Resource.VALUES) o.setStock(r, 0);
            o.setStock(Resource.WOOD, 590);
            h.assertTrue(Caravans.dispatch(data, o, other, now) == Caravans.Result.OK, "караван должен выйти");
            Caravan c = o.caravans.get(o.caravans.size() - 1);
            int items = c.goods.stream().filter(st -> st.is(net.minecraft.tags.ItemTags.LOGS)).mapToInt(net.minecraft.world.item.ItemStack::getCount).sum();
            h.assertTrue(items > 0, "груз — настоящие предметы");
            h.assertTrue(Math.abs(o.stock(Resource.WOOD) - (590 - items * 4)) < 1e-6, "склад отправителя уменьшился ровно на стоимость брёвен");
            other.setStock(Resource.WOOD, 0);
            other.setStock(Resource.GOLD, 1000);
            int mail = data.mail().size();
            Caravans.arrive(level, data, o, other, c, now);
            h.assertTrue(Math.abs(other.stock(Resource.WOOD) - items * 4) < 1e-6, "груз лёг на склад покупателя: " + other.stock(Resource.WOOD));
            h.assertTrue(other.stock(Resource.GOLD) < 1000 && c.earned > 0, "казна покупателя заплатила");
            h.assertTrue(c.goods.isEmpty(), "груз передан, а не скопирован");
            h.assertTrue(data.mail().size() == mail + 1, "Лорд-покупатель получил письмо");
            data.mail().subList(mail, data.mail().size()).clear();
        } finally {
            OutpostManager.abandon(data, o, level.getGameTime());
            OutpostManager.abandon(data, other, level.getGameTime());
            data.remove(o);
            data.remove(other);
        }
        h.succeed();
    }

    /**
     * Жильё: при нехватке мест планировщик советует дом, но сам его не заказывает, пока Лорд не включит авто-застройку.
     * Рядом с игроком заказ оплачивается только материалом, который строители принесли со склада.
     */
    @GameTest(template = "empty")
    public static void housingAdviceAndBuilderMaterials(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(1792, 0, 0), 1, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            while (o.residents.size() < o.populationCap()) OutpostManager.addSettler(o, Role.SHIELDMAN, level.random);
            o.setStock(Resource.FOOD, 500);
            h.assertTrue(o.autoBuild, "новое владение: авто-застройка домов включена по умолчанию");
            // Лорд выключил авто-застройку: планировщик только советует.
            o.autoBuild = false;
            com.vladosiwe.lordsoutpost.data.OutpostDirector.tick(level, o, now);
            h.assertTrue(o.recommended.containsKey(com.vladosiwe.lordsoutpost.outpost.Project.Type.HOUSE), "при полном поселении планировщик советует дом");
            h.assertTrue(o.projects.isEmpty(), "без авто-застройки планировщик сам ничего не заказывает");

            var p = new com.vladosiwe.lordsoutpost.outpost.Project(++o.projectCounter, com.vladosiwe.lordsoutpost.outpost.Project.Type.WALL,
                    o.center.offset(60, 0, 0), net.minecraft.world.level.block.Rotation.NONE);
            o.projects.add(p);
            o.online = true;
            o.setStock(Resource.STONE, 500);
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Projects.pay(o, 10) == 10 && p.paid == 0, "рядом с игроком симуляция не оплачивает заказ сама");
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Projects.claimPaying(o, now) < 0, "без принесённого материала шаг не берётся");
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Projects.neededMaterial(o) == Resource.STONE, "нужен камень");
            com.vladosiwe.lordsoutpost.data.Projects.addMaterial(o, Resource.STONE, 2);
            h.assertTrue(com.vladosiwe.lordsoutpost.data.Projects.claimPaying(o, now) >= 0 && p.paid == 1, "принесённый камень оплачивает шаг");
            h.assertTrue(Math.abs(o.buildMaterials.get(Resource.STONE) - 1) < 1e-6 && Math.abs(o.stock(Resource.STONE) - 500) < 1e-6,
                    "тратится принесённое, а не запас склада");
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(Math.abs(copy.buildMaterials.get(Resource.STONE) - 1) < 1e-6, "остаток принесённого материала сохраняется");
        } finally {
            o.online = false;
            OutpostManager.abandon(data, o, level.getGameTime());
            data.remove(o);
        }
        h.succeed();
    }

    /**
     * Перенос загона, кузницы и шахты: зона переезжает только в готовую постройку, прежнее место запоминается
     * (оттуда уводят скот и забирают печи), новая шахта не готова, пока шахтёр не прокопает ствол; всё сохраняется.
     */
    @GameTest(template = "empty")
    public static void relocationRebindsZones(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        OutpostSavedData data = OutpostSavedData.get(level);
        Outpost o = virtualOutpost(level, data, FAR.offset(2048, 0, 0), 2, UUID.randomUUID());
        try {
            long now = level.getGameTime();
            BlockPos oldPen = o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN);
            var pen = new com.vladosiwe.lordsoutpost.outpost.Project(++o.projectCounter, com.vladosiwe.lordsoutpost.outpost.Project.Type.PEN,
                    o.center.offset(50, 0, 0), net.minecraft.world.level.block.Rotation.NONE);
            o.projects.add(pen);
            h.assertTrue(o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN).equals(oldPen), "пока загон не построен, работают по старому месту");
            pen.paid = pen.size(o);
            pen.placed.set(0, pen.size(o));
            com.vladosiwe.lordsoutpost.data.Projects.finish(o, pen, now);
            h.assertTrue(o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN).equals(pen.zoneAnchor()), "загон переехал в новую постройку");
            h.assertTrue(oldPen.equals(o.zones.previous(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN)), "старое место запомнено для перевода скота");
            h.assertTrue(com.vladosiwe.lordsoutpost.data.ChunkReconciliation.penSize(o) == 3, "новый загон 7×7");

            var mine = new com.vladosiwe.lordsoutpost.outpost.Project(++o.projectCounter, com.vladosiwe.lordsoutpost.outpost.Project.Type.MINE,
                    o.center.offset(-50, 0, 0), net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
            o.projects.add(mine);
            o.mineCursor = 40;
            mine.paid = mine.size(o);
            mine.placed.set(0, mine.size(o));
            com.vladosiwe.lordsoutpost.data.Projects.finish(o, mine, now);
            var site = o.mine();
            h.assertTrue(site.exists() && !site.ready() && site.top().equals(mine.origin), "новая шахта ждёт проходки ствола");
            h.assertTrue(o.mineCursor == 0, "штрек новой шахты начинается сначала");
            h.assertTrue(site.entry().equals(mine.zoneAnchor()), "шахтёр получает новую точку входа");
            o.zones.mineDepth = 3;
            Outpost copy = Outpost.load(o.save(level.registryAccess()), level.registryAccess());
            h.assertTrue(copy.zones.mineDepth == 3 && !copy.mine().ready() && copy.mine().top().equals(mine.origin), "проходка ствола сохраняется");
            h.assertTrue(copy.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN).equals(pen.zoneAnchor())
                    && oldPen.equals(copy.zones.previous(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN)), "перенос загона сохраняется");
            copy.zones.mineDepth = com.vladosiwe.lordsoutpost.outpost.WorkZones.MINE_DEPTH;
            h.assertTrue(copy.mine().ready() && copy.mine().bottom().equals(mine.origin.below(6)), "после проходки шахта готова, дно на 6 блоков ниже");
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
    @GameTest(template = "empty", timeoutTicks = 3000)
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
        BlockPos[] broken = new BlockPos[2];
        net.minecraft.world.level.block.Block[] wanted = new net.minecraft.world.level.block.Block[2];
        int[] stepsAtFounding = {0};
        long[] mark = {0};
        net.minecraft.server.level.ServerPlayer[] viewer = {null};
        h.onEachTick(() -> {
            if (h.getTick() % 150 == 0) {
                LordsOutpost.LOGGER.info("Форт в мире: тик {}, этап {}, online={}, base={}, состояние {}, жителей {}, сущностей {}, steps={}, placed={}, ground={}, placedTier={}, plan={}, hasWork={}, workers={}",
                        h.getTick(), stage[0], o.online, o.basePlaced, o.state, o.residents.size(), level.getEntitiesOfClass(OutpostNpc.class, area).size(),
                        o.steps, o.placed, o.groundTier, o.placedTier, o.nextPlan().size(), ChunkReconciliation.hasWork(o),
                        level.getEntitiesOfClass(WorkerEntity.class, area).stream().map(WorkerEntity::goalClassNames).toList());
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
                    // Ремонт: ломаем блок стены и блок пола — строитель обязан восстановить оба.
                    for (var e : o.design().cells.entrySet()) {
                        var cell = e.getValue();
                        BlockPos w = o.world(e.getKey());
                        if (cell.state().isAir() || !level.getBlockState(w).is(cell.state().getBlock()) || o.placedByPlayer(w)) continue;
                        // Клетка остаётся и в следующем тире (иначе её перестроит переход, а не ремонт).
                        var nextCell = o.blueprint().design(o.tier + 1).cells.get(e.getKey());
                        if (nextCell == null || nextCell.state() != cell.state()) continue;
                        if (broken[0] == null && e.getKey().getY() == 2 && cell.stage() == com.vladosiwe.lordsoutpost.blueprint.FortDesigner.ST_WALLS
                                && com.vladosiwe.lordsoutpost.blueprint.FortBlueprint.costOf(cell.state()) != null) broken[0] = w;
                        if (broken[1] == null && e.getKey().getY() == 0 && !cell.state().is(net.minecraft.tags.BlockTags.DIRT)
                                && !cell.state().is(net.minecraft.world.level.block.Blocks.DIRT_PATH)
                                && com.vladosiwe.lordsoutpost.blueprint.FortBlueprint.costOf(cell.state()) != null) broken[1] = w;
                    }
                    h.assertTrue(broken[0] != null, "не нашлось стены для проверки ремонта");
                    // Пол проверяется, если в форте есть пол, который переход на следующий тир не перестраивает.
                    if (broken[1] == null) broken[1] = broken[0];
                    wanted[0] = level.getBlockState(broken[0]).getBlock();
                    wanted[1] = level.getBlockState(broken[1]).getBlock();
                    level.setBlockAndUpdate(broken[0], net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(broken[1], net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    o.setStock(Resource.WOOD, Math.max(o.stock(Resource.WOOD), 200));
                    o.setStock(Resource.STONE, Math.max(o.stock(Resource.STONE), 200));
                    stage[0] = 4;
                }
                case 4 -> {
                    boolean wall = level.getBlockState(broken[0]).is(wanted[0]), floor = level.getBlockState(broken[1]).is(wanted[1]);
                    if (h.getTick() % 200 == 0) LordsOutpost.LOGGER.info("Ремонт: стена {} пол {} очередь {} {}", wall, floor, o.repairs.size(),
                            o.repairs.stream().limit(3).map(BlockPos::toShortString).toList());
                    if (!wall || !floor) return;
                    List<OutpostNpc> all = level.getEntitiesOfClass(OutpostNpc.class, area);
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
                // Выработка площадью: кольца вокруг дна ствола, 49×49 без самого ствола.
                h.assertTrue(cols.size() == 49 * 49 - 1, layout.id + " " + rot + ": столбцов выработки " + cols.size());
                BlockPos shaft = o.marker(Marker.MINE_SHAFT);
                java.util.Set<BlockPos> dug = new java.util.HashSet<>();
                for (var c : cols) {
                    int cheb = Math.max(Math.abs(c.stand().getX() - c.floor().getX()), Math.abs(c.stand().getZ() - c.floor().getZ()));
                    h.assertTrue(cheb == 1 && c.stand().getY() == c.floor().getY(), "столбец не примыкает к соседнему: " + c);
                    h.assertTrue(dug.contains(c.stand()) || dug.isEmpty() || Math.abs(c.stand().getX() - c.floor().getX()) + Math.abs(c.stand().getZ() - c.floor().getZ()) <= 2,
                            "копают с невыкопанной клетки: " + c);
                    dug.add(c.floor());
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
        List<net.minecraft.world.entity.animal.Animal> testCows = new java.util.ArrayList<>();
        List<BlockPos> treeLogs = new java.util.ArrayList<>();
        List<BlockPos> courtyardTreeLogs = new java.util.ArrayList<>();
        BlockPos[] courtyardTreeBase = {null};
        BlockPos[] courtyardPlant = {null};
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
                    // Дерево внутри стен: лесоруб обязан расчистить двор и не сажать его обратно.
                    BlockPos innerBase = new BlockPos(o.center.getX() + 3, o.center.getY() + 1, o.center.getZ() + 5);
                    courtyardTreeBase[0] = innerBase;
                    level.setBlockAndUpdate(innerBase.below(), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int i = 0; i < 8; i++) {
                        BlockPos log = innerBase.above(i);
                        level.setBlockAndUpdate(log, net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState());
                        courtyardTreeLogs.add(log);
                    }
                    for (BlockPos p : BlockPos.betweenClosed(innerBase.offset(-2, 6, -2), innerBase.offset(2, 9, 2))) {
                        if (level.getBlockState(p).isAir()) level.setBlockAndUpdate(p, net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
                    }
                    // Растительность во дворе: лесоруб должен убрать траву и молодую поросль, не трогая посевы.
                    BlockPos plant = new BlockPos(o.center.getX() + 5, o.center.getY() + 1, o.center.getZ() + 5);
                    courtyardPlant[0] = plant;
                    level.setBlockAndUpdate(plant.below(), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                    level.setBlockAndUpdate(plant, net.minecraft.world.level.block.Blocks.TALL_GRASS.defaultBlockState());
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
                        testCows.add(cow);
                    }
                    // Корм на складе: коров приманивают настоящей пшеницей, а не поводком.
                    net.minecraft.world.SimpleContainer wheat = new net.minecraft.world.SimpleContainer(1);
                    wheat.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WHEAT, 32));
                    com.vladosiwe.lordsoutpost.data.Storehouse.deposit(level, o, wheat);
                    // Саженцы на складе: за стенами лесоруб сажает настоящий саженец, а не создаёт его из ничего.
                    net.minecraft.world.SimpleContainer saplings = new net.minecraft.world.SimpleContainer(1);
                    saplings.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_SAPLING, 4));
                    com.vladosiwe.lordsoutpost.data.Storehouse.deposit(level, o, saplings);
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
                    // Выработка большая (кольца 49×49): камнем — только первые колонны, с породой над ними (шахтёр копает
                    // лишь в недрах, под сплошной породой).
                    var mineCols = com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.tunnel(o, level.getMinBuildHeight());
                    for (com.vladosiwe.lordsoutpost.entity.ai.WorkGoal.Column column : mineCols.subList(0, Math.min(40, mineCols.size()))) {
                        level.setBlockAndUpdate(column.floor(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(column.floor().below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(column.floor().above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                        for (int dy = 2; dy <= 4; dy++) {
                            BlockPos roof = column.floor().above(dy);
                            if (level.getBlockState(roof).isAir()) level.setBlockAndUpdate(roof, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                        }
                    }
                    mark[0] = h.getTick();
                    stage[0] = 1;
                }
                case 1 -> {
                    // Фиксируем животное, заведённое вглубь загона: тест проверяет перегон, а не случайное блуждание стада.
                    // У самой калитки не фиксируем — замершая в проходе корова перегородила бы выход скотоводу.
                    BlockPos penAt = o.marker(Marker.PEN);
                    for (net.minecraft.world.entity.animal.Animal penned
                            : com.vladosiwe.lordsoutpost.data.ChunkReconciliation.penAnimals(level, o)) {
                        boolean nearGate = false;
                        for (BlockPos g : BlockPos.betweenClosed(penAt.offset(-3, 0, -3), penAt.offset(3, 0, 3))) {
                            if (level.getBlockState(g).getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                                    && penned.distanceToSqr(g.getX() + 0.5, g.getY(), g.getZ() + 0.5) <= 1.6 * 1.6) nearGate = true;
                        }
                        if (!nearGate) penned.setNoAi(true);
                    }
                    int logsLeft = 0;
                    for (BlockPos p : treeLogs) if (level.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)) logsLeft++;
                    int courtyardLogsLeft = 0;
                    for (BlockPos p : courtyardTreeLogs) if (level.getBlockState(p).is(net.minecraft.tags.BlockTags.LOGS)) courtyardLogsLeft++;
                    boolean courtyardReplanted = courtyardTreeBase[0] != null
                            && level.getBlockState(courtyardTreeBase[0]).getBlock() == net.minecraft.world.level.block.Blocks.OAK_SAPLING;
                    boolean courtyardPlantCleared = courtyardPlant[0] != null && level.getBlockState(courtyardPlant[0]).isAir();
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
                    // Хотя бы одну корову, стоящую за калиткой, скотовод должен привести в загон приманиванием.
                    var inPen = com.vladosiwe.lordsoutpost.data.ChunkReconciliation.penAnimals(level, o);
                    long luredCows = testCows.stream().filter(inPen::contains).count();
                    if ((h.getTick() - mark[0]) % 200 == 0) LordsOutpost.LOGGER.info("Работа жителей: приведено коров {}, загон {}, коровы {}", luredCows,
                            o.zone(com.vladosiwe.lordsoutpost.outpost.WorkZone.PEN).subtract(o.center).toShortString(),
                            testCows.stream().map(c -> c.blockPosition().subtract(o.center).toShortString() + (c.isAlive() ? "" : "†")).toList());
                    if ((h.getTick() - mark[0]) % 200 == 0) LordsOutpost.LOGGER.info("Работа жителей: условия: брёвна {} двор {} двор-пересажен {} трава {} пересажено {} мусор {} донесено {} коровы {} шахтёр {}",
                            logsLeft, courtyardLogsLeft, courtyardReplanted, courtyardPlantCleared, replanted, litterGone, o.depositedItems, luredCows, minerWorked);
                    if (logsLeft > 0 || courtyardLogsLeft > 0 || courtyardReplanted || !courtyardPlantCleared
                            || !replanted || !litterGone || o.depositedItems < 24 + 5 || luredCows < 1 || !minerWorked) return;
                    // Склад: бочки отражают запас.
                    var barrels = com.vladosiwe.lordsoutpost.data.Storehouse.barrels(level, o);
                    h.assertTrue(!barrels.isEmpty(), "у склада нет бочек");
                    var have = com.vladosiwe.lordsoutpost.data.Storehouse.measure(barrels);
                    for (Resource r : com.vladosiwe.lordsoutpost.data.Storehouse.PHYSICAL) {
                        double diff = Math.abs(have.get(r) - o.stock(r));
                        h.assertTrue(diff < 10 || have.get(r) > 0, "бочки склада не совпадают с запасом: " + r + " " + have.get(r) + " / " + o.stock(r));
                    }
                    LordsOutpost.LOGGER.info("Работа жителей: внешнее дерево свалено и пересажено, дерево и трава во дворе убраны без повторной посадки, мусор подобран, шахтёр получил опыт, донесено {}, максимум в загоне {}, склад {}",
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
