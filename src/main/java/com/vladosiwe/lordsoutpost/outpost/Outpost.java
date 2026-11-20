package com.vladosiwe.lordsoutpost.outpost;

import com.vladosiwe.lordsoutpost.blueprint.Design;
import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Marker;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.blueprint.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Аванпост целиком: место, владелец, стройка, склад, жители, отряды, права, законы, договоры, караваны.
 * Это единственный источник правды: сущности и блоки в мире — лишь его отражение, пока чанки загружены.
 */
public final class Outpost {
    /** Запись хроники: что произошло и когда (ключ перевода + аргументы). */
    public record Event(long day, String key, List<String> args) {}

    public static final int CHRONICLE_SIZE = 24;
    public static final int MAX_TRIBUTE_STACKS = 54;

    // --- Место ---
    public final long id;
    /** Центр форта; y — уровень земли (блок, на котором стоят). */
    public final BlockPos center;
    public final Layout layout;
    public final Palette palette;
    public final Rotation rotation;
    public final Difficulty difficulty;
    /** Временное хранилище, пока склад сносят и строят заново (не сохраняется: пересчитывается по миру). */
    @org.jetbrains.annotations.Nullable public transient BlockPos tempStore;
    /** Природные деревья внутри стен убраны (генерация мира сажает их уже после форта). */
    public boolean natureCleared;
    /** Версия планировки (1 — первая, 2 — просторная); новые форты берут версию своего мира. */
    public int version = Layout.currentVersion();
    /** Создан командой, а не генерацией мира. */
    public final boolean manual;

    // --- Владение ---
    public OutpostState state = OutpostState.WILD;
    public String name = "";
    @Nullable public UUID owner;
    public String ownerName = "";
    public long foundedAt;
    public long conqueredAt;
    @Nullable public BlockPos standard;

    // --- Стройка ---
    /** Последний полностью готовый тир (1..3). */
    public int tier;
    /** Сколько шагов плана следующего тира выполнено (логически). */
    public int steps;
    /** Сколько из них уже поставлено блоками в мире. */
    public int placed;
    /**
     * К плану какого тира относится placed. Обычно tier + 1; меньше — если тир достроили,
     * пока чанки были выгружены, и хвост старого плана ещё предстоит поставить в мире.
     */
    public int placedTier;
    /** Блоки базового состояния поставлены (для фортов из генерации мира — сразу). */
    public boolean basePlaced;
    /** Наибольший тир, под периметр которого уже выровнена площадка. */
    public int groundTier;
    /** Курсор выравнивания площадки под следующий периметр. */
    public int groundCursor;
    public double buildCredit;
    /** Ресурс, из-за нехватки которого стройка стоит (не сохраняется). */
    @Nullable public transient Resource stalledOn;
    public transient boolean starving;
    public long nextCaravanAt;
    /** Последнее измеренное содержимое бочек склада (стоимость по ресурсам), см. Storehouse. */
    public final EnumMap<Resource, Double> storeLast = new EnumMap<>(Resource.class);
    /** Сколько столбцов штрека уже пройдено шахтёрами. */
    public int mineCursor;
    /** Загон уже заселён по записи после прихода игрока (не сохраняется). */
    public transient boolean penSpawned;
    /** Деревья, которые сейчас рубят лесорубы (позиции комлей). */
    public transient final java.util.Set<BlockPos> claimedTrees = new java.util.HashSet<>();
    public transient long penActionAt;
    /** Сколько предметов жители донесли до склада с начала сессии (для проверки и отладки). */
    public transient int depositedItems;

