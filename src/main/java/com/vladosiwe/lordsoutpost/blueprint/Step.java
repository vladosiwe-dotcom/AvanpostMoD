package com.vladosiwe.lordsoutpost.blueprint;

import com.vladosiwe.lordsoutpost.outpost.Resource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Один шаг стройки: поставить (или убрать) блок.
 *
 * @param pos   позиция относительно центра форта (уже с учётом поворота)
 * @param chain ставится вместе с предыдущим шагом
 * @param cost  ресурс, который расходуется на шаг (null — бесплатно)
 */
public record Step(BlockPos pos, BlockState state, boolean chain, @Nullable Resource cost) {}
