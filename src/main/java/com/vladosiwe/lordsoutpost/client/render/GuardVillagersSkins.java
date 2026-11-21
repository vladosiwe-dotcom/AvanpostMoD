package com.vladosiwe.lordsoutpost.client.render;

import com.vladosiwe.lordsoutpost.entity.FighterEntity;
import com.vladosiwe.lordsoutpost.outpost.Role;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Скины стражи из мода Guard Villagers. Его текстуры распространяются с правами «All Rights Reserved», поэтому в наш
 * jar они не входят: если Guard Villagers установлен у игрока, страж форта берёт его скин прямо из ресурсов того мода
 * (как его собственные стражи — по типу жителя биома: равнины, пустыня, саванна, снега, тайга, джунгли, болото).
 * Без Guard Villagers — наши скины.
 */
public final class GuardVillagersSkins {
    private static final String MODID = "guardvillagers";
    private static final boolean LOADED = net.neoforged.fml.ModList.get().isLoaded(MODID);
    private static final ResourceLocation GUARD = ResourceLocation.fromNamespaceAndPath(MODID, "textures/entity/guard/guard.png");
    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();
    /** Для проверки в разработке: скин стражи и на мародёрах (-Dlordsoutpost.guardSkinsOnAll=true). */
    private static final boolean ON_ALL = Boolean.getBoolean("lordsoutpost.guardSkinsOnAll");

    private GuardVillagersSkins() {}

    /**
     * Скин стражи Guard Villagers нарисован под их модель-жителя (текстура 128×128: голова 8×10×8, туловище, руки,
     * ноги в своих местах, плюс накладка по биому). Наши стражи — на скелете игрока (и у Epic Fight, и без него),
     * поэтому скин пересобирается здесь, в памяти клиента, в раскладку игрока 64×64: каждая грань каждой части
     * переносится на своё место, голова сжимается с 10 до 8 пикселей. Источник — ресурсы установленного мода.
     */
    private static ResourceLocation compose(String type) {
        var rm = Minecraft.getInstance().getResourceManager();
        ResourceLocation variant = ResourceLocation.fromNamespaceAndPath(MODID, "textures/entity/guard/guard_variants/guard_" + type + ".png");
        try (var baseIn = rm.open(GUARD)) {
            NativeImage src = NativeImage.read(baseIn);
            if (rm.getResource(variant).isPresent()) {
                try (var overIn = rm.open(variant); NativeImage over = NativeImage.read(overIn)) {
                    int w = Math.min(src.getWidth(), over.getWidth()), h = Math.min(src.getHeight(), over.getHeight());
                    for (int x = 0; x < w; x++)
                        for (int y = 0; y < h; y++) {
                            int o = over.getPixelRGBA(x, y);
                            if ((o >>> 24) != 0) src.blendPixel(x, y, o);
                        }
                }
            }
            // Текстура может быть в другом разрешении, чем 128 единиц развёртки.
            float k = src.getWidth() / 128f;
            NativeImage dst = new NativeImage(64, 64, true);
            copy(src, k, 49, 99, 8, 10, 8, dst, 0, 0, 8, 8, 8, false);    // голова (10 → 8)
            copy(src, k, 52, 50, 8, 12, 4, dst, 16, 16, 8, 12, 4, false); // туловище
            copy(src, k, 32, 75, 4, 12, 4, dst, 40, 16, 4, 12, 4, true);  // правая рука (у них отражена)
            copy(src, k, 33, 48, 4, 12, 4, dst, 32, 48, 4, 12, 4, false); // левая рука
            copy(src, k, 16, 48, 4, 12, 4, dst, 0, 16, 4, 12, 4, true);   // правая нога (отражена)
            copy(src, k, 16, 28, 4, 12, 4, dst, 16, 48, 4, 12, 4, false); // левая нога
            src.close();
            ResourceLocation id = com.vladosiwe.lordsoutpost.LordsOutpost.id("dynamic/guardvillagers_guard_" + type);
            Minecraft.getInstance().getTextureManager().register(id, new net.minecraft.client.renderer.texture.DynamicTexture(dst));
            return id;
        } catch (java.io.IOException | RuntimeException e) {
            com.vladosiwe.lordsoutpost.LordsOutpost.LOGGER.warn("Guard Villagers skin {}: {}", type, e.toString());
            return null;
        }
    }

    /** Грани куба в развёртке Minecraft: top, bottom, right, front, left, back → {x, y, w, h}. */
    private static int[][] faces(int u, int v, int w, int h, int d) {
        return new int[][]{{u + d, v, w, d}, {u + d + w, v, w, d}, {u, v + d, d, h},
                {u + d, v + d, w, h}, {u + d + w, v + d, d, h}, {u + 2 * d + w, v + d, w, h}};
    }

    private static void copy(NativeImage src, float k, int su, int sv, int sw, int sh, int sd,
                             NativeImage dst, int du, int dv, int dw, int dh, int dd, boolean mirrored) {
        int[][] sf = faces(su, sv, sw, sh, sd), df = faces(du, dv, dw, dh, dd);
        for (int f = 0; f < 6; f++) {
            // У отражённой части правая и левая грани меняются местами, а каждая грань зеркалится.
            int[] s = sf[mirrored && (f == 2 || f == 4) ? 6 - f : f], d = df[f];
            for (int dx = 0; dx < d[2]; dx++)
                for (int dy = 0; dy < d[3]; dy++) {
                    int sx = mirrored ? s[2] - 1 - dx * s[2] / d[2] : dx * s[2] / d[2];
                    int sy = dy * s[3] / d[3];
                    int px = (int) ((s[0] + sx) * k), py = (int) ((s[1] + sy) * k);
                    if (px < src.getWidth() && py < src.getHeight()) dst.setPixelRGBA(d[0] + dx, d[1] + dy, src.getPixelRGBA(px, py));
                }
        }
    }

    public static boolean active() {
        return LOADED;
    }

    /** Скин Guard Villagers для бойца или null (мод не установлен, это мародёр или капитан). */
    @Nullable
    public static ResourceLocation texture(FighterEntity entity) {
        if (!LOADED || (!ON_ALL && (entity.hostileFaction() || entity.role() == Role.CAPTAIN))) return null;
        VillagerType type = VillagerType.byBiome(entity.level().getBiome(entity.blockPosition()));
        String name = BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath();
        if (!CACHE.containsKey(name)) CACHE.put(name, compose(name));
        return CACHE.get(name);
    }
}
