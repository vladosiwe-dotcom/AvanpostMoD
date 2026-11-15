package com.vladosiwe.lordsoutpost.world;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.neoforged.neoforge.common.Tags;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Места аванпостов. Мир поделён на регионы 1024×1024 блока, в каждом не больше одного форта
 * (ванильное размещение random_spread: шаг 64 чанка, отступ 28, то есть соседи не ближе ~450 блоков).
 *
 * Главное здесь — {@link #evaluate}: чистая функция от сида и генератора. Её вызывает и генерация мира,
 * когда ставит форт, и сервер, когда предсказывает форт в ещё не созданных чанках (знак вопроса на карте).
 * Поэтому предсказание и реальность совпадают, и ради карты не нужно ни грузить, ни генерировать чанки.
 */
public final class OutpostSites {
    public static final ResourceKey<Structure> STRUCTURE = ResourceKey.create(Registries.STRUCTURE, LordsOutpost.id("outpost"));
    /** Перепад высот на площадке, при котором место считается непригодным. */
    public static final int MAX_SLOPE = 14;

    public record Site(BlockPos center, Layout layout, Palette palette, Rotation rotation, Difficulty difficulty) {
        /** Радиус, который форт займёт с учётом уже начатого расширения. */
        public int reach() {
            int tier = difficulty.progress > 0 && difficulty.tier < 3 ? difficulty.tier + 1 : difficulty.tier;
            return Layout.RADIUS[tier] + Layout.MARGIN;
        }

        public int topTier() {
            return difficulty.progress > 0 && difficulty.tier < 3 ? difficulty.tier + 1 : difficulty.tier;
        }
    }

    private static final Long2ObjectMap<Optional<Site>> CACHE = new Long2ObjectOpenHashMap<>();

    private OutpostSites() {}

    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /** Есть ли в этом чанке место для форта и каким он будет. Детерминировано: зависит только от сида и генератора. */
    public static Optional<Site> evaluate(ChunkPos chunk, long seed, ChunkGenerator generator, RandomState randomState,
                                          LevelHeightAccessor heights, Predicate<Holder<Biome>> validBiome) {
        WorldgenRandom rnd = new WorldgenRandom(new LegacyRandomSource(0L));
        rnd.setLargeFeatureSeed(seed ^ 0x4C6F7264L, chunk.x, chunk.z);
        int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();

        Difficulty difficulty = rollDifficulty(rnd, x, z);
        Layout layout = Layout.VALUES[rnd.nextInt(Layout.VALUES.length)];
        Rotation rotation = Rotation.values()[rnd.nextInt(4)];

        int tier = difficulty.progress > 0 && difficulty.tier < 3 ? difficulty.tier + 1 : difficulty.tier;
        int r = Layout.RADIUS[tier] + Layout.MARGIN;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        long sum = 0;
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                int h = generator.getBaseHeight(x + dx * r, z + dz * r, Heightmap.Types.WORLD_SURFACE_WG, heights, randomState) - 1;
                min = Math.min(min, h);
                max = Math.max(max, h);
                sum += h;
                n++;
            }
        if (max - min > MAX_SLOPE) return Optional.empty();
        int floor = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, randomState) - 1;
        int surface = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, heights, randomState) - 1;
        if (floor < surface - 1 || min < generator.getSeaLevel() - 1) return Optional.empty();
        int baseY = (int) Math.round(sum / (double) n);
        if (baseY + 40 >= heights.getMaxBuildHeight() || baseY - 16 <= heights.getMinBuildHeight()) return Optional.empty();

        Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(baseY + 1), QuartPos.fromBlock(z), randomState.sampler());
        if (!validBiome.test(biome)) return Optional.empty();
        return Optional.of(new Site(new BlockPos(x, baseY, z), layout, palette(biome), rotation, difficulty));
    }

    /** Чем дальше от центра мира, тем чаще встречаются сильные аванпосты. */
    private static Difficulty rollDifficulty(WorldgenRandom rnd, int x, int z) {
        double dist = Math.sqrt((double) x * x + (double) z * z);
        double b = Math.max(0, Math.min(1, (dist - 600) / 4000.0));
        double[] w = {34 - 20 * b, 28 - 8 * b, 20 + 6 * b, 12 + 12 * b, 6 + 10 * b};
        double roll = rnd.nextDouble() * 100;
        for (int i = 0; i < w.length; i++) {
            roll -= w[i];
            if (roll < 0) return Difficulty.VALUES[i];
        }
        return Difficulty.FRONTIER_POST;
    }

    public static Palette palette(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Tags.Biomes.IS_SNOWY) || biome.is(Tags.Biomes.IS_COLD_OVERWORLD)) return Palette.NORTHERN;
        if (biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.IS_SAVANNA) || biome.is(Tags.Biomes.IS_DESERT)) return Palette.DESERT;
        if (biome.is(BiomeTags.IS_JUNGLE) || biome.is(Biomes.DARK_FOREST) || biome.is(Tags.Biomes.IS_SWAMP)) return Palette.DARKWOOD;
        return Palette.TIMBER;
    }

    /**
     * Предсказывает форт региона, не трогая чанки. Вызывается с серверного потока.
     * Пустой результат — в регионе нет подходящего места (океан, горы, структуры выключены).
     */
    public static Optional<Site> predict(ServerLevel level, int rx, int rz) {
        long key = OutpostSavedData.regionKey(rx, rz);
        synchronized (CACHE) {
            Optional<Site> cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        Optional<Site> result = compute(level, rx, rz);
        synchronized (CACHE) {
            CACHE.put(key, result);
        }
        return result;
    }

    private static Optional<Site> compute(ServerLevel level, int rx, int rz) {
        if (!level.getServer().getWorldData().worldGenOptions().generateStructures()) return Optional.empty();
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Optional<Holder.Reference<Structure>> holder = registry.getHolder(STRUCTURE);
        if (holder.isEmpty()) return Optional.empty();
        ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
        RandomSpreadStructurePlacement placement = null;
        for (StructurePlacement p : state.getPlacementsForStructure(holder.get())) {
            if (p instanceof RandomSpreadStructurePlacement spread && spread.spacing() == OutpostSavedData.REGION_CHUNKS) placement = spread;
        }
        if (placement == null) return Optional.empty();
        ChunkPos chunk = placement.getPotentialStructureChunk(state.getLevelSeed(), rx * OutpostSavedData.REGION_CHUNKS, rz * OutpostSavedData.REGION_CHUNKS);
        if (!placement.isStructureChunk(state, chunk.x, chunk.z)) return Optional.empty();
        Structure structure = holder.get().value();
        return evaluate(chunk, state.getLevelSeed(), level.getChunkSource().getGenerator(), state.randomState(), level, structure.biomes()::contains);
    }
}
