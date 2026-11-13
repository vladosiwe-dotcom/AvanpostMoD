package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Ресурсы виртуального склада поселения. */
public enum Resource {
    WOOD("wood", Items.OAK_LOG, 0xB5803C),
    STONE("stone", Items.COBBLESTONE, 0x9A9A9A),
    IRON("iron", Items.IRON_INGOT, 0xD8D8D8),
    FOOD("food", Items.COOKED_BEEF, 0xC96A3A),
    HIDES("hides", Items.LEATHER, 0xA5662E),
    GOLD("gold", Items.EMERALD, 0x3FD46B),
    ARCANE("arcane", Items.AMETHYST_SHARD, 0xB37BFF);

    public static final Resource[] VALUES = values();

    public final String id;
    public final Item icon;
    public final int color;

    Resource(String id, Item icon, int color) {
        this.id = id;
        this.icon = icon;
        this.color = color;
    }

    public Component label() {
        return Component.translatable("lordsoutpost.resource." + id);
    }

    public static Resource byId(String id) {
        for (Resource r : VALUES) if (r.id.equals(id)) return r;
        return null;
    }
}
