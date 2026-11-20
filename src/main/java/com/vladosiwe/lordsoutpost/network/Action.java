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
    TOGGLE_AUTOBUILD;

    public static final Action[] VALUES = values();
}