    // --- Хозяйство ---
    private final EnumMap<Resource, Double> stock = new EnumMap<>(Resource.class);
    public final List<Resident> residents = new ArrayList<>();
    public final Squad[] squads = {new Squad(Squad.GUARD, Squad.Order.POSTS), new Squad(Squad.WATCH, Squad.Order.PATROL), new Squad(Squad.RETINUE, Squad.Order.POSTS)};
    public Law law = Law.NONE;
    public long lawChangedAt = Long.MIN_VALUE / 2;
    public final AccessRules access = new AccessRules();
    public final Map<Long, Relation> relations = new LinkedHashMap<>();
    public final List<Caravan> caravans = new ArrayList<>();
    public boolean autoCaravans = true;
    public final List<Quest> quests = new ArrayList<>();
    public int questCounter;
    /** Дань, которая ещё не легла в сундук (накоплена, пока чанки были выгружены или сундук полон). */
    public final List<ItemStack> tribute = new ArrayList<>();
    /** Постройки, заказанные Лордом сверх плана тиров (сохраняются). */
    public final List<Project> projects = new ArrayList<>();
    public long projectCounter;
    /** Авто-застройка: планировщик сам заказывает рекомендованные постройки (иначе только советует Лорду). */
    public boolean autoBuild;
    /** Что планировщик советует построить и почему (пересчитывается, не сохраняется). */
    public transient final Map<Project.Type, String> recommended = new java.util.EnumMap<>(Project.Type.class);
    public transient long autoBuildTriedAt;
    /**
     * Материал, который строители уже принесли со склада и пустили в дело, но ещё не израсходовали на шаги заказов
     * (бревно даёт 4 единицы дерева, а шаг стоит 1). Сохраняется, чтобы остаток не терялся.
     */
    public final EnumMap<Resource, Double> buildMaterials = new EnumMap<>(Resource.class);
    /** Занятые строителями шаги проектов: ключ (id проекта, шаг) -> тик истечения (не сохраняется). */
    public transient final Map<Long, Long> projectClaims = new HashMap<>();
    public transient final Map<Long, Long> projectDeferred = new HashMap<>();
    public transient final Map<Long, Integer> projectFailures = new HashMap<>();
    /**
     * Предметы, которые уже принадлежат складу, но ещё не лежат в бочках (произведены, пока форт не загружен,
     * или бочки были полны). При ближайшей сверке склада они выкладываются в бочки. Сохраняются.
     */
    public final List<ItemStack> storeItems = new ArrayList<>();
    /** Сумка придворного мага: его ноша хранится в записи, поэтому не пропадает при выгрузке тела. */
    public final net.minecraft.world.SimpleContainer mageSatchel = new net.minecraft.world.SimpleContainer(9);
    /** Личный инвентарь лавки торговца: настоящий товар, взятый со склада (сохраняется). */
    public final List<ItemStack> shopStock = new ArrayList<>();
    /** Полка придворного мага: магические товары, которые он сам принёс со склада и продаёт у башни (сохраняется). */
    public final List<ItemStack> magicShelf = new ArrayList<>();
    public double tributeGold;
    public int penAnimals;
    public double arcaneWork;
    public double growth;
    public double smithWork;
    /** Ускорения от выполненных заданий: ресурс -> тик окончания. */
    public final Map<String, Long> boosts = new HashMap<>();

    // --- Угрозы ---
    public long nextRaidAt;
    @Nullable public Raid raid;
    public long garrisonRegenAt;

    public long lastSim;
    public final Deque<Event> chronicle = new ArrayDeque<>();

    // --- Не сохраняется ---
    public transient boolean online;
    public transient long onlineSince;
    /** Занятые строителями шаги: индекс шага -> тик, после которого блок ставится сам. */
    public transient final Map<Integer, Long> claims = new HashMap<>();
    public transient int claimCursor;
    /** Отложенные шаги стройки, до которых строители не смогли добраться: индекс -> тик повторной попытки. */
    public transient final Map<Integer, Long> deferredSteps = new HashMap<>();
    /** Сколько раз подряд шаг оказался недостижимым (для удлинения отсрочки и расширенного поиска позиции). */
    public transient final Map<Integer, Integer> stepFailures = new HashMap<>();
    /** Игровое время последнего онлайн-тика стройки (для проверки отсрочек без доступа к миру). */
    public transient long buildClock;
    public transient boolean lootSeeded;
    public transient int repairCursor;
    public transient final Deque<BlockPos> repairs = new ArrayDeque<>();
    public transient long alarmUntil;
    public transient boolean dirtyForClients = true;
    /** Шаги текущего плана, поставленные не по порядку (индексы шагов). */
    public transient final java.util.BitSet doneBits = new java.util.BitSet();
    public transient long defenselessSince;
    /** Для форта из генерации мира: проверено, что структура действительно стоит в чанке. */
    public boolean verified;

    // --- Управление поселением ---
    /** Задачи жителей и резервы склада (сохраняются). */
    public final TaskBoard tasks = new TaskBoard();
    /** Рабочие зоны профессий и их перенос (сохраняются). */
    public final WorkZones zones = new WorkZones();
    /** Приоритеты профессий, которые выставляет планировщик (пересчитываются, не сохраняются). */
    public transient final EnumMap<Role, Integer> priorities = new EnumMap<>(Role.class);
    /** Ресурс, который сейчас нужнее всего (по оценке планировщика), или null. */
    @Nullable public transient Resource scarce;
    /** Еды почти нет: планировщик приостанавливает необязательную стройку. */
    public transient boolean foodCritical;
    /** Пиромант Iron's Spells у башни мага (сущность мода, сохраняется в чанке сама) и срок прихода нового после гибели. */
    @Nullable public UUID pyromancer;
    public long pyromancerRejoinAt;

