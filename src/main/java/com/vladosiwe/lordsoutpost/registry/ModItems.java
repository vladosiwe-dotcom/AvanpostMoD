package com.vladosiwe.lordsoutpost.registry;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.item.LordStandardItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LordsOutpost.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, LordsOutpost.MODID);

    public static final DeferredItem<LordStandardItem> LORD_STANDARD = ITEMS.registerItem("lord_standard",
            p -> new LordStandardItem(ModBlocks.LORD_STANDARD.get(), p), new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());
    public static final DeferredItem<BlockItem> OUTPOST_GATE = ITEMS.registerSimpleBlockItem(ModBlocks.OUTPOST_GATE);
    public static final DeferredItem<BlockItem> QUEST_BOARD = ITEMS.registerSimpleBlockItem(ModBlocks.QUEST_BOARD);
    public static final DeferredItem<BlockItem> POSTBOX = ITEMS.registerSimpleBlockItem(ModBlocks.POSTBOX);
    public static final DeferredItem<net.minecraft.world.item.DoubleHighBlockItem> STOREHOUSE_DOOR = ITEMS.registerItem("storehouse_door",
            p -> new net.minecraft.world.item.DoubleHighBlockItem(ModBlocks.STOREHOUSE_DOOR.get(), p));

    public static final DeferredItem<com.vladosiwe.lordsoutpost.item.LordMirrorItem> LORD_MIRROR = ITEMS.registerItem("lord_mirror",
            com.vladosiwe.lordsoutpost.item.LordMirrorItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant());

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.lordsoutpost"))
            .icon(() -> LORD_STANDARD.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(LORD_STANDARD.get());
                out.accept(OUTPOST_GATE.get());
                out.accept(QUEST_BOARD.get());
                out.accept(POSTBOX.get());
                out.accept(STOREHOUSE_DOOR.get());
            })
            .build());

    private ModItems() {}
}
