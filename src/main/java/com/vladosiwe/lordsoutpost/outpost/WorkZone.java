package com.vladosiwe.lordsoutpost.outpost;

import com.vladosiwe.lordsoutpost.blueprint.Marker;
import org.jetbrains.annotations.Nullable;

/**
 * Рабочая зона профессии. По умолчанию зона стоит там, где её задал чертёж форта (метка), но Лорд может перенести
 * переносимые зоны в другое место во дворе: профессия узнаёт об этом через {@link WorkZones#version()} и якорь зоны.
 */
public enum WorkZone {
    MINE("mine", Marker.MINE, Role.MINER, false),
    WOODYARD("woodyard", Marker.WOODYARD, Role.LUMBERJACK, true),
    PEN("pen", Marker.PEN, Role.SKOTOVOD, false),
    FARM("farm", Marker.FARM, Role.FARMER, true),
    SMITHY("smithy", Marker.SMITHY, Role.SMITH, false),
    STOREHOUSE("storehouse", Marker.STORE_INSIDE, null, false),
    MAGE_TOWER("mage_tower", Marker.MAGE, Role.MAGE, false),
    /** Лавка торговца: по умолчанию у площадки караванов, Лорд может перенести её в любое место двора. */
    SHOP("shop", Marker.CARAVAN, Role.CARAVANEER, true);

    public static final WorkZone[] VALUES = values();

    public final String id;
    public final Marker marker;
    @Nullable public final Role role;
    /**
     * Зону можно перенести без перестройки: поле фермер вспашет на новом месте сам, дровяной двор — это лишь точка.
     * Загон, шахтный ствол и кузница — постройки чертежа; их перенос требует работы строителя (следующий этап).
     */
    public final boolean relocatable;

    WorkZone(String id, Marker marker, @Nullable Role role, boolean relocatable) {
        this.id = id;
        this.marker = marker;
        this.role = role;
        this.relocatable = relocatable;
    }

    @Nullable
    public static WorkZone byId(String id) {
        for (WorkZone z : VALUES) if (z.id.equals(id)) return z;
        return null;
    }
}
