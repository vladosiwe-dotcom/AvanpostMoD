package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.outpost.Resource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/** Перевод предметов игрока в ресурсы склада поселения (сдача на Доске и бочка снабжения). */
public final class ResourceItems {
    public record Value(Resource resource, double perItem) {}

    public static final TagKey<Item> ARCANE = TagKey.create(Registries.ITEM, LordsOutpost.id("arcane"));

    private ResourceItems() {}

    @Nullable
    public static Value of(ItemStack stack) {
        if (stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (stack.is(ItemTags.LOGS)) return new Value(Resource.WOOD, 4);
        if (stack.is(ItemTags.PLANKS)) return new Value(Resource.WOOD, 1);
        if (stack.is(ItemTags.WOODEN_SLABS) || item == Items.STICK) return new Value(Resource.WOOD, 0.5);
        if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS) || stack.is(ItemTags.STONE_BRICKS) || item == Items.STONE || item == Items.ANDESITE
                || item == Items.DIORITE || item == Items.GRANITE || item == Items.DEEPSLATE || item == Items.SANDSTONE || item == Items.TUFF
                || item == Items.RED_SANDSTONE || item == Items.MOSSY_COBBLESTONE) return new Value(Resource.STONE, 1);
        if (item == Items.IRON_INGOT) return new Value(Resource.IRON, 4);
        if (item == Items.RAW_IRON) return new Value(Resource.IRON, 3);
        if (item == Items.IRON_BLOCK) return new Value(Resource.IRON, 36);
        if (item == Items.RAW_IRON_BLOCK) return new Value(Resource.IRON, 27);
        if (item == Items.IRON_NUGGET) return new Value(Resource.IRON, 0.44);
        if (item == Items.LEATHER) return new Value(Resource.HIDES, 2);
        if (item == Items.RABBIT_HIDE) return new Value(Resource.HIDES, 0.5);
        if (stack.is(ItemTags.WOOL)) return new Value(Resource.HIDES, 1);
        if (item == Items.EMERALD) return new Value(Resource.GOLD, 1);
        if (item == Items.EMERALD_BLOCK) return new Value(Resource.GOLD, 9);
        if (item == Items.GOLD_INGOT) return new Value(Resource.GOLD, 2);
        if (item == Items.GOLD_BLOCK) return new Value(Resource.GOLD, 18);
        if (item == Items.GOLD_NUGGET) return new Value(Resource.GOLD, 0.22);
        if (item == Items.DIAMOND) return new Value(Resource.GOLD, 8);
        if (stack.is(ARCANE)) return new Value(Resource.ARCANE, 2);
        if (item == Items.LAPIS_LAZULI || item == Items.GLOWSTONE_DUST) return new Value(Resource.ARCANE, 1);
        if (item == Items.AMETHYST_SHARD) return new Value(Resource.ARCANE, 2);
        if (item == Items.REDSTONE) return new Value(Resource.ARCANE, 0.5);
        if (item == Items.BLAZE_POWDER) return new Value(Resource.ARCANE, 3);
        if (item == Items.ENDER_PEARL) return new Value(Resource.ARCANE, 6);
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id.getNamespace().equals("irons_spellbooks") && (id.getPath().equals("arcane_essence") || id.getPath().endsWith("_rune"))) {
            return new Value(Resource.ARCANE, 4);
        }
        if (item == Items.WHEAT) return new Value(Resource.FOOD, 1);
        if (item == Items.HAY_BLOCK) return new Value(Resource.FOOD, 9);
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food != null && food.nutrition() > 0 && item != Items.ROTTEN_FLESH && item != Items.SPIDER_EYE && item != Items.POISONOUS_POTATO) {
            return new Value(Resource.FOOD, Math.max(0.5, food.nutrition() / 2.0));
        }
        return null;
    }
}
