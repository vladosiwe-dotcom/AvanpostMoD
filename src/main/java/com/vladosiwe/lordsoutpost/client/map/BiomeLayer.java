package com.vladosiwe.lordsoutpost.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.network.BiomeTiles;
import com.vladosiwe.lordsoutpost.network.Payloads;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * Подложка карты: биомы ещё не разведанной местности (приходят с сервера плитками по региону 1024×1024).
 * Нарисована приглушённо, как старая карта, чтобы отличаться от того, что игрок видел своими глазами.
 */
public final class BiomeLayer {
    public static final int TILE_BLOCKS = BiomeTiles.SIZE * 16;
    private static final int[] COLORS = {
            0x3C5C8C, 0x2F4A78, 0x4A6FA0, 0xC9BE8E, 0x7C9A54, 0x4F7A3E, 0x4C6B52, 0xD9E0E6,
            0xCDBB7C, 0xB06A3C, 0x9C9A4E, 0x3F8A3A, 0x55684A, 0x8A8A86, 0x35502E, 0x6E5A7A};

    private static final Long2ObjectOpenHashMap<ResourceLocation> TILES = new Long2ObjectOpenHashMap<>();
    private static final Long2LongOpenHashMap REQUESTED = new Long2LongOpenHashMap();
    private static long lastRequest;

    private BiomeLayer() {}

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public static void accept(int rx, int rz, byte[] kinds) {
        if (kinds.length != BiomeTiles.SIZE * BiomeTiles.SIZE) return;
        NativeImage image = new NativeImage(BiomeTiles.SIZE, BiomeTiles.SIZE, false);
        for (int j = 0; j < BiomeTiles.SIZE; j++)
            for (int i = 0; i < BiomeTiles.SIZE; i++) {
                int rgb = COLORS[kinds[j * BiomeTiles.SIZE + i] & 15];
                // Лёгкая «бумажная» рябь, чтобы подложка не выглядела заливкой.
                int n = ((i * 7 + j * 13) & 3) - 1;
                int r = Math.max(0, Math.min(255, ((rgb >> 16) & 0xFF) * 3 / 5 + 28 + n));
                int g = Math.max(0, Math.min(255, ((rgb >> 8) & 0xFF) * 3 / 5 + 26 + n));
                int b = Math.max(0, Math.min(255, (rgb & 0xFF) * 3 / 5 + 22 + n));
                image.setPixelRGBA(i, j, 0xFF000000 | (b << 16) | (g << 8) | r);
            }
        ResourceLocation id = LordsOutpost.id("map/biome_" + rx + "_" + rz);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
        TILES.put(key(rx, rz), id);
    }

    /** Плитка региона; если её нет, запрашивает у сервера (не чаще нескольких раз в секунду). */
    @Nullable
    public static ResourceLocation tile(int rx, int rz) {
        long key = key(rx, rz);
        ResourceLocation id = TILES.get(key);
        if (id != null) return id;
        long now = System.currentTimeMillis();
        if (now - lastRequest > 150 && now - REQUESTED.getOrDefault(key, 0L) > 8000 && Minecraft.getInstance().getConnection() != null) {
            lastRequest = now;
            REQUESTED.put(key, now);
            PacketDistributor.sendToServer(new Payloads.TileRequest(rx, rz));
        }
        return null;
    }

    public static void reset() {
        for (ResourceLocation id : TILES.values()) Minecraft.getInstance().getTextureManager().release(id);
        TILES.clear();
        REQUESTED.clear();
    }
}
