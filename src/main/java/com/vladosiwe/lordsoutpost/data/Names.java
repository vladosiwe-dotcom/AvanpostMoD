package com.vladosiwe.lordsoutpost.data;

import net.minecraft.util.RandomSource;

/** Имена жителей: слоговый генератор, одинаково читается на любом языке клиента. */
public final class Names {
    private static final String[] START = {"Bor", "Kal", "Dra", "Ed", "Fen", "Gar", "Hal", "Ivo", "Jor", "Kes", "Lud", "Mar", "Nor", "Os", "Per", "Rad", "Sig", "Tor", "Ul", "Vel", "Wen", "Yar", "Zor", "Al", "Bran", "Cor", "Dag", "Erl", "Gun", "Har"};
    private static final String[] MID = {"a", "e", "i", "o", "u", "ar", "en", "il", "or", "un", "an", "el"};
    private static final String[] END = {"n", "r", "d", "k", "s", "mir", "dan", "gar", "rik", "win", "bert", "mund", "ric", "ld", "th", "vin", "sa", "ra", "na", "da"};

    private Names() {}

    public static String random(RandomSource rnd) {
        String name = START[rnd.nextInt(START.length)];
        if (rnd.nextInt(3) != 0) name += MID[rnd.nextInt(MID.length)];
        return name + END[rnd.nextInt(END.length)];
    }
}
