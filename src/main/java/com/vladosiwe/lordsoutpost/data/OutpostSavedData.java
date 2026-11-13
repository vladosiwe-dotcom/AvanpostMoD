package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Все аванпосты мира. Хранится в данных Верхнего мира (data/lordsoutpost.dat) и не зависит от того,
 * загружены ли чанки фортов: именно поэтому поселения живут, пока игрока нет рядом.
 */
public final class OutpostSavedData extends SavedData {
    public static final String NAME = "lordsoutpost";
    /** Сторона региона сетки в чанках (1024 блока). */
    public static final int REGION_CHUNKS = 64;
    private static final long MANUAL_BASE = 1L << 48;

    /** Что игрок знает о мире: слухи (знак вопроса на карте) и разведанные форты. */
    public static final class Knowledge {
        public final LongSet hinted = new LongOpenHashSet();
        public final LongSet discovered = new LongOpenHashSet();
    }

    private final Long2ObjectMap<Outpost> outposts = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<List<Outpost>> byRegion = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, Long> lords = new HashMap<>();
    private final Map<UUID, Knowledge> knowledge = new HashMap<>();
    /** Регионы, где при загрузке чанков форта не оказалось (мир создан до установки мода). */
    private final LongSet emptyRegions = new LongOpenHashSet();
    private long manualCounter;

    public static final Factory<OutpostSavedData> FACTORY = new Factory<>(OutpostSavedData::new, OutpostSavedData::load);

    public static OutpostSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public static OutpostSavedData get(ServerLevel level) {
        return get(level.getServer());
    }

    // ============================== Ключи ==============================

    public static int regionOf(int block) {
        return Math.floorDiv(block >> 4, REGION_CHUNKS);
    }

    public static long regionKey(int rx, int rz) {
        return ((rx & 0xFFFFFL) << 20) | (rz & 0xFFFFFL);
    }

    public static boolean isManualId(long id) {
        return id >= MANUAL_BASE;
    }

    public long nextManualId() {
        setDirty();
        return MANUAL_BASE + (++manualCounter);
    }

    // ============================== Доступ ==============================

    public Collection<Outpost> all() {
        return outposts.values();
    }

    @Nullable
    public Outpost byId(long id) {
        return outposts.get(id);
    }

    public void add(Outpost o) {
        outposts.put(o.id, o);
        index(o);
        if (o.state == OutpostState.OWNED && o.owner != null) lords.put(o.owner, o.id);
        setDirty();
    }

    public void remove(Outpost o) {
        outposts.remove(o.id);
        List<Outpost> list = byRegion.get(regionKey(regionOf(o.center.getX()), regionOf(o.center.getZ())));
        if (list != null) list.remove(o);
        if (o.owner != null && Long.valueOf(o.id).equals(lords.get(o.owner))) lords.remove(o.owner);
        for (Outpost other : outposts.values()) other.relations.remove(o.id);
        setDirty();
    }

    private void index(Outpost o) {
        byRegion.computeIfAbsent(regionKey(regionOf(o.center.getX()), regionOf(o.center.getZ())), k -> new ArrayList<>(1)).add(o);
    }

    /** Аванпост, на территории которого находится точка. */
    @Nullable
    public Outpost findAt(BlockPos pos) {
        return findAt(pos.getX(), pos.getY(), pos.getZ());
    }

    @Nullable
    public Outpost findAt(int x, int y, int z) {
        // Территория может заходить в соседний регион, поэтому проверяются регионы четырёх углов окна.
        int rx0 = regionOf(x - 48), rx1 = regionOf(x + 48), rz0 = regionOf(z - 48), rz1 = regionOf(z + 48);
        for (int rx = rx0; rx <= rx1; rx++)
            for (int rz = rz0; rz <= rz1; rz++) {
                List<Outpost> list = byRegion.get(regionKey(rx, rz));
                if (list == null) continue;
                for (int i = 0; i < list.size(); i++) if (list.get(i).contains(x, y, z)) return list.get(i);
            }
        return null;
    }

