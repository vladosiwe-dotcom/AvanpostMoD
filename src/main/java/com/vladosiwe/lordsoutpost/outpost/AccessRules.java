package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Права других игроков на аванпост и глобальные переключатели. */
public final class AccessRules {
    public record Entry(String name, AccessLevel level) {}

    public final Map<UUID, Entry> players = new LinkedHashMap<>();
    /** Уровень для игроков, которых нет в списке. */
    public AccessLevel defaultLevel = AccessLevel.GUEST;
    /** Лорды союзных фортов автоматически считаются союзниками. */
    public boolean autoAllyPacts = true;
    /** Союзники могут открывать Сундук Дани. */
    public boolean alliesTribute = false;
    /** Чужаки не могут ломать и ставить блоки на территории. */
    public boolean protectBlocks = true;

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag list = new ListTag();
        for (var e : players.entrySet()) {
            CompoundTag p = new CompoundTag();
            p.putUUID("id", e.getKey());
            p.putString("name", e.getValue().name());
            p.putString("level", e.getValue().level().id);
            list.add(p);
        }
        t.put("players", list);
        t.putString("default", defaultLevel.id);
        t.putBoolean("autoAlly", autoAllyPacts);
        t.putBoolean("alliesTribute", alliesTribute);
        t.putBoolean("protect", protectBlocks);
        return t;
    }

    public void load(CompoundTag t) {
        players.clear();
        ListTag list = t.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag p = list.getCompound(i);
            if (!p.hasUUID("id")) continue;
            AccessLevel level = AccessLevel.byId(p.getString("level"));
            if (level == AccessLevel.LORD) level = AccessLevel.ALLY;
            players.put(p.getUUID("id"), new Entry(p.getString("name"), level));
        }
        defaultLevel = AccessLevel.byId(t.getString("default"));
        if (defaultLevel == AccessLevel.LORD || defaultLevel == AccessLevel.ALLY) defaultLevel = AccessLevel.GUEST;
        autoAllyPacts = !t.contains("autoAlly") || t.getBoolean("autoAlly");
        alliesTribute = t.getBoolean("alliesTribute");
        protectBlocks = !t.contains("protect") || t.getBoolean("protect");
    }
}
