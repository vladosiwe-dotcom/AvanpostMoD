package com.vladosiwe.lordsoutpost.outpost;

public enum OutpostState {
    /** Занят мародёрами, Капитан жив. */
    WILD,
    /** Капитан убит, форт ждёт Штандарта Лорда. */
    CONQUERED,
    /** У форта есть Лорд. */
    OWNED;

    public static final OutpostState[] VALUES = values();
}
