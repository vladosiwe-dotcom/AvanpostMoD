package com.vladosiwe.lordsoutpost.compat;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;

/**
 * Мягкая связь с Solo Status System. Жёсткой зависимости нет: если мод установлен, через отражение
 * вызывается его открытый класс com.vladosiwe.solostatus.api.SoloStatusApi. Так титул «Лорд» и подвиги
 * при штурме попадают в окно Системы (счётчики, достижения, опыт охотника).
 */
public final class SoloStatusCompat {
    public static final String MODID = "solostatus";
    private static boolean resolved;
    private static Method addCounter;
    private static Method setCounter;
    private static Method grantXp;

    private SoloStatusCompat() {}

    private static boolean ready() {
        if (!resolved) {
            resolved = true;
            if (ModList.get() == null || !ModList.get().isLoaded(MODID)) return false;
            try {
                Class<?> api = Class.forName("com.vladosiwe.solostatus.api.SoloStatusApi");
                addCounter = api.getMethod("addCounter", ServerPlayer.class, String.class, long.class);
                setCounter = api.getMethod("setCounter", ServerPlayer.class, String.class, long.class);
                grantXp = api.getMethod("grantXp", ServerPlayer.class, long.class);
                LordsOutpost.LOGGER.info("Solo Status System: интеграция включена (титул Лорда, счётчики штурмов)");
            } catch (ReflectiveOperationException e) {
                LordsOutpost.LOGGER.warn("Solo Status System установлен, но в нём нет SoloStatusApi — обновите мод. Интеграция выключена.");
                addCounter = null;
            }
        }
        return addCounter != null;
    }

    private static void call(Method m, Object... args) {
        try {
            m.invoke(null, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LordsOutpost.LOGGER.warn("Solo Status System: вызов {} не удался: {}", m.getName(), e.toString());
        }
    }

    public static void onMarauderKilled(ServerPlayer player, boolean captain) {
        if (!ready()) return;
        call(addCounter, player, captain ? "outpost_captains" : "outpost_marauders", 1L);
    }

    public static void onOutpostConquered(ServerPlayer player, int skulls) {
        if (!ready()) return;
        call(addCounter, player, "outposts_conquered", 1L);
        if (skulls >= 5) call(addCounter, player, "great_fortresses", 1L);
        call(grantXp, player, 400L * skulls * skulls);
    }

    public static void onBecameLord(ServerPlayer player) {
        if (!ready()) return;
        call(setCounter, player, "outpost_lord", 1L);
        call(addCounter, player, "outposts_founded", 1L);
    }

    public static void onQuestDone(ServerPlayer player, int reward) {
        if (!ready()) return;
        call(addCounter, player, "outpost_quests", 1L);
        call(grantXp, player, 20L * reward);
    }

    /** Достижения форта, которые видит Система: тир владения. */
    public static void onTier(ServerPlayer player, int tier) {
        if (!ready()) return;
        call(setCounter, player, "outpost_tier", (long) tier);
    }
}