    // --- Правки игрока (сохраняются) ---
    /** Блоки, поставленные игроком на территории или в лесной зоне: их не рубят, не расчищают и не сносят стройкой. */
    public final it.unimi.dsi.fastutil.longs.LongOpenHashSet playerPlaced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    /** Клетки чертежа, которые игрок сознательно разобрал: ремонт их не восстанавливает. */
    public final it.unimi.dsi.fastutil.longs.LongOpenHashSet playerRemoved = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    public static final int MAX_PLAYER_EDITS = 65536;

    public Outpost(long id, BlockPos center, Layout layout, Palette palette, Rotation rotation, Difficulty difficulty, boolean manual) {
        this.id = id;
        this.center = center;
        this.layout = layout;
        this.palette = palette;
        this.rotation = rotation;
        this.difficulty = difficulty;
        this.manual = manual;
        this.tier = difficulty.tier;
        this.placedTier = tier + 1;
        this.groundTier = tier;
    }

    /** Сколько шагов следующего тира уже построено у только что сгенерированного форта этой сложности. */
    public static int initialSteps(FortBlueprint bp, Difficulty d) {
        if (d.tier >= FortBlueprint.MAX_TIER || d.progress <= 0) return 0;
        // Просторная планировка: дикий форт стоит готовым тиром, без недостроенного второго кольца стен.
        if (bp.version >= 2) return 0;
        return Math.round(d.progress * bp.plan(d.tier + 1).size());
    }

    // ============================== Чертёж и геометрия ==============================

    public FortBlueprint blueprint() {
        return FortBlueprint.get(layout, palette, rotation, version);
    }

    public Design design() {
        return blueprint().design(tier);
    }

    /** План следующего тира или пустой список, если форт достроен. */
    public List<Step> nextPlan() {
        return tier >= FortBlueprint.MAX_TIER ? List.of() : blueprint().plan(tier + 1);
    }

    public boolean complete() {
        return tier >= FortBlueprint.MAX_TIER;
    }

    public float progress() {
        int size = nextPlan().size();
        return size == 0 ? 1f : Mth.clamp(steps / (float) size, 0f, 1f);
    }

    public int radius() {
        return Layout.radius(version, tier);
    }

    /** Радиус с учётом идущего расширения. */
    public int reach() {
        return Layout.radius(version, !complete() && (steps > 0 || state == OutpostState.OWNED) ? tier + 1 : tier);
    }

    public BlockPos world(BlockPos rel) {
        return center.offset(rel);
    }

    /** Мировые координаты -> канонические (ворота на юге). */
    private int[] canonical(int x, int z) {
        int dx = x - center.getX(), dz = z - center.getZ();
        return switch (rotation) {
            case NONE -> new int[]{dx, dz};
            case CLOCKWISE_90 -> new int[]{dz, -dx};
            case CLOCKWISE_180 -> new int[]{-dx, -dz};
            case COUNTERCLOCKWISE_90 -> new int[]{-dz, dx};
        };
    }

    /** Точка на территории аванпоста (периметр с запасом, с разумными границами по высоте). */
    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public boolean contains(int x, int y, int z) {
        if (y < center.getY() - 16 || y > center.getY() + 48) return false;
        return insideHorizontally(x, z, reach() + Layout.MARGIN);
    }

    public boolean insideHorizontally(int x, int z, int r) {
        int span = r + 1;
        if (Math.abs(x - center.getX()) > span || Math.abs(z - center.getZ()) > span) return false;
        int[] c = canonical(x, z);
        return layout.inside(c[0], c[1], r);
    }

    /** Внутри стен (без подступов). */
    public boolean insideWalls(BlockPos pos) {
        return pos.getY() >= center.getY() - 8 && pos.getY() <= center.getY() + 40 && insideHorizontally(pos.getX(), pos.getZ(), radius());
    }

