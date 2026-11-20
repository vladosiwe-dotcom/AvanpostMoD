package com.vladosiwe.lordsoutpost.world;

import com.vladosiwe.lordsoutpost.blueprint.FortBlueprint;
import com.vladosiwe.lordsoutpost.blueprint.Layout;
import com.vladosiwe.lordsoutpost.blueprint.Palette;
import com.vladosiwe.lordsoutpost.outpost.Difficulty;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.registry.ModWorldgen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.Map;

/**
 * Единственный кусок структуры: ставит блоки форта по тому же чертежу, по которому потом работают строители.
 * Ограничивающая рамка начинается с первого блока над землёй — так beard_box выравнивает площадку ровно под пол.
 */
public class OutpostPiece extends StructurePiece {
    private final BlockPos center;
    private final Layout layout;
    private final Palette palette;
    private final Rotation rotation;
    private final Difficulty difficulty;
    /** Версия планировки: кусок помнит её сам, чанки старого мира достраиваются по первой. */
    private final int version;

    public OutpostPiece(OutpostSites.Site site) {
        super(ModWorldgen.OUTPOST_PIECE.get(), 0, box(site.center(), site.reach(), site.topTier()));
        this.version = site.version();
        this.center = site.center();
        this.layout = site.layout();
        this.palette = site.palette();
        this.rotation = site.rotation();
        this.difficulty = site.difficulty();
    }

    public OutpostPiece(CompoundTag tag) {
        super(ModWorldgen.OUTPOST_PIECE.get(), tag);
        this.center = new BlockPos(tag.getInt("cx"), tag.getInt("cy"), tag.getInt("cz"));
        this.layout = Layout.byId(tag.getString("layout"));
        this.palette = Palette.byId(tag.getString("palette"));
        this.rotation = Rotation.values()[Math.max(0, Math.min(3, tag.getByte("rot")))];
        this.difficulty = Difficulty.bySkulls(tag.getByte("skulls"));
        this.version = tag.contains("ver") ? Math.max(1, Math.min(2, tag.getByte("ver"))) : 1;
    }

    private static BoundingBox box(BlockPos center, int reach, int topTier) {
        int height = topTier == 1 ? 12 : topTier == 2 ? 16 : 26;
        return new BoundingBox(center.getX() - reach, center.getY() + 1, center.getZ() - reach,
                center.getX() + reach, center.getY() + height, center.getZ() + reach);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext ctx, CompoundTag tag) {
        tag.putInt("cx", center.getX());
        tag.putInt("cy", center.getY());
        tag.putInt("cz", center.getZ());
        tag.putString("layout", layout.id);
        tag.putString("palette", palette.id);
        tag.putByte("rot", (byte) rotation.ordinal());
        tag.putByte("skulls", (byte) difficulty.skulls);
        tag.putByte("ver", (byte) version);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        FortBlueprint bp = FortBlueprint.get(layout, palette, rotation, version);
        Map<BlockPos, BlockState> snapshot = bp.snapshot(difficulty.tier, Outpost.initialSteps(bp, difficulty));
        // Снимок может включать начатое расширение (следующий тир шире): жидкость убирается по полному радиусу.
        int topTier = difficulty.progress > 0 && difficulty.tier < FortBlueprint.MAX_TIER ? difficulty.tier + 1 : difficulty.tier;
        FortPlacer.clearFluids(level, center, Layout.radius(version, topTier) + Layout.MARGIN,
                box, rel -> snapshot.containsKey(rel));
        FortPlacer.place(level, center, bp, difficulty, snapshot, box, random);
    }
}
