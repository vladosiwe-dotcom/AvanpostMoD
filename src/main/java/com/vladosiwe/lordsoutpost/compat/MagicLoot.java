package com.vladosiwe.lordsoutpost.compat;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/**
 * Магическая добыча. Если установлен Iron's Spells 'n Spellbooks, используются его свитки и руны
 * (таблицы добычи с условием neoforge:mod_loaded, поэтому без мода они просто не загружаются);
 * иначе — ванильные магические предметы. Жёсткой зависимости от мода нет.
 */
public final class MagicLoot {
    public static final String IRONS = "irons_spellbooks";
    public static final ResourceKey<LootTable> MAGE_VANILLA = table("gameplay/mage_work");
    public static final ResourceKey<LootTable> MAGE_IRONS = table("compat/irons/mage_work");
    public static final ResourceKey<LootTable> TREASURE_IRONS = table("compat/irons/captain_treasure");

    private MagicLoot() {}

    private static ResourceKey<LootTable> table(String path) {
        return ResourceKey.create(Registries.LOOT_TABLE, LordsOutpost.id(path));
    }

    public static boolean ironsLoaded() {
        return ModList.get() != null && ModList.get().isLoaded(IRONS);
    }

    /** Что Придворный Маг создал за один цикл работы. */
    public static List<ItemStack> mageProduct(ServerLevel level, RandomSource rnd) {
        List<ItemStack> out = roll(level, ironsLoaded() ? MAGE_IRONS : MAGE_VANILLA, rnd);
        if (out.isEmpty()) out.add(new ItemStack(rnd.nextBoolean() ? Items.EXPERIENCE_BOTTLE : Items.LAPIS_LAZULI, 2 + rnd.nextInt(3)));
        return out;
    }

    /** Редкие свитки в сундуке Капитана (аванпосты от четырёх черепков). */
    public static List<ItemStack> captainScrolls(ServerLevel level, RandomSource rnd) {
        return ironsLoaded() ? roll(level, TREASURE_IRONS, rnd) : new ArrayList<>();
    }

    /**
     * Уровень заклинания на свитке Iron's Spells (без зависимости от API мода): компоненты предмета из пространства
     * имён мода кодируются в NBT и берётся наибольшее поле "level". Для прочих предметов — 0.
     */
    public static int scrollLevel(ItemStack st) {
        if (!ironsLoaded() || st.isEmpty()) return 0;
        int best = 0;
        for (var typed : st.getComponents()) {
            var key = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(typed.type());
            if (key == null || !IRONS.equals(key.getNamespace())) continue;
            try {
                var tag = typed.encodeValue(net.minecraft.nbt.NbtOps.INSTANCE).result().orElse(null);
                if (tag != null) best = Math.max(best, maxLevel(tag));
            } catch (RuntimeException ignored) {
                // Чужой компонент не закодировался — цена просто без надбавки.
            }
        }
        return Math.min(10, best);
    }

    private static int maxLevel(net.minecraft.nbt.Tag tag) {
        int best = 0;
        if (tag instanceof net.minecraft.nbt.CompoundTag c) {
            for (String k : c.getAllKeys()) {
                net.minecraft.nbt.Tag v = c.get(k);
                if (k.equals("level") && v instanceof net.minecraft.nbt.NumericTag n) best = Math.max(best, n.getAsInt());
                else if (v != null) best = Math.max(best, maxLevel(v));
            }
        } else if (tag instanceof net.minecraft.nbt.ListTag l) {
            for (net.minecraft.nbt.Tag v : l) best = Math.max(best, maxLevel(v));
        }
        return best;
    }

    public static final String[] INK_RARITIES = {"common", "uncommon", "rare", "epic", "legendary"};

    /** Свиток огня: уровень заклинания случайный внутри диапазона редкости чернил (0 — обычные … 4 — легендарные). */
    public static List<ItemStack> fireScroll(ServerLevel level, int rarity, RandomSource rnd) {
        return roll(level, table("compat/irons/fire_scroll_" + INK_RARITIES[Math.max(0, Math.min(4, rarity))]), rnd);
    }

    /** Без Iron's Spells: зачарованная книга со случайной силой зачарования. */
    public static List<ItemStack> mageBook(ServerLevel level, RandomSource rnd) {
        return roll(level, table("gameplay/mage_book"), rnd);
    }

    private static List<ItemStack> roll(ServerLevel level, ResourceKey<LootTable> key, RandomSource rnd) {
        List<ItemStack> out = new ArrayList<>();
        try {
            LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
            if (table == LootTable.EMPTY) return out;
            LootParams params = new LootParams.Builder(level).create(LootContextParamSets.EMPTY);
            table.getRandomItems(params, rnd.nextLong(), out::add);
        } catch (RuntimeException e) {
            LordsOutpost.LOGGER.warn("Не удалось получить добычу {}: {}", key.location(), e.toString());
        }
        return out;
    }
}
