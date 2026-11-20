package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Реестр рабочих зон одного аванпоста: перенесённые Лордом якоря (сохраняются), текущие проблемы зон,
 * о которых работники сообщают планировщику, и счётчик изменений для профессий.
 */
public final class WorkZones {
    private final EnumMap<WorkZone, BlockPos> anchors = new EnumMap<>(WorkZone.class);
    private final EnumMap<WorkZone, String> problems = new EnumMap<>(WorkZone.class);
    private final EnumMap<WorkZone, UUID> holders = new EnumMap<>(WorkZone.class);
    /** Зона перенесена в постройку-заказ (загон, кузница, шахта): id этого заказа. */
    private final EnumMap<WorkZone, Long> links = new EnumMap<>(WorkZone.class);
    /** Где зона стояла до последнего переноса (оттуда уводят животных и забирают печи). */
    private final EnumMap<WorkZone, BlockPos> previous = new EnumMap<>(WorkZone.class);
    /** Сколько блоков нового шахтного ствола уже прокопано (новая шахта готова на глубине MINE_DEPTH). */
    public int mineDepth;
    public static final int MINE_DEPTH = 6;
    private int version;

    /** Якорь, заданный Лордом, или null, если зона стоит по чертежу. */
    @Nullable
    public BlockPos override(WorkZone zone) {
        return anchors.get(zone);
    }

    public Map<WorkZone, BlockPos> overrides() {
        return anchors;
    }

    /**
     * Зона переезжает в новую постройку (заказ Лорда). Прежнее место запоминается: скотовод уведёт оттуда скот,
     * кузнец заберёт содержимое печей. Для шахты начинается проходка нового ствола.
     */
    public void rebind(WorkZone zone, BlockPos anchor, long projectId, BlockPos was) {
        previous.put(zone, was.immutable());
        links.put(zone, projectId);
        anchors.put(zone, anchor.immutable());
        problems.remove(zone);
        if (zone == WorkZone.MINE) mineDepth = 0;
        version++;
    }

    @Nullable
    public Long link(WorkZone zone) {
        return links.get(zone);
    }

    @Nullable
    public BlockPos previous(WorkZone zone) {
        return previous.get(zone);
    }

    public void forgetPrevious(WorkZone zone) {
        previous.remove(zone);
    }

    /** Переносит зону; профессия заметит смену версии и бросит работу на старом месте. */
    public void relocate(WorkZone zone, @Nullable BlockPos anchor) {
        links.remove(zone);
        if (anchor == null) anchors.remove(zone);
        else anchors.put(zone, anchor.immutable());
        problems.remove(zone);
        version++;
    }

    /** Увеличивается при каждом переносе: так профессии узнают, что кэш зоны устарел. */
    public int version() {
        return version;
    }

    /** Работник сообщает, что зона недоступна (нет калитки, нет почвы, чанк не загружен…). */
    public void report(WorkZone zone, String problem) {
        problems.put(zone, problem);
    }

    public void clearProblem(WorkZone zone) {
        problems.remove(zone);
    }

    @Nullable
    public String problem(WorkZone zone) {
        return problems.get(zone);
    }

    public Map<WorkZone, String> problems() {
        return problems;
    }

    /** Кто сейчас работает в зоне (для справки и разрешения конфликтов, не сохраняется). */
    public void hold(WorkZone zone, @Nullable UUID resident) {
        if (resident == null) holders.remove(zone);
        else holders.put(zone, resident);
    }

    @Nullable
    public UUID holder(WorkZone zone) {
        return holders.get(zone);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        for (var e : anchors.entrySet()) t.putLong(e.getKey().id, e.getValue().asLong());
        CompoundTag l = new CompoundTag();
        for (var e : links.entrySet()) l.putLong(e.getKey().id, e.getValue());
        t.put("_links", l);
        CompoundTag p = new CompoundTag();
        for (var e : previous.entrySet()) p.putLong(e.getKey().id, e.getValue().asLong());
        t.put("_previous", p);
        t.putInt("_mineDepth", mineDepth);
        return t;
    }

    public void load(CompoundTag t) {
        anchors.clear();
        links.clear();
        previous.clear();
        CompoundTag l = t.getCompound("_links");
        for (String key : l.getAllKeys()) {
            WorkZone z = WorkZone.byId(key);
            if (z != null) links.put(z, l.getLong(key));
        }
        for (String key : t.getAllKeys()) {
            WorkZone z = WorkZone.byId(key);
            if (z != null && (z.relocatable || links.containsKey(z))) anchors.put(z, BlockPos.of(t.getLong(key)));
        }
        CompoundTag p = t.getCompound("_previous");
        for (String key : p.getAllKeys()) {
            WorkZone z = WorkZone.byId(key);
            if (z != null) previous.put(z, BlockPos.of(p.getLong(key)));
        }
        mineDepth = t.contains("_mineDepth") ? t.getInt("_mineDepth") : MINE_DEPTH;
    }
}