    /**
     * Клетка принадлежит постройкам форта: чертежу текущего или строящегося тира либо заказанной постройке.
     * Такие блоки (брёвна стен и башен, ограды загона) не трогают ни лесорубы, ни расчистка.
     */
    public boolean structural(BlockPos pos, net.minecraft.world.level.block.state.BlockState actual) {
        if (actual.isAir() || !contains(pos)) return false;
        BlockPos rel = pos.subtract(center);
        // Постройка — только если чертёж хочет здесь именно этот блок: бревно стены — да, дерево на месте будущего дома — нет.
        var cell = design().cells.get(rel);
        if (cell != null && cell.state().getBlock() == actual.getBlock()) return true;
        if (!complete()) {
            var next = blueprint().design(tier + 1).cells.get(rel);
            if (next != null && next.state().getBlock() == actual.getBlock()) return true;
        }
        for (Project p : projects) {
            if (p.state == Project.State.CANCELLED) continue;
            for (var step : p.steps(this)) if (step.pos().equals(rel) && step.state().getBlock() == actual.getBlock()) return true;
        }
        return false;
    }

    public double distanceSqr(double x, double z) {
        double dx = x - center.getX(), dz = z - center.getZ();
        return dx * dx + dz * dz;
    }

    public List<BlockPos> markers(Marker marker) {
        List<BlockPos> rel = design().markers(marker);
        List<BlockPos> out = new ArrayList<>(rel.size());
        for (BlockPos p : rel) out.add(world(p));
        return out;
    }

    public BlockPos marker(Marker marker) {
        List<BlockPos> rel = design().markers(marker);
        return rel.isEmpty() ? center.above() : world(rel.get(0));
    }

    /** Якорь рабочей зоны: перенесённый Лордом или стоящий по чертежу. */
    public BlockPos zone(WorkZone zone) {
        BlockPos moved = zones.override(zone);
        return moved != null ? moved : marker(zone.marker);
    }

    /**
     * Шахта: вход (где шахтёр стоит наверху), устье, дно ствола, точка направления штрека, уровень земли.
     * По чертежу форта или в построенном по заказу копре; ready = ствол прокопан до дна.
     */
    public record MineSite(BlockPos entry, BlockPos top, BlockPos bottom, BlockPos dir, int groundY, boolean ready, boolean exists) {}

    public MineSite mine() {
        Long link = zones.link(WorkZone.MINE);
        if (link != null) {
            for (Project p : projects) {
                if (p.id != link) continue;
                BlockPos top = p.origin;
                BlockPos bottom = top.below(WorkZones.MINE_DEPTH);
                return new MineSite(p.zoneAnchor(), top, bottom, bottom.offset(p.mineDir()), top.getY(),
                        zones.mineDepth >= WorkZones.MINE_DEPTH, true);
            }
        }
        boolean exists = !design().markers(Marker.MINE_SHAFT).isEmpty();
        BlockPos bottom = marker(Marker.MINE_SHAFT);
        return new MineSite(marker(Marker.MINE), new BlockPos(bottom.getX(), center.getY(), bottom.getZ()), bottom,
                marker(Marker.MINE_DIR), center.getY(), true, exists);
    }

    /** Зона существует: перенесена Лордом или есть в чертеже текущего тира. */
    public boolean hasZone(WorkZone zone) {
        return zones.override(zone) != null || !design().markers(zone.marker).isEmpty();
    }

    /** Контур периметра в мировых координатах (x, z) для карты. */
    public int[][] outline(int r) {
        int[][] canon = layout.outline(r);
        int[][] out = new int[canon.length][2];
        for (int i = 0; i < canon.length; i++) {
            BlockPos p = new BlockPos(canon[i][0], 0, canon[i][1]).rotate(rotation);
            out[i][0] = center.getX() + p.getX();
            out[i][1] = center.getZ() + p.getZ();
        }
        return out;
    }

    // ============================== Склад ==============================

    public double stock(Resource r) {
        return stock.getOrDefault(r, 0.0);
    }

    public int capacity(Resource r) {
        if (r == Resource.GOLD) return 1_000_000;
        return switch (tier) { case 1 -> 600; case 2 -> 1500; default -> 4000; };
    }

    /** Кладёт на склад; возвращает, сколько реально поместилось. */
    public double add(Resource r, double amount) {
        if (amount <= 0) return 0;
        double cur = stock(r);
        // Запас может быть выше ёмкости (игрок сам положил предметы в бочки) — тогда он не урезается.
        if (cur >= capacity(r)) return 0;
        double next = Math.min(capacity(r), cur + amount);
        stock.put(r, next);
        return next - cur;
    }