    /** Аванпосты, центр которых не дальше range блоков от точки. */
    public List<Outpost> near(double x, double z, double range) {
        List<Outpost> out = new ArrayList<>();
        int rx0 = regionOf((int) (x - range)), rx1 = regionOf((int) (x + range));
        int rz0 = regionOf((int) (z - range)), rz1 = regionOf((int) (z + range));
        double r2 = range * range;
        if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > outposts.size()) {
            for (Outpost o : outposts.values()) if (o.distanceSqr(x, z) <= r2) out.add(o);
            return out;
        }
        for (int rx = rx0; rx <= rx1; rx++)
            for (int rz = rz0; rz <= rz1; rz++) {
                List<Outpost> list = byRegion.get(regionKey(rx, rz));
                if (list == null) continue;
                for (Outpost o : list) if (o.distanceSqr(x, z) <= r2) out.add(o);
            }
        return out;
    }

    @Nullable
    public Outpost gridOutpost(int rx, int rz) {
        return outposts.get(regionKey(rx, rz));
    }

    // ============================== Лорды ==============================

    @Nullable
    public Outpost lordOutpost(UUID player) {
        Long id = lords.get(player);
        if (id == null) return null;
        Outpost o = outposts.get(id);
        if (o == null || !o.isLord(player)) {
            lords.remove(player);
            return null;
        }
        return o;
    }

    public void setLord(Outpost o, @Nullable UUID player) {
        if (o.owner != null && Long.valueOf(o.id).equals(lords.get(o.owner))) lords.remove(o.owner);
        if (player != null) lords.put(player, o.id);
        setDirty();
    }

    public Knowledge knowledge(UUID player) {
        return knowledge.computeIfAbsent(player, k -> new Knowledge());
    }

    public boolean regionEmpty(int rx, int rz) {
        return emptyRegions.contains(regionKey(rx, rz));
    }

    public void markRegionEmpty(int rx, int rz) {
        if (emptyRegions.add(regionKey(rx, rz))) setDirty();
    }

    // ============================== Сохранение ==============================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Outpost o : outposts.values()) list.add(o.save(registries));
        tag.put("outposts", list);
        ListTag kn = new ListTag();
        for (var e : knowledge.entrySet()) {
            CompoundTag k = new CompoundTag();
            k.putUUID("player", e.getKey());
            k.putLongArray("hinted", e.getValue().hinted.toLongArray());
            k.putLongArray("discovered", e.getValue().discovered.toLongArray());
            kn.add(k);
        }
        tag.put("knowledge", kn);
        tag.putLongArray("emptyRegions", emptyRegions.toLongArray());
        tag.putLong("manualCounter", manualCounter);
        return tag;
    }

    private static OutpostSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        OutpostSavedData data = new OutpostSavedData();
        ListTag list = tag.getList("outposts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                Outpost o = Outpost.load(list.getCompound(i), registries);
                data.outposts.put(o.id, o);
                data.index(o);
                if (o.state == OutpostState.OWNED && o.owner != null) data.lords.put(o.owner, o.id);
            } catch (RuntimeException e) {
                com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.error("Не удалось прочитать аванпост #{}, запись пропущена", i, e);
            }
        }
        ListTag kn = tag.getList("knowledge", Tag.TAG_COMPOUND);
        for (int i = 0; i < kn.size(); i++) {
            CompoundTag k = kn.getCompound(i);
            if (!k.hasUUID("player")) continue;
            Knowledge kk = data.knowledge(k.getUUID("player"));
            for (long l : k.getLongArray("hinted")) kk.hinted.add(l);
            for (long l : k.getLongArray("discovered")) kk.discovered.add(l);
        }
        for (long l : tag.getLongArray("emptyRegions")) data.emptyRegions.add(l);
        data.manualCounter = tag.getLong("manualCounter");
        return data;
    }
}
