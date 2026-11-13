package com.vladosiwe.lordsoutpost.blueprint;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Готовый чертёж одного тира: какой блок должен стоять в каждой точке (относительно центра форта). */
public final class Design {
    public final int tier;
    public final int radius;
    public final Map<BlockPos, Canvas.Cell> cells;
    public final EnumMap<Marker, List<BlockPos>> markers;

    public Design(int tier, int radius, Map<BlockPos, Canvas.Cell> cells, EnumMap<Marker, List<BlockPos>> markers) {
        this.tier = tier;
        this.radius = radius;
        this.cells = cells;
        this.markers = markers;
    }

    public Design rotated(Rotation rot) {
        if (rot == Rotation.NONE) return this;
        Map<BlockPos, Canvas.Cell> out = new HashMap<>(cells.size() * 2);
        for (var e : cells.entrySet()) {
            Canvas.Cell c = e.getValue();
            out.put(e.getKey().rotate(rot), new Canvas.Cell(c.state().rotate(rot), c.stage(), c.group(), c.seq(), c.chain()));
        }
        EnumMap<Marker, List<BlockPos>> m = new EnumMap<>(Marker.class);
        for (var e : markers.entrySet()) {
            List<BlockPos> list = new ArrayList<>(e.getValue().size());
            for (BlockPos p : e.getValue()) list.add(p.rotate(rot));
            m.put(e.getKey(), list);
        }
        return new Design(tier, radius, out, m);
    }

    public List<BlockPos> markers(Marker marker) {
        return markers.getOrDefault(marker, List.of());
    }
}
