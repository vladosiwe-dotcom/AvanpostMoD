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
        return Math.round(d.progress * bp.plan(d.tier + 1).size());
    }

    // ============================== Чертёж и геометрия ==============================

    public FortBlueprint blueprint() {
        return FortBlueprint.get(layout, palette, rotation);
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
        return Layout.RADIUS[tier];
    }

    /** Радиус с учётом идущего расширения. */
    public int reach() {
        return Layout.RADIUS[!complete() && (steps > 0 || state == OutpostState.OWNED) ? tier + 1 : tier];
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
        double next = Math.min(capacity(r), cur + amount);
        stock.put(r, next);
        return next - cur;
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
        return switch (tier) { case 1 -> 10; case 2 -> 18; default -> 28; };
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
        return sum * walls * law.defense();
    }

    public boolean boosted(Resource r, long now) {
        Long until = boosts.get(r.id);
        return until != null && until > now;
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
        t.putDouble("credit", buildCredit);

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
        for (Caravan c : caravans) car.add(c.save());
        t.put("caravans", car);
        t.putBoolean("autoCaravans", autoCaravans);
        ListTag qs = new ListTag();
        for (Quest q : quests) qs.add(q.save());
        t.put("quests", qs);
        t.putInt("questCounter", questCounter);
        ListTag tr = new ListTag();
        for (ItemStack s : tribute) if (!s.isEmpty()) tr.add(s.save(registries));
        t.put("tribute", tr);
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
        o.buildCredit = t.getDouble("credit");

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
        for (int i = 0; i < car.size(); i++) o.caravans.add(Caravan.load(car.getCompound(i)));
        o.autoCaravans = !t.contains("autoCaravans") || t.getBoolean("autoCaravans");
        ListTag qs = t.getList("quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < qs.size(); i++) o.quests.add(Quest.load(qs.getCompound(i)));
        o.questCounter = t.getInt("questCounter");
        ListTag tr = t.getList("tribute", Tag.TAG_COMPOUND);
        for (int i = 0; i < tr.size(); i++) ItemStack.parse(registries, tr.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(o.tribute::add);
        o.tributeGold = t.getDouble("tributeGold");
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
