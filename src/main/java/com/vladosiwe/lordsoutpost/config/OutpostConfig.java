package com.vladosiwe.lordsoutpost.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Серверные настройки баланса. Файл: serverconfig/lordsoutpost-server.toml в папке мира. */
public final class OutpostConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue RADAR_MIN = B
            .comment("Минимальный радиус (в блоках), на котором неразведанный аванпост появляется на карте знаком вопроса.")
            .defineInRange("discovery.radarMin", 1200, 200, 6000);
    public static final ModConfigSpec.IntValue RADAR_MAX = B
            .comment("Максимальный радиус появления знака вопроса. Для каждого аванпоста выбирается своё значение между минимумом и максимумом.")
            .defineInRange("discovery.radarMax", 1500, 200, 6000);
    public static final ModConfigSpec.IntValue DISCOVERY_RANGE = B
            .comment("На каком расстоянии аванпост считается найденным (баннер и иконка форта на карте).")
            .defineInRange("discovery.range", 110, 32, 400);

    public static final ModConfigSpec.DoubleValue BUILD_SPEED = B
            .comment("Множитель скорости строительства.")
            .defineInRange("economy.buildSpeed", 1.0, 0.1, 20.0);
    public static final ModConfigSpec.DoubleValue PRODUCTION = B
            .comment("Множитель добычи ресурсов жителями.")
            .defineInRange("economy.production", 1.0, 0.1, 20.0);
    public static final ModConfigSpec.DoubleValue TRIBUTE = B
            .comment("Множитель дани Лорду.")
            .defineInRange("economy.tribute", 1.0, 0.0, 20.0);
    public static final ModConfigSpec.DoubleValue PLAYER_HELP_BOOST = B
            .comment("Во сколько раз быстрее идёт работа после того, как игрок закрыл задание Доски.")
            .defineInRange("economy.playerHelpBoost", 2.0, 1.0, 10.0);
    public static final ModConfigSpec.IntValue BOOST_MINUTES = B
            .comment("Сколько минут действует ускорение от выполненного задания.")
            .defineInRange("economy.boostMinutes", 20, 1, 600);

    public static final ModConfigSpec.BooleanValue RAIDS = B
            .comment("Набеги на аванпосты игроков.")
            .define("threats.raids", true);
    public static final ModConfigSpec.DoubleValue RAID_INTERVAL_DAYS = B
            .comment("Средний промежуток между набегами в игровых днях.")
            .defineInRange("threats.raidIntervalDays", 4.0, 0.5, 100.0);
    public static final ModConfigSpec.DoubleValue RECLAIM_DAYS = B
            .comment("Через сколько игровых дней мародёры вернутся в захваченный, но не занятый форт.")
            .defineInRange("threats.reclaimDays", 5.0, 0.5, 1000.0);
    public static final ModConfigSpec.DoubleValue CAPTAIN_HEALTH = B
            .comment("Множитель здоровья Капитана.")
            .defineInRange("threats.captainHealth", 1.0, 0.1, 20.0);

    public static final ModConfigSpec.DoubleValue ROAD_SPEED = B
            .comment("Множитель скорости на дорогах (тропинка, гравий, булыжник и блоки из тега lordsoutpost:roads).")
            .defineInRange("roads.speed", 1.35, 1.0, 3.0);
    public static final ModConfigSpec.BooleanValue ROADS_FOR_PLAYERS = B
            .comment("Ускорять на дорогах игроков, а не только караваны и жителей.")
            .define("roads.players", true);

    public static final ModConfigSpec.BooleanValue LORD_PREFIX = B
            .comment("Показывать титул «Лорд» перед именем игрока в чате и над головой.")
            .define("title.prefix", true);
    public static final ModConfigSpec.BooleanValue RETROFIT = B
            .comment("Достраивать аванпосты в чанках, созданных до установки мода (может затронуть постройки игроков).")
            .define("worldgen.retrofitOldChunks", false);

    public static final ModConfigSpec SPEC = B.build();

    private OutpostConfig() {}

    /** Значение настройки или запасное, если конфиг ещё не загружен (тесты, ранний запуск). */
    public static double get(ModConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static int get(ModConfigSpec.IntValue v, int fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    public static boolean get(ModConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return v.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
