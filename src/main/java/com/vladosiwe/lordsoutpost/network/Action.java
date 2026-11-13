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
    CLOSE;

    public static final Action[] VALUES = values();
}