    /**
     * Учитывает предметы, которые уже физически лежат на складе (их принёс игрок). Ёмкость ограничивает
     * только выработку поселения; настоящие предметы нельзя «не учесть», иначе синхронизация бочек их удалит.
     */
    public void addPhysical(Resource r, double amount) {
        if (amount <= 0) return;
        stock.put(r, stock(r) + amount);
    }

    /** Запас без того, что отложено под задачи жителей (резерв нельзя тратить на стройку и торговлю). */
    /** Свободный запас: за вычетом отложенного под задачи жителей и резерва принятых заказов Лорда. */
    public double available(Resource r) {
        return Math.max(0, availableForProjects(r) - com.vladosiwe.lordsoutpost.data.Projects.reserved(this, r));
    }

    /** Запас за вычетом задач жителей (заказ Лорда оплачивается из своего резерва). */
    public double availableForProjects(Resource r) {
        double reserved = tasks.reservedValue(item -> {
            var v = com.vladosiwe.lordsoutpost.data.ResourceItems.of(new ItemStack(item));
            return v != null && v.resource() == r ? v.perItem() : 0;
        });
        return Math.max(0, stock(r) - reserved);
    }

    public boolean take(Resource r, double amount) {
        double cur = stock(r);
        if (cur + 1e-6 < amount) return false;
        stock.put(r, Math.max(0, cur - amount));
        return true;
    }

    public void setStock(Resource r, double amount) {
        stock.put(r, Math.max(0, amount));
    }

    // ============================== Жители ==============================

    public int count(Role role) {
        int n = 0;
        for (Resident r : residents) if (r.role == role) n++;
        return n;
    }

    public int activeCount(Role role) {
        int n = 0;
        for (Resident r : residents) if (r.role == role && r.active()) n++;
        return n;
    }

    public int fighters() {
        int n = 0;
        for (Resident r : residents) if (r.role.combat) n++;
        return n;
    }

    public int civilians() {
        return residents.size() - fighters();
    }

    @Nullable
    public Resident resident(UUID id) {
        for (Resident r : residents) if (r.id.equals(id)) return r;
        return null;
    }

    @Nullable
    public Resident captain() {
        for (Resident r : residents) if (r.role == Role.CAPTAIN) return r;
        return null;
    }

    public int populationCap() {
        int base = switch (tier) { case 1 -> 10; case 2 -> 18; default -> 28; };
        // Каждый построенный по заказу дом — ещё две кровати (не больше восьми домов).
        return base + 2 * Math.min(8, builtProjects(Project.Type.HOUSE));
    }

    public int builtProjects(Project.Type type) {
        int n = 0;
        for (Project p : projects) if (p.type == type && p.built(this)) n++;
        return n;
    }

    public int infirmaryBeds() {
        return tier >= 2 ? design().markers(Marker.INFIRMARY).size() : 0;
    }

    public int penCapacity() {
        return switch (tier) { case 1 -> 6; case 2 -> 9; default -> 12; };
    }

    /** Суммарная сила обороны (для виртуального боя). */
    public double defensePower() {
        double sum = 0;
        for (Resident r : residents) sum += r.power();
        double walls = switch (tier) { case 1 -> 1.0; case 2 -> 1.3; default -> 1.6; };
        // Заказанные башни и участки стен усиливают оборону (до +30%).
        walls += Math.min(0.3, 0.05 * builtProjects(Project.Type.WATCHTOWER) + 0.03 * builtProjects(Project.Type.WALL));
        return sum * walls * law.defense();
    }

    public boolean boosted(Resource r, long now) {
        Long until = boosts.get(r.id);
        return until != null && until > now;
    }

    // ============================== Правки игрока ==============================

    public boolean placedByPlayer(BlockPos pos) {
        return playerPlaced.contains(pos.asLong());
    }

    public boolean removedByPlayer(BlockPos pos) {
        return playerRemoved.contains(pos.asLong());
    }

    public void notePlayerPlaced(BlockPos pos) {
        long k = pos.asLong();
        playerRemoved.remove(k);
        if (playerPlaced.size() < MAX_PLAYER_EDITS) playerPlaced.add(k);
    }

    public void notePlayerRemoved(BlockPos pos, boolean blueprintCell) {
        long k = pos.asLong();
        playerPlaced.remove(k);
        if (blueprintCell && playerRemoved.size() < MAX_PLAYER_EDITS) playerRemoved.add(k);
    }

    /** Клетку заново занял чертёж (новая постройка): старые пометки больше не действуют. */
    public void clearPlayerMarks(BlockPos pos) {
        long k = pos.asLong();
        playerPlaced.remove(k);
        playerRemoved.remove(k);
    }

