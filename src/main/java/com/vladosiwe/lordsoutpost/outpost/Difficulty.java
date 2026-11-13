package com.vladosiwe.lordsoutpost.outpost;

import net.minecraft.network.chat.Component;

/**
 * Сложность аванпоста в «черепках». Задаёт, насколько он отстроен при генерации,
 * силу гарнизона и Капитана.
 *
 * @param tier     готовый тир при генерации
 * @param progress доля плана следующего тира, уже построенная при генерации
 */
public enum Difficulty {
    FRONTIER_POST(1, "frontier_post", 1, 0.0f, 4),
    WATCH_FORT(2, "watch_fort", 1, 0.5f, 6),
    REDOUBT(3, "redoubt", 2, 0.0f, 9),
    MARAUDER_CITADEL(4, "marauder_citadel", 2, 0.6f, 12),
    GREAT_FORTRESS(5, "great_fortress", 2, 0.92f, 16);

    public static final Difficulty[] VALUES = values();

    public final int skulls;
    public final String id;
    public final int tier;
    public final float progress;
    /** Бойцов в гарнизоне, не считая Капитана. */
    public final int garrison;

    Difficulty(int skulls, String id, int tier, float progress, int garrison) {
        this.skulls = skulls;
        this.id = id;
        this.tier = tier;
        this.progress = progress;
        this.garrison = garrison;
    }

    public static Difficulty bySkulls(int skulls) {
        return VALUES[Math.max(1, Math.min(5, skulls)) - 1];
    }

    public Component label() {
        return Component.translatable("lordsoutpost.difficulty." + id);
    }
}
