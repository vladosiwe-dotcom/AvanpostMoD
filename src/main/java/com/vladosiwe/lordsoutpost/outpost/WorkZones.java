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
    private int version;

    /** Якорь, заданный Лордом, или null, если зона стоит по чертежу. */
    @Nullable
    public BlockPos override(WorkZone zone) {
        return anchors.get(zone);
    }

    public Map<WorkZone, BlockPos> overrides() {
        return anchors;
    }

    /** Переносит зону; профессия заметит смену версии и бросит работу на старом месте. */
    public void relocate(WorkZone zone, @Nullable BlockPos anchor) {
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
        return t;
    }

    public void load(CompoundTag t) {
        anchors.clear();
        for (String key : t.getAllKeys()) {
            WorkZone z = WorkZone.byId(key);
            if (z != null && z.relocatable) anchors.put(z, BlockPos.of(t.getLong(key)));
        }
    }
}
