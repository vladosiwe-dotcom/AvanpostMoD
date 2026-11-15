package com.vladosiwe.lordsoutpost.blueprint;

/**
 * Планировка форта («заготовка»). Определяет форму периметра и расстановку зданий.
 * Канонически ворота смотрят на юг (+Z); при генерации форт поворачивается.
 */
public enum Layout {
    /** Квадратный форт: донжон у северной стены, службы вдоль восточной и западной. */
    SQUARE("square"),
    /** Восьмиугольная крепость: донжон в центре, службы кольцом вокруг. */
    OCTAGON("octagon");

    public static final Layout[] VALUES = values();
    /** Половина стороны периметра по тирам (индекс = тир). */
    public static final int[] RADIUS = {0, 13, 18, 23};
    /** На сколько башни и подступы выходят за периметр. */
    public static final int MARGIN = 4;

    public final String id;

    Layout(String id) {
        this.id = id;
    }

    public static Layout byId(String id) {
        for (Layout l : VALUES) if (l.id.equals(id)) return l;
        return SQUARE;
    }

    /** Точка внутри периметра радиуса r (включая саму стену). */
    public boolean inside(int x, int z, int r) {
        int ax = Math.abs(x), az = Math.abs(z);
        if (ax > r || az > r) return false;
        return this == SQUARE || ax + az <= chamfer(r);
    }

    /** Клетка стены толщиной t: внутри r, но не внутри r - t. */
    public boolean ring(int x, int z, int r, int t) {
        return inside(x, z, r) && !inside(x, z, r - t);
    }

    public static int chamfer(int r) {
        return r + r / 2;
    }

    /** Вершины контура (по часовой стрелке), для карты и пунктира расширения. */
    public int[][] outline(int r) {
        if (this == SQUARE) return new int[][]{{-r, -r}, {r, -r}, {r, r}, {-r, r}};
        int c = chamfer(r) - r;
        return new int[][]{{-c, -r}, {c, -r}, {r, -c}, {r, c}, {c, r}, {-c, r}, {-r, c}, {-r, -c}};
    }

    /** Центры четырёх угловых башен. */
    public int[][] towers(int r) {
        if (this == SQUARE) return new int[][]{{-r, -r}, {r, -r}, {r, r}, {-r, r}};
        int l = chamfer(r), t = l / 2;
        return new int[][]{{-t, -(l - t)}, {t, -(l - t)}, {t, l - t}, {-t, l - t}};
    }
}
