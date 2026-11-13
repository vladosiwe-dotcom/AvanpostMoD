package com.vladosiwe.lordsoutpost;

import com.mojang.logging.LogUtils;
import com.vladosiwe.lordsoutpost.config.OutpostConfig;
import com.vladosiwe.lordsoutpost.registry.ModBlocks;
import com.vladosiwe.lordsoutpost.registry.ModEntities;
import com.vladosiwe.lordsoutpost.registry.ModItems;
import com.vladosiwe.lordsoutpost.registry.ModWorldgen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Точка входа Lord's Outpost. Здесь только регистрация; логика разнесена по пакетам:
 * blueprint (чертежи), outpost (модель), data (сохранение и A-Life), world (генерация),
 * entity (жители и бойцы), protection (права), network и client (карта и интерфейс).
 */
@Mod(LordsOutpost.MODID)
public final class LordsOutpost {
    public static final String MODID = "lordsoutpost";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LordsOutpost(IEventBus modBus, ModContainer container) {
        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModItems.TABS.register(modBus);
        ModEntities.ENTITIES.register(modBus);
        ModWorldgen.STRUCTURE_TYPES.register(modBus);
        ModWorldgen.PIECE_TYPES.register(modBus);
        modBus.addListener(ModEntities::attributes);
        container.registerConfig(ModConfig.Type.SERVER, OutpostConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
