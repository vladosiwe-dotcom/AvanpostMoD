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
    private static final ResourceKey<LootTable> MAGE_VANILLA = table("gameplay/mage_work");
    private static final ResourceKey<LootTable> MAGE_IRONS = table("compat/irons/mage_work");
    private static final ResourceKey<LootTable> TREASURE_IRONS = table("compat/irons/captain_treasure");

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