    // ============================== Права ==============================

    public boolean isLord(UUID player) {
        return state == OutpostState.OWNED && player.equals(owner);
    }

    // ============================== Хроника ==============================

    public void log(long gameTime, String key, Object... args) {
        List<String> list = new ArrayList<>(args.length);
        for (Object a : args) list.add(String.valueOf(a));
        chronicle.addFirst(new Event(gameTime / 24000L + 1, key, list));
        while (chronicle.size() > CHRONICLE_SIZE) chronicle.removeLast();
        dirtyForClients = true;
    }

    public String displayName() {
        return name.isEmpty() ? "" : name;
    }

    // ============================== Сохранение ==============================

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        t.putLong("id", id);
        t.putInt("x", center.getX());
        t.putInt("y", center.getY());
        t.putInt("z", center.getZ());
        t.putString("layout", layout.id);
        t.putByte("layoutVersion", (byte) version);
        t.putBoolean("natureCleared", natureCleared);
        t.putString("palette", palette.id);
        t.putByte("rot", (byte) rotation.ordinal());
        t.putByte("skulls", (byte) difficulty.skulls);
        t.putBoolean("manual", manual);

        t.putByte("state", (byte) state.ordinal());
        t.putString("name", name);
        if (owner != null) t.putUUID("owner", owner);
        t.putString("ownerName", ownerName);
        t.putLong("founded", foundedAt);
        t.putLong("conquered", conqueredAt);
        if (standard != null) t.putLong("standard", standard.asLong());

        t.putByte("tier", (byte) tier);
        t.putInt("steps", steps);
        // Шаги ставятся идемпотентно, поэтому незавершённые заявки строителей просто повторятся после загрузки.
        int safePlaced = placed;
        for (int idx : claims.keySet()) safePlaced = Math.min(safePlaced, idx);
        t.putInt("placed", Math.max(0, safePlaced));
        t.putByte("placedTier", (byte) placedTier);
        t.putBoolean("base", basePlaced);
        t.putByte("groundTier", (byte) groundTier);
        t.putInt("ground", groundCursor);
        t.putLong("nextCaravan", nextCaravanAt);
        t.putBoolean("verified", verified);
        CompoundTag sl = new CompoundTag();
        for (var e : storeLast.entrySet()) sl.putDouble(e.getKey().id, e.getValue());
        t.put("storeLast", sl);
        t.putInt("mineCursor", mineCursor);
        t.putDouble("credit", buildCredit);
        t.putLongArray("playerPlaced", playerPlaced.toLongArray());
        t.put("tasks", tasks.save());
        t.put("zones", zones.save());
        if (pyromancer != null) t.putUUID("pyromancer", pyromancer);
        t.putLong("pyromancerRejoin", pyromancerRejoinAt);
        t.putLongArray("playerRemoved", playerRemoved.toLongArray());

        CompoundTag st = new CompoundTag();
        for (var e : stock.entrySet()) st.putDouble(e.getKey().id, e.getValue());
        t.put("stock", st);
        ListTag res = new ListTag();
        for (Resident r : residents) res.add(r.save());
        t.put("residents", res);
        ListTag sq = new ListTag();
        for (Squad s : squads) sq.add(s.save());
        t.put("squads", sq);
        t.putString("law", law.id);
        t.putLong("lawAt", lawChangedAt);
        t.put("access", access.save());
        ListTag rel = new ListTag();
        for (var e : relations.entrySet()) {
            CompoundTag r = e.getValue().save();
            r.putLong("id", e.getKey());
            rel.add(r);
        }
        t.put("relations", rel);
        ListTag car = new ListTag();
        for (Caravan c : caravans) car.add(c.save(registries));
        t.put("caravans", car);
        t.putBoolean("autoCaravans", autoCaravans);
        ListTag qs = new ListTag();
        for (Quest q : quests) qs.add(q.save());
        t.put("quests", qs);
        t.putInt("questCounter", questCounter);
        ListTag tr = new ListTag();
        for (ItemStack s : tribute) if (!s.isEmpty()) tr.add(s.save(registries));
        t.put("tribute", tr);
        ListTag store = new ListTag();
        for (ItemStack s : storeItems) if (!s.isEmpty()) store.add(s.save(registries));
        t.put("storeItems", store);
        ListTag satchel = new ListTag();
        for (int i = 0; i < mageSatchel.getContainerSize(); i++) if (!mageSatchel.getItem(i).isEmpty()) satchel.add(mageSatchel.getItem(i).save(registries));
        t.put("mageSatchel", satchel);
        ListTag shop = new ListTag();
        for (ItemStack s : shopStock) if (!s.isEmpty()) shop.add(s.save(registries));
        t.put("shop", shop);
        ListTag shelf = new ListTag();
        for (ItemStack s : magicShelf) if (!s.isEmpty()) shelf.add(s.save(registries));
        t.put("magicShelf", shelf);
        ListTag pr = new ListTag();
        for (Project p : projects) pr.add(p.save());
        t.put("projects", pr);
        t.putLong("projectCounter", projectCounter);
        t.putBoolean("autoBuild", autoBuild);
        CompoundTag bm = new CompoundTag();
        for (var e : buildMaterials.entrySet()) bm.putDouble(e.getKey().id, e.getValue());
        t.put("buildMaterials", bm);
        t.putDouble("tributeGold", tributeGold);
        t.putInt("pen", penAnimals);
        t.putDouble("arcaneWork", arcaneWork);
        t.putDouble("growth", growth);
        t.putDouble("smithWork", smithWork);
        CompoundTag bo = new CompoundTag();
        for (var e : boosts.entrySet()) bo.putLong(e.getKey(), e.getValue());
        t.put("boosts", bo);

