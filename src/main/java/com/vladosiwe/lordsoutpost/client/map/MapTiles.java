package com.vladosiwe.lordsoutpost.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.vladosiwe.lordsoutpost.LordsOutpost;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Рельеф карты. Клиент сам рисует то, что видел: каждый загруженный чанк превращается в плитку 16×16 точек
 * (цвет верхнего блока, тени по перепаду высот, глубина воды). Плитки собраны в регионы 512×512 блоков
 * и сохраняются на диск, поэтому разведанная местность остаётся на карте между сессиями.
 */
public final class MapTiles {
    public static final int REGION = 512;
    private static final int MAX_REGIONS = 48;

    public static final class Region {
        public final int rx, rz;
        final NativeImage image;
        final DynamicTexture texture;
        public final ResourceLocation id;
        boolean needsUpload = true;
        boolean unsaved;

        Region(int rx, int rz, NativeImage image) {
            this.rx = rx;
            this.rz = rz;
            this.image = image;
            this.texture = new DynamicTexture(image);
            this.id = LordsOutpost.id("map/r" + rx + "_" + rz);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }

        /** Загружает изменения в видеопамять (вызывается с потока отрисовки, только когда карта на экране). */
        public void upload() {
            if (needsUpload) {
                texture.upload();
                needsUpload = false;
            }
        }
    }

    private static final Long2ObjectLinkedOpenHashMap<Region> REGIONS = new Long2ObjectLinkedOpenHashMap<>();
    private static final LongSet MISSING = new LongOpenHashSet();
    private static final ArrayDeque<ChunkPos> QUEUE = new ArrayDeque<>();
    private static final LongSet QUEUED = new LongOpenHashSet();
    @Nullable private static Path dir;
    private static String worldKey = "";
    private static int ticks;

    private MapTiles() {}

    private static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public static void enqueue(ChunkPos pos) {
        if (QUEUED.add(pos.toLong())) QUEUE.add(pos);
    }

    /** Смена мира или измерения: у каждого свой набор плиток. */
    private static void ensureWorld(Minecraft mc, ClientLevel level) {
        String server = mc.getSingleplayerServer() != null ? "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName()
                : mc.getCurrentServer() != null ? "mp_" + mc.getCurrentServer().ip : "unknown";
        String key = (server + "/" + level.dimension().location()).replaceAll("[^A-Za-z0-9_./-]", "_").replace(':', '_');
        if (key.equals(worldKey)) return;
        flush();
        worldKey = key;
        dir = mc.gameDirectory.toPath().resolve("lordsoutpost").resolve("map").resolve(key.replace("..", "_"));
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        ensureWorld(mc, level);
        ticks++;
        // Раз в пять секунд перерисовываем чанки вокруг игрока: он мог что-то построить.
        if (ticks % 100 == 0) {
            ChunkPos p = mc.player.chunkPosition();
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) enqueue(new ChunkPos(p.x + dx, p.z + dz));
        }
        for (int i = 0; i < 4 && !QUEUE.isEmpty(); i++) {
            ChunkPos pos = QUEUE.poll();
            QUEUED.remove(pos.toLong());
            LevelChunk chunk = level.getChunkSource().getChunk(pos.x, pos.z, false);
            if (chunk != null && !chunk.isEmpty()) scan(level, chunk);
        }
        if (ticks % 2400 == 0) save();
    }

    private static void scan(ClientLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        Region region = region(Math.floorDiv(cp.getMinBlockX(), REGION), Math.floorDiv(cp.getMinBlockZ(), REGION), true);
        if (region == null) return;
        LevelChunk north = level.getChunkSource().getChunk(cp.x, cp.z - 1, false);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        for (int x = 0; x < 16; x++) {
            int prev = north != null && !north.isEmpty() ? north.getHeight(Heightmap.Types.WORLD_SURFACE, x, 15) : Integer.MIN_VALUE;
            for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockState state = null;
                MapColor color = MapColor.NONE;
                while (y >= minY) {
                    pos.set(cp.getMinBlockX() + x, y, cp.getMinBlockZ() + z);
                    state = chunk.getBlockState(pos);
                    color = state.getMapColor(level, pos);
                    if (color != MapColor.NONE) break;
                    y--;
                }
                int argb;
                if (color == MapColor.NONE) {
                    argb = 0;
                } else if (!state.getFluidState().isEmpty()) {
                    int depth = 0;
                    while (depth < 12 && y - depth - 1 >= minY) {
                        pos.set(cp.getMinBlockX() + x, y - depth - 1, cp.getMinBlockZ() + z);
                        if (chunk.getBlockState(pos).getFluidState().isEmpty()) break;
                        depth++;
                    }
                    argb = shade(color.col, 1.0f - depth * 0.035f);
                } else {
                    float f = prev == Integer.MIN_VALUE || y == prev ? 0.86f : y > prev ? 1.0f : 0.72f;
                    argb = shade(color.col, f);
                }
                prev = y;
                int px = Math.floorMod(cp.getMinBlockX() + x, REGION), pz = Math.floorMod(cp.getMinBlockZ() + z, REGION);
                // NativeImage хранит цвет как ABGR.
                int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                region.image.setPixelRGBA(px, pz, abgr);
            }
        }
        region.needsUpload = true;
        region.unsaved = true;
    }

    private static int shade(int rgb, float f) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Регион из памяти или с диска. create = false — только если он уже разведан. */
    @Nullable
    public static Region region(int rx, int rz, boolean create) {
        long key = key(rx, rz);
        Region region = REGIONS.getAndMoveToLast(key);
        if (region != null) return region;
        if (!create && MISSING.contains(key)) return null;
        NativeImage image = null;
        Path file = file(rx, rz);
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage loaded = NativeImage.read(in);
                if (loaded.getWidth() == REGION && loaded.getHeight() == REGION) image = loaded;
                else loaded.close();
            } catch (IOException | RuntimeException e) {
                LordsOutpost.LOGGER.warn("Не удалось прочитать плитку карты {}: {}", file, e.toString());
            }
        }
        if (image == null) {
            if (!create) {
                MISSING.add(key);
                return null;
            }
            image = new NativeImage(REGION, REGION, true);
        }
        MISSING.remove(key);
        region = new Region(rx, rz, image);
        REGIONS.putAndMoveToLast(key, region);
        while (REGIONS.size() > MAX_REGIONS) {
            Region old = REGIONS.removeFirst();
            write(old);
            Minecraft.getInstance().getTextureManager().release(old.id);
        }
        return region;
    }

    @Nullable
    private static Path file(int rx, int rz) {
        return dir == null ? null : dir.resolve("r." + rx + "." + rz + ".png");
    }

    private static void write(Region region) {
        if (!region.unsaved) return;
        Path file = file(region.rx, region.rz);
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            region.image.writeToFile(file);
            region.unsaved = false;
        } catch (IOException | RuntimeException e) {
            LordsOutpost.LOGGER.warn("Не удалось сохранить плитку карты {}: {}", file, e.toString());
        }
    }

    public static void save() {
        for (Region region : REGIONS.values()) write(region);
    }

    /** Сохраняет и выгружает всё (выход из мира, смена измерения). */
    public static void flush() {
        save();
        for (Region region : REGIONS.values()) Minecraft.getInstance().getTextureManager().release(region.id);
        REGIONS.clear();
        MISSING.clear();
        QUEUE.clear();
        QUEUED.clear();
    }

    public static void reset() {
        flush();
        worldKey = "";
        dir = null;
    }
}
