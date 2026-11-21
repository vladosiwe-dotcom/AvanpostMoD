package com.vladosiwe.lordsoutpost.world;

import com.mojang.serialization.MapCodec;
import com.vladosiwe.lordsoutpost.registry.ModWorldgen;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/** Структура генерации мира: аванпост мародёров. Место и вид выбирает {@link OutpostSites#evaluate}. */
public class OutpostStructure extends Structure {
    public static final MapCodec<OutpostStructure> CODEC = simpleCodec(OutpostStructure::new);

    public OutpostStructure(StructureSettings settings) {
        super(settings);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        return OutpostSites.evaluate(ctx.chunkPos(), ctx.seed(), ctx.chunkGenerator(), ctx.randomState(), ctx.heightAccessor(), ctx.validBiome())
                .map(site -> new GenerationStub(site.center().above(), builder -> builder.addPiece(new OutpostPiece(site))));
    }

    @Override
    public StructureType<?> type() {
        return ModWorldgen.OUTPOST.get();
    }
}
