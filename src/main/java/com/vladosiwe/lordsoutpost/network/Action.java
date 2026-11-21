package com.vladosiwe.lordsoutpost.network;

/** Действия Лорда. Порядок — часть сетевого протокола. */
public enum Action {
    RENAME,
    SET_LAW,
    SET_ACCESS,
    REMOVE_ACCESS,
    SET_DEFAULT_ACCESS,
    TOGGLE,
    SQUAD_ORDER,
    SQUAD_ROUTE,
    SQUAD_HOLD,
    ASSIGN,
    PACT,
    BREAK_PACT,
    CARAVAN,
    DELIVER,
    ABANDON,
    CLOSE,
    /** Заказ постройки перед Лордом: {type}. */
    ORDER_PROJECT,
    /** Отмена заказа: {id}. */
    CANCEL_PROJECT,
    /** Перенос рабочей зоны к Лорду или назад по чертежу: {zone, here}. */
    MOVE_ZONE,
    /** Накопленную дань — посылкой в почтомат. */
    MAIL_TRIBUTE,
    /** Построить рекомендованное планировщиком: {type}; место выбирается само. */
    ORDER_RECOMMENDED,
    /** Включить или выключить авто-застройку. */
    TOGGLE_AUTOBUILD,
    /** RTS: заказ в точке {type, x, z, rot}. */
    ORDER_PROJECT_AT,
    /** RTS: проверить место {type, x, z, rot, seq} — ответ пакетом PlacementCheck. */
    CHECK_PROJECT,
    /** Приоритет заказа: {id, up}. */
    MOVE_PROJECT,
    UPGRADE_PROJECT,
    DEMOLISH_PROJECT,
    DEMOLISH_AT,
    SET_ROLE,
    CHOOSE_LAYOUT;

    public static final Action[] VALUES = values();
}
