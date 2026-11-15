package com.vladosiwe.lordsoutpost.network;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.Util;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.neoforged.neoforge.common.Tags;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Карта биомов для местности, которую игрок ещё не видел. Считается по шуму генератора в фоновом потоке:
 * чанки при этом не загружаются и не создаются. Результат — по байту («вид местности») на точку 16×16 блоков.
 */
public final class BiomeTiles {
    public static final int SIZE = 64;
    public static final int OCEAN = 0, DEEP_OCEAN = 1, RIVER = 2, BEACH = 3, PLAINS = 4, FOREST = 5, TAIGA = 6, SNOW = 7,
            DESERT = 8, BADLANDS = 9, SAVANNA = 10, JUNGLE = 11, SWAMP = 12, MOUNTAIN = 13, DARK = 14, OTHER = 15;

    private static final Long2ObjectLinkedOpenHashMap<byte[]> CACHE = new Long2ObjectLinkedOpenHashMap<>();
    private static final LongSet IN_FLIGHT = new LongOpenHashSet();

    private BiomeTiles() {}

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            IN_FLIGHT.clear();
        }
    }

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public static void request(ServerLevel level, ServerPlayer player, int rx, int rz) {
        // Только регионы в разумной близости от игрока: карта не должна раскрывать весь мир по запросу.
        int prx = Math.floorDiv(player.getBlockX(), SIZE * 16), prz = Math.floorDiv(player.getBlockZ(), SIZE * 16);
        if (Math.abs(rx - prx) > 4 || Math.abs(rz - prz) > 4) return;
        long key = key(rx, rz);
        byte[] cached;
        synchronized (CACHE) {
            cached = CACHE.getAndMoveToLast(key);
            if (cached == null) {
                if (IN_FLIGHT.size() >= 6 || !IN_FLIGHT.add(key)) return;
            }
        }
        if (cached != null) {
            ModNetwork.send(player, new Payloads.BiomeTile(rx, rz, cached));
            return;
        }
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        int y = QuartPos.fromBlock(level.getSeaLevel() + 8);
        UUID id = player.getUUID();
        CompletableFuture.supplyAsync(() -> compute(source, sampler, rx, rz, y), Util.backgroundExecutor())
                .whenCompleteAsync((bytes, error) -> {
                    synchronized (CACHE) {
                        IN_FLIGHT.remove(key);
                        if (bytes != null) {
                            CACHE.putAndMoveToLast(key, bytes);
                            while (CACHE.size() > 256) CACHE.removeFirst();
                        }
                    }
                    ServerPlayer target = level.getServer().getPlayerList().getPlayer(id);
                    if (bytes != null && target != null) ModNetwork.send(target, new Payloads.BiomeTile(rx, rz, bytes));
                }, level.getServer());
    }

    private static byte[] compute(BiomeSource source, Climate.Sampler sampler, int rx, int rz, int quartY) {
        byte[] out = new byte[SIZE * SIZE];
        int baseX = rx * SIZE * 16, baseZ = rz * SIZE * 16;
        for (int j = 0; j < SIZE; j++)
            for (int i = 0; i < SIZE; i++) {
                Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(baseX + i * 16 + 8), quartY, QuartPos.fromBlock(baseZ + j * 16 + 8), sampler);
                out[j * SIZE + i] = (byte) kind(biome);
            }
        return out;
    }

    public static int kind(Holder<Biome> b) {
        if (b.is(BiomeTags.IS_DEEP_OCEAN)) return DEEP_OCEAN;
        if (b.is(BiomeTags.IS_OCEAN)) return OCEAN;
        if (b.is(BiomeTags.IS_RIVER)) return RIVER;
        if (b.is(BiomeTags.IS_BEACH)) return BEACH;
        if (b.is(BiomeTags.IS_BADLANDS)) return BADLANDS;
        if (b.is(Tags.Biomes.IS_DESERT)) return DESERT;
        if (b.is(BiomeTags.IS_SAVANNA)) return SAVANNA;
        if (b.is(BiomeTags.IS_JUNGLE)) return JUNGLE;
        if (b.is(Tags.Biomes.IS_SWAMP)) return SWAMP;
        if (b.is(Biomes.DARK_FOREST)) return DARK;
        if (b.is(Tags.Biomes.IS_SNOWY)) return SNOW;
        if (b.is(BiomeTags.IS_TAIGA)) return TAIGA;
        if (b.is(BiomeTags.IS_MOUNTAIN)) return MOUNTAIN;
        if (b.is(BiomeTags.IS_FOREST)) return FOREST;
        if (b.is(Tags.Biomes.IS_PLAINS)) return PLAINS;
        return b.is(BiomeTags.IS_OVERWORLD) ? PLAINS : OTHER;
    }
}
