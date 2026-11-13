package com.vladosiwe.lordsoutpost.registry;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import com.vladosiwe.lordsoutpost.world.OutpostPiece;
import com.vladosiwe.lordsoutpost.world.OutpostStructure;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModWorldgen {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES = DeferredRegister.create(Registries.STRUCTURE_TYPE, LordsOutpost.MODID);
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES = DeferredRegister.create(Registries.STRUCTURE_PIECE, LordsOutpost.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<OutpostStructure>> OUTPOST =
            STRUCTURE_TYPES.register("outpost", () -> () -> OutpostStructure.CODEC);
    public static final DeferredHolder<StructurePieceType, StructurePieceType> OUTPOST_PIECE =
            PIECE_TYPES.register("outpost", () -> (StructurePieceType.ContextlessType) OutpostPiece::new);

    private ModWorldgen() {}
}
