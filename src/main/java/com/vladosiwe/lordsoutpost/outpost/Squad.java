package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Отряд бойцов с общим приказом. У аванпоста три отряда: Стража, Дозор и Свита. */
public final class Squad {
    public enum Order {
        /** Стоять на постах стен и ворот. */
        POSTS,
        /** Ходить по кругу через путевые точки. */
        PATROL,
        /** Держать позицию. */
        HOLD,
        /** Сопровождать Лорда в походе. */
        RETINUE;

        public static final Order[] VALUES = values();

        public Component label() {
            return Component.translatable("lordsoutpost.order." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static final int GUARD = 0, WATCH = 1, RETINUE = 2, COUNT = 3;
    public static final int MAX_WAYPOINTS = 12;

    public final int id;
    public Order order;
    /** Путевые точки патруля (мировые координаты; высота уточняется на месте). Пусто — обход по умолчанию. */
    public final List<BlockPos> route = new ArrayList<>();
    @Nullable public BlockPos hold;

    public Squad(int id, Order order) {
        this.id = id;
        this.order = order;
    }

    public Component label() {
        return Component.translatable("lordsoutpost.squad." + id);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putByte("order", (byte) order.ordinal());
        long[] pts = new long[route.size()];
        for (int i = 0; i < pts.length; i++) pts[i] = route.get(i).asLong();
        t.putLongArray("route", pts);
        if (hold != null) t.putLong("hold", hold.asLong());
        return t;
    }

    public void load(CompoundTag t) {
        order = Order.VALUES[Math.max(0, Math.min(Order.VALUES.length - 1, t.getByte("order")))];
        route.clear();
        for (long l : t.getLongArray("route")) if (route.size() < MAX_WAYPOINTS) route.add(BlockPos.of(l));
        hold = t.contains("hold") ? BlockPos.of(t.getLong("hold")) : null;
    }
}
