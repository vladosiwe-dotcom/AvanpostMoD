package com.vladosiwe.lordsoutpost.blueprint;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Набор материалов форта. Планировка одна и та же, а палитра подбирается по биому,
 * поэтому аванпост в тайге и в пустыне выглядят по-разному.
 */
public enum Palette {
    TIMBER("timber",
            Blocks.OAK_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.OAK_FENCE, Blocks.OAK_FENCE_GATE,
            Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.COBBLESTONE_WALL,
            Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB,
            Blocks.DIRT_PATH, Blocks.COBBLESTONE, Blocks.WHITE_WOOL, Blocks.RED_WOOL, Blocks.RED_BED, Blocks.RED_CARPET),
    NORTHERN("northern",
            Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_FENCE_GATE,
            Blocks.STONE_BRICKS, Blocks.COBBLED_DEEPSLATE, Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICK_WALL,
            Blocks.DEEPSLATE_TILES, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB,
            Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.LIGHT_GRAY_WOOL, Blocks.BLUE_WOOL, Blocks.BLUE_BED, Blocks.BLUE_CARPET),
    DESERT("desert",
            Blocks.ACACIA_LOG, Blocks.ACACIA_PLANKS, Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB, Blocks.ACACIA_FENCE, Blocks.ACACIA_FENCE_GATE,
            Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE_STAIRS, Blocks.SANDSTONE_SLAB, Blocks.SANDSTONE_WALL,
            Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB,
            Blocks.GRAVEL, Blocks.SANDSTONE, Blocks.ORANGE_WOOL, Blocks.YELLOW_WOOL, Blocks.YELLOW_BED, Blocks.YELLOW_CARPET),
    DARKWOOD("darkwood",
            Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_FENCE_GATE,
            Blocks.MOSSY_COBBLESTONE, Blocks.MOSSY_STONE_BRICKS, Blocks.MOSSY_STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_SLAB, Blocks.MOSSY_COBBLESTONE_WALL,
            Blocks.MUD_BRICKS, Blocks.MUD_BRICK_STAIRS, Blocks.MUD_BRICK_SLAB,
            Blocks.DIRT_PATH, Blocks.COBBLESTONE, Blocks.GREEN_WOOL, Blocks.PURPLE_WOOL, Blocks.PURPLE_BED, Blocks.PURPLE_CARPET);

    public static final Palette[] VALUES = values();

    public final String id;
    public final Block log, planks, stairs, slab, fence, fenceGate;
    public final Block stone, stoneAlt, stoneStairs, stoneSlab, stoneWall;
    public final Block roof, roofStairs, roofSlab;
    public final Block path, foundation, cloth, accent, bed, carpet;

    Palette(String id, Block log, Block planks, Block stairs, Block slab, Block fence, Block fenceGate,
            Block stone, Block stoneAlt, Block stoneStairs, Block stoneSlab, Block stoneWall,
            Block roof, Block roofStairs, Block roofSlab,
            Block path, Block foundation, Block cloth, Block accent, Block bed, Block carpet) {
        this.id = id;
        this.log = log; this.planks = planks; this.stairs = stairs; this.slab = slab; this.fence = fence; this.fenceGate = fenceGate;
        this.stone = stone; this.stoneAlt = stoneAlt; this.stoneStairs = stoneStairs; this.stoneSlab = stoneSlab; this.stoneWall = stoneWall;
        this.roof = roof; this.roofStairs = roofStairs; this.roofSlab = roofSlab;
        this.path = path; this.foundation = foundation; this.cloth = cloth; this.accent = accent; this.bed = bed; this.carpet = carpet;
    }

    public BlockState s(Block b) {
        return b.defaultBlockState();
    }

    public static Palette byId(String id) {
        for (Palette p : VALUES) if (p.id.equals(id)) return p;
        return TIMBER;
    }
}