        t.putLong("nextRaid", nextRaidAt);
        if (raid != null) t.put("raid", raid.save());
        t.putLong("regenAt", garrisonRegenAt);
        t.putLong("lastSim", lastSim);
        t.putBoolean("loot", lootSeeded);
        ListTag ch = new ListTag();
        for (Event e : chronicle) {
            CompoundTag c = new CompoundTag();
            c.putLong("day", e.day());
            c.putString("key", e.key());
            ListTag a = new ListTag();
            for (String s : e.args()) a.add(StringTag.valueOf(s));
            c.put("args", a);
            ch.add(c);
        }
        t.put("chronicle", ch);
        return t;
    }

    public static Outpost load(CompoundTag t, HolderLookup.Provider registries) {
        Rotation rot = Rotation.values()[Mth.clamp(t.getByte("rot"), 0, 3)];
        Outpost o = new Outpost(t.getLong("id"), new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z")),
                Layout.byId(t.getString("layout")), Palette.byId(t.getString("palette")), rot,
                Difficulty.bySkulls(t.getByte("skulls")), t.getBoolean("manual"));
        // Записи до просторной планировки — первой версии.
        o.version = t.contains("layoutVersion") ? Mth.clamp(t.getByte("layoutVersion"), 1, 2) : 1;
        o.natureCleared = t.getBoolean("natureCleared");
        o.state = OutpostState.VALUES[Mth.clamp(t.getByte("state"), 0, OutpostState.VALUES.length - 1)];
        o.name = t.getString("name");
        o.owner = t.hasUUID("owner") ? t.getUUID("owner") : null;
        o.ownerName = t.getString("ownerName");
        o.foundedAt = t.getLong("founded");
        o.conqueredAt = t.getLong("conquered");
        o.standard = t.contains("standard") ? BlockPos.of(t.getLong("standard")) : null;
        if (o.state == OutpostState.OWNED && o.owner == null) o.state = OutpostState.CONQUERED;

        o.tier = Mth.clamp(t.getByte("tier"), 1, FortBlueprint.MAX_TIER);
        o.steps = Math.max(0, t.getInt("steps"));
        o.placedTier = t.contains("placedTier") ? Mth.clamp(t.getByte("placedTier"), 1, o.tier + 1) : o.tier + 1;
        o.placed = Math.max(0, t.getInt("placed"));
        o.basePlaced = t.getBoolean("base");
        o.groundTier = t.contains("groundTier") ? t.getByte("groundTier") : o.tier;
        o.groundCursor = Math.max(0, t.getInt("ground"));
        o.nextCaravanAt = t.getLong("nextCaravan");
        o.verified = t.getBoolean("verified");
        CompoundTag sl = t.getCompound("storeLast");
        for (Resource r : Resource.VALUES) if (sl.contains(r.id)) o.storeLast.put(r, sl.getDouble(r.id));
        o.mineCursor = Math.max(0, t.getInt("mineCursor"));
        o.buildCredit = t.getDouble("credit");
        for (long l : t.getLongArray("playerPlaced")) o.playerPlaced.add(l);
        o.tasks.load(t.getCompound("tasks"), o.lastSim);
        o.zones.load(t.getCompound("zones"));
        o.pyromancer = t.hasUUID("pyromancer") ? t.getUUID("pyromancer") : null;
        o.pyromancerRejoinAt = t.getLong("pyromancerRejoin");
        for (long l : t.getLongArray("playerRemoved")) o.playerRemoved.add(l);

        CompoundTag st = t.getCompound("stock");
        for (Resource r : Resource.VALUES) if (st.contains(r.id)) o.stock.put(r, Math.max(0, st.getDouble(r.id)));
        ListTag res = t.getList("residents", Tag.TAG_COMPOUND);
        for (int i = 0; i < res.size(); i++) o.residents.add(Resident.load(res.getCompound(i)));
        ListTag sq = t.getList("squads", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(sq.size(), o.squads.length); i++) o.squads[i].load(sq.getCompound(i));
        o.law = Law.byId(t.getString("law"));
        o.lawChangedAt = t.getLong("lawAt");
        o.access.load(t.getCompound("access"));
        ListTag rel = t.getList("relations", Tag.TAG_COMPOUND);
        for (int i = 0; i < rel.size(); i++) o.relations.put(rel.getCompound(i).getLong("id"), Relation.load(rel.getCompound(i)));
        ListTag car = t.getList("caravans", Tag.TAG_COMPOUND);
        for (int i = 0; i < car.size(); i++) o.caravans.add(Caravan.load(car.getCompound(i), registries));
        o.autoCaravans = !t.contains("autoCaravans") || t.getBoolean("autoCaravans");
        ListTag qs = t.getList("quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < qs.size(); i++) o.quests.add(Quest.load(qs.getCompound(i)));
        o.questCounter = t.getInt("questCounter");
        ListTag tr = t.getList("tribute", Tag.TAG_COMPOUND);
        for (int i = 0; i < tr.size(); i++) ItemStack.parse(registries, tr.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(o.tribute::add);
        o.tributeGold = t.getDouble("tributeGold");
        ListTag store = t.getList("storeItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < store.size(); i++) ItemStack.parse(registries, store.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(o.storeItems::add);
        ListTag satchel = t.getList("mageSatchel", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(satchel.size(), o.mageSatchel.getContainerSize()); i++) {
            int slot = i;
            ItemStack.parse(registries, satchel.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(s -> o.mageSatchel.setItem(slot, s));
        }
        ListTag shop = t.getList("shop", Tag.TAG_COMPOUND);
        for (int i = 0; i < shop.size(); i++) ItemStack.parse(registries, shop.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(o.shopStock::add);
        ListTag shelf = t.getList("magicShelf", Tag.TAG_COMPOUND);
        for (int i = 0; i < shelf.size(); i++) ItemStack.parse(registries, shelf.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(o.magicShelf::add);
        ListTag pr = t.getList("projects", Tag.TAG_COMPOUND);
        for (int i = 0; i < pr.size(); i++) {
            Project p = Project.load(pr.getCompound(i));
            if (p != null) o.projects.add(p);
        }
        o.projectCounter = t.getLong("projectCounter");
        o.autoBuild = t.getBoolean("autoBuild");
        CompoundTag bm = t.getCompound("buildMaterials");
        for (Resource r : Resource.VALUES) if (bm.contains(r.id)) o.buildMaterials.put(r, Math.max(0, bm.getDouble(r.id)));
        o.penAnimals = Math.max(0, t.getInt("pen"));
        o.arcaneWork = t.getDouble("arcaneWork");
        o.growth = t.getDouble("growth");
        o.smithWork = t.getDouble("smithWork");
        CompoundTag bo = t.getCompound("boosts");
        for (String k : bo.getAllKeys()) o.boosts.put(k, bo.getLong(k));

        o.nextRaidAt = t.getLong("nextRaid");
        o.raid = t.contains("raid") ? Raid.load(t.getCompound("raid")) : null;
        o.garrisonRegenAt = t.getLong("regenAt");
        o.lastSim = t.getLong("lastSim");
        o.lootSeeded = t.getBoolean("loot");
        ListTag ch = t.getList("chronicle", Tag.TAG_COMPOUND);
        for (int i = 0; i < ch.size(); i++) {
            CompoundTag c = ch.getCompound(i);
            List<String> args = new ArrayList<>();
            ListTag a = c.getList("args", Tag.TAG_STRING);
            for (int j = 0; j < a.size(); j++) args.add(a.getString(j));
            o.chronicle.addLast(new Event(c.getLong("day"), c.getString("key"), args));
        }
        return o;
    }
}
