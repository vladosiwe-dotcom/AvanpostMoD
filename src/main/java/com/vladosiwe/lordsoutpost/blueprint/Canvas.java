package com.vladosiwe.lordsoutpost.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Холст чертежа: модули форта «рисуют» блоки в локальных координатах (вход модуля смотрит на юг, +Z),
 * а холст переносит и поворачивает их на место. Позже записанный блок заменяет ранний.
 * Номер этапа (stage) задаёт порядок стройки: сначала стены, потом здания.
 */
public final class Canvas {
    /** @param chain блок ставится вместе с предыдущим (вторая половина двери, кровати). */
    public record Cell(BlockState state, int stage, int group, int seq, boolean chain) {}

    private final Map<BlockPos, Cell> cells = new HashMap<>();
    private final EnumMap<Marker, List<BlockPos>> markers = new EnumMap<>(Marker.class);
    private int stage;
    private int group;
    private int seq;
    private int ox, oy, oz;
    private Rotation rot = Rotation.NONE;

    public void stage(int stage) {
        this.stage = stage;
        this.group++;
    }

    /** Рисует модуль в точке (x, z) с поворотом; внутри локальные координаты. */
    public void at(int x, int z, Rotation rotation, Runnable module) {
        int px = ox, py = oy, pz = oz;
        Rotation pr = rot;
        ox = x; oy = 0; oz = z; rot = rotation;
        group++;
        try {
            module.run();
        } finally {
            ox = px; oy = py; oz = pz; rot = pr;
        }
    }

    private BlockPos world(int x, int y, int z) {
        return switch (rot) {
            case NONE -> new BlockPos(ox + x, oy + y, oz + z);
            case CLOCKWISE_90 -> new BlockPos(ox - z, oy + y, oz + x);
            case CLOCKWISE_180 -> new BlockPos(ox - x, oy + y, oz - z);
            case COUNTERCLOCKWISE_90 -> new BlockPos(ox + z, oy + y, oz - x);
        };
    }

    public void set(int x, int y, int z, BlockState state) {
        put(x, y, z, state, false);
    }

    public void set(int x, int y, int z, Block block) {
        put(x, y, z, block.defaultBlockState(), false);
    }

    /** Блок, который ставится одновременно с предыдущим записанным. */
    public void chained(int x, int y, int z, BlockState state) {
        put(x, y, z, state, true);
    }

    private void put(int x, int y, int z, BlockState state, boolean chain) {
        BlockState rotated = rot == Rotation.NONE ? state : state.rotate(rot);
        cells.put(world(x, y, z), new Cell(rotated, stage, group, seq++, chain));
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    set(x, y, z, state);
    }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, Block block) {
        fill(x1, y1, z1, x2, y2, z2, block.defaultBlockState());
    }

    /** Четыре стены прямоугольника без пола и потолка. */
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, BlockState state) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) {
                set(x, y, z1, state);
                set(x, y, z2, state);
            }
            for (int z = z1; z <= z2; z++) {
                set(x1, y, z, state);
                set(x2, y, z, state);
            }
        }
    }

    /** Убирает блоки из чертежа (проёмы дверей и окон). */
    public void clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                    cells.remove(world(x, y, z));
    }

    /** Явный воздух: блок нужно вынуть (шахта, колодец). */
    public void air(int x1, int y1, int z1, int x2, int y2, int z2) {
        fill(x1, y1, z1, x2, y2, z2, Blocks.AIR.defaultBlockState());
    }

    public boolean has(int x, int y, int z) {
        return cells.containsKey(world(x, y, z));
    }

    public void mark(Marker marker, int x, int y, int z) {
        markers.computeIfAbsent(marker, m -> new ArrayList<>()).add(world(x, y, z));
    }

    public Map<BlockPos, Cell> cells() {
        return cells;
    }

    public EnumMap<Marker, List<BlockPos>> markers() {
        return markers;
    }

    // ---------- Состояния с ориентацией (в локальных координатах модуля) ----------

    public static BlockState stairs(Block block, Direction facing) {
        return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    public static BlockState stairsTop(Block block, Direction facing) {
        return stairs(block, facing).setValue(BlockStateProperties.HALF, Half.TOP);
    }

    public static BlockState slabTop(Block block) {
        return block.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
    }

    public static BlockState log(Block block, Direction.Axis axis) {
        return block.defaultBlockState().setValue(BlockStateProperties.AXIS, axis);
    }

    public static BlockState facing(Block block, Direction facing) {
        return block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing);
    }
}
