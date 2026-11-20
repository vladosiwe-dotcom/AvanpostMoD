#!/usr/bin/env python3
"""Рисует оригинальные скины бойцов в раскладке игрока (64x64) для Epic Fight.

Epic Fight рисует бойца на своём скелете игрока: текстура разбойника туда не ложится. Эти скины — свои,
нарисованы процедурно: лицо, стёганка, пояс, штаны, сапоги; у стражи — синяя накидка, у мародёров — тёмная с красным.
"""
import random
from PIL import Image

OUT = 'src/main/resources/assets/lordsoutpost/textures/entity/'


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c) + (255,)


def fill(img, x0, y0, x1, y1, color, noise=0.08, rnd=None):
    for x in range(x0, x1):
        for y in range(y0, y1):
            k = 1 + (rnd.uniform(-noise, noise) if rnd else 0)
            img.putpixel((x, y), shade(color, k))


def box(img, u, v, w, h, d, faces, rnd):
    """Развёртка куба Minecraft: faces = dict(top, bottom, right, front, left, back) -> цвет или функция(img,x0,y0,x1,y1)."""
    rects = {
        'top': (u + d, v, u + d + w, v + d),
        'bottom': (u + d + w, v, u + d + 2 * w, v + d),
        'right': (u, v + d, u + d, v + d + h),
        'front': (u + d, v + d, u + d + w, v + d + h),
        'left': (u + d + w, v + d, u + 2 * d + w, v + d + h),
        'back': (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }
    for name, (x0, y0, x1, y1) in rects.items():
        f = faces.get(name, faces.get('side'))
        if callable(f):
            f(img, x0, y0, x1, y1)
        elif f is not None:
            fill(img, x0, y0, x1, y1, f, rnd=rnd)


def skin(name, tabard, trim, seed, hostile):
    rnd = random.Random(seed)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    skin_c = (190, 140, 105) if not hostile else (160, 115, 85)
    hair = (70, 48, 30) if not hostile else (40, 32, 28)
    gambeson = (120, 100, 75)
    pants = (70, 60, 50) if not hostile else (55, 45, 40)
    boots = (60, 40, 28)
    belt = (45, 30, 20)

    def face(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, skin_c, rnd=rnd)
        for x in range(x0, x1):
            img.putpixel((x, y0), shade(hair, 1))
            img.putpixel((x, y0 + 1), shade(hair, 0.95))
        # Брови, глаза, нос, борода.
        for dx in (1, 2, 5, 6):
            img.putpixel((x0 + dx, y0 + 3), shade(hair, 0.8))
        img.putpixel((x0 + 1, y0 + 4), (240, 240, 240, 255))
        img.putpixel((x0 + 2, y0 + 4), (40, 60, 90, 255) if not hostile else (90, 30, 25, 255))
        img.putpixel((x0 + 6, y0 + 4), (240, 240, 240, 255))
        img.putpixel((x0 + 5, y0 + 4), (40, 60, 90, 255) if not hostile else (90, 30, 25, 255))
        img.putpixel((x0 + 3, y0 + 5), shade(skin_c, 0.85))
        img.putpixel((x0 + 4, y0 + 5), shade(skin_c, 0.85))
        for x in range(x0 + 1, x1 - 1):
            img.putpixel((x, y0 + 6), shade(hair, 0.9))
            img.putpixel((x, y0 + 7), shade(hair, 0.85))
        img.putpixel((x0 + 3, y0 + 6), shade((150, 70, 60), 1))
        img.putpixel((x0 + 4, y0 + 6), shade((150, 70, 60), 1))

    def head_side(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, skin_c, rnd=rnd)
        for x in range(x0, x1):
            for y in range(y0, y0 + 3):
                img.putpixel((x, y), shade(hair, rnd.uniform(0.9, 1.05)))
        for y in range(y0 + 6, y1):
            img.putpixel((x0 + (0 if x1 - x0 else 0), y), shade(hair, 0.85))

    def hair_all(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, hair, noise=0.1, rnd=rnd)

    box(img, 0, 0, 8, 8, 8, {'top': hair_all, 'bottom': skin_c, 'right': head_side, 'left': head_side, 'front': face, 'back': hair_all}, rnd)

    def tunic_front(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, gambeson, rnd=rnd)
        # Накидка-табард с каймой.
        for x in range(x0 + 1, x1 - 1):
            for y in range(y0, y1):
                img.putpixel((x, y), shade(tabard, rnd.uniform(0.92, 1.06)))
        for y in range(y0, y1):
            img.putpixel((x0 + 1, y), shade(trim, 1))
            img.putpixel((x1 - 2, y), shade(trim, 1))
        # Герб: простой крест.
        cx = (x0 + x1) // 2
        for y in range(y0 + 2, y0 + 7):
            img.putpixel((cx - 1, y), shade(trim, 1.1))
            img.putpixel((cx, y), shade(trim, 1.1))
        for x in range(cx - 3, cx + 2):
            img.putpixel((x, y0 + 4), shade(trim, 1.1))
        for x in range(x0, x1):
            img.putpixel((x, y0 + 8), shade(belt, 1))
            img.putpixel((x, y0 + 9), shade(belt, 0.9))
        img.putpixel((cx - 1, y0 + 8), (200, 170, 80, 255))

    def tunic_side(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, gambeson, rnd=rnd)
        for x in range(x0, x1):
            img.putpixel((x, y0 + 8), shade(belt, 1))
            img.putpixel((x, y0 + 9), shade(belt, 0.9))

    box(img, 16, 16, 8, 12, 4, {'top': gambeson, 'bottom': gambeson, 'right': tunic_side, 'left': tunic_side,
                                'front': tunic_front, 'back': tunic_front}, rnd)

    def sleeve(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, gambeson, rnd=rnd)
        for x in range(x0, x1):
            for y in range(y1 - 3, y1):
                img.putpixel((x, y), shade(skin_c, rnd.uniform(0.95, 1.03)))
            img.putpixel((x, y1 - 4), shade((90, 70, 50), 1))

    arm = {'top': gambeson, 'bottom': skin_c, 'side': sleeve}
    box(img, 40, 16, 4, 12, 4, arm, rnd)
    box(img, 32, 48, 4, 12, 4, arm, rnd)

    def leg(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, pants, rnd=rnd)
        for x in range(x0, x1):
            for y in range(y1 - 4, y1):
                img.putpixel((x, y), shade(boots, rnd.uniform(0.9, 1.05)))
            img.putpixel((x, y1 - 5), shade(boots, 0.75))

    lg = {'top': pants, 'bottom': boots, 'side': leg}
    box(img, 0, 16, 4, 12, 4, lg, rnd)
    box(img, 16, 48, 4, 12, 4, lg, rnd)
    img.save(OUT + name + '.png')


def bandit(name, seed):
    """Разбойник: серый капюшон с тканевой маской, кожаная куртка, красная перевязь через грудь, обмотки и сапоги."""
    rnd = random.Random(seed)
    img = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    hood = (88, 84, 80)
    hood_dark = (58, 55, 52)
    skin_c = (150, 108, 80)
    mask = (110, 30, 28)
    leather = (105, 70, 42)
    shirt = (52, 46, 42)
    sash = (150, 36, 30)
    belt = (40, 27, 18)
    pants = (78, 62, 46)
    wraps = (130, 118, 96)
    boots = (45, 32, 24)

    def hood_fill(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, hood, noise=0.1, rnd=rnd)

    def face(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, hood, noise=0.1, rnd=rnd)
        # Проём капюшона: тень, глаза, маска на нижней половине лица.
        for x in range(x0 + 1, x1 - 1):
            for y in range(y0 + 2, y1):
                img.putpixel((x, y), shade(skin_c, rnd.uniform(0.9, 1.0)))
        for x in range(x0 + 1, x1 - 1):
            img.putpixel((x, y0 + 2), shade(hood_dark, 0.8))
            img.putpixel((x, y0 + 3), shade(skin_c, 0.7))
        for dx in (2, 5):
            img.putpixel((x0 + dx, y0 + 4), (235, 230, 220, 255))
        img.putpixel((x0 + 3, y0 + 4), (60, 40, 30, 255))
        img.putpixel((x0 + 4, y0 + 4), (60, 40, 30, 255))
        img.putpixel((x0 + 1, y0 + 4), shade(skin_c, 0.75))
        img.putpixel((x0 + 6, y0 + 4), shade(skin_c, 0.75))
        for x in range(x0 + 1, x1 - 1):
            for y in range(y0 + 5, y1):
                img.putpixel((x, y), shade(mask, rnd.uniform(0.85, 1.05)))
        for x in range(x0 + 1, x1 - 1):
            img.putpixel((x, y0 + 5), shade(mask, 1.15))

    box(img, 0, 0, 8, 8, 8, {'top': hood_fill, 'bottom': hood_dark, 'side': hood_fill, 'front': face}, rnd)

    # Внешний слой головы: края капюшона чуть шире головы (на лице — только рамка).
    def hood_frame(img, x0, y0, x1, y1):
        for x in range(x0, x1):
            img.putpixel((x, y0), shade(hood, rnd.uniform(0.9, 1.05)))
        for y in range(y0, y1):
            img.putpixel((x0, y), shade(hood_dark, rnd.uniform(0.9, 1.05)))
            img.putpixel((x1 - 1, y), shade(hood_dark, rnd.uniform(0.9, 1.05)))

    def hood_back(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, hood_dark, noise=0.12, rnd=rnd)

    box(img, 32, 0, 8, 8, 8, {'top': hood_back, 'side': hood_back, 'front': hood_frame, 'bottom': None}, rnd)

    def torso_front(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, leather, noise=0.1, rnd=rnd)
        # Рубаха в разрезе куртки.
        for y in range(y0, y0 + 7):
            for x in (x0 + 3, x0 + 4):
                img.putpixel((x, y), shade(shirt, rnd.uniform(0.9, 1.05)))
        # Перевязь с левого плеча на правый бок.
        for i in range(10):
            x = x0 + 7 - (i * 7) // 9
            for dx in (0, -1):
                if x0 <= x + dx < x1:
                    img.putpixel((x + dx, y0 + i), shade(sash, rnd.uniform(0.9, 1.08)))
        for x in range(x0, x1):
            img.putpixel((x, y0 + 8), shade(belt, 1))
            img.putpixel((x, y0 + 9), shade(belt, 0.85))
        img.putpixel((x0 + 3, y0 + 8), (190, 160, 80, 255))
        img.putpixel((x0 + 4, y0 + 8), (190, 160, 80, 255))
        # Подол куртки и кошель на поясе.
        for x in range(x0, x1):
            img.putpixel((x, y1 - 1), shade(leather, 0.75))
        img.putpixel((x0 + 6, y0 + 10), shade(sash, 0.8))
        img.putpixel((x0 + 6, y0 + 11), shade(sash, 0.7))

    def torso_back(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, leather, noise=0.1, rnd=rnd)
        for y in range(y0, y0 + 3):
            for x in range(x0 + 1, x1 - 1):
                img.putpixel((x, y), shade(hood_dark, rnd.uniform(0.9, 1.05)))
        for i in range(10):
            x = x0 + (i * 7) // 9
            if x0 <= x < x1:
                img.putpixel((x, y0 + i), shade(sash, 0.95))
        for x in range(x0, x1):
            img.putpixel((x, y0 + 8), shade(belt, 1))
            img.putpixel((x, y0 + 9), shade(belt, 0.85))

    def torso_side(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, leather, noise=0.1, rnd=rnd)
        for x in range(x0, x1):
            img.putpixel((x, y0 + 8), shade(belt, 1))
            img.putpixel((x, y0 + 9), shade(belt, 0.85))

    box(img, 16, 16, 8, 12, 4, {'top': leather, 'bottom': leather, 'side': torso_side, 'front': torso_front, 'back': torso_back}, rnd)

    def sleeve(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, shirt, noise=0.1, rnd=rnd)
        for x in range(x0, x1):
            for y in range(y0, y0 + 3):
                img.putpixel((x, y), shade(leather, rnd.uniform(0.9, 1.05)))
            for y in range(y0 + 6, y1 - 2):
                img.putpixel((x, y), shade(leather, rnd.uniform(0.75, 0.9)))
            for y in range(y1 - 2, y1):
                img.putpixel((x, y), shade(boots, rnd.uniform(0.95, 1.1)))

    arm = {'top': leather, 'bottom': boots, 'side': sleeve}
    box(img, 40, 16, 4, 12, 4, arm, rnd)
    box(img, 32, 48, 4, 12, 4, arm, rnd)

    def leg(img, x0, y0, x1, y1):
        fill(img, x0, y0, x1, y1, pants, noise=0.1, rnd=rnd)
        for x in range(x0, x1):
            for y in range(y1 - 7, y1 - 3):
                img.putpixel((x, y), shade(wraps, 0.85 if (y + x) % 2 else 1.0))
            for y in range(y1 - 3, y1):
                img.putpixel((x, y), shade(boots, rnd.uniform(0.9, 1.05)))

    lg = {'top': pants, 'bottom': boots, 'side': leg}
    box(img, 0, 16, 4, 12, 4, lg, rnd)
    box(img, 16, 48, 4, 12, 4, lg, rnd)
    img.save(OUT + name + '.png')


if __name__ == '__main__':
    skin('guard', (40, 70, 140), (215, 185, 90), 7, False)
    skin('guard_captain', (120, 30, 35), (220, 200, 120), 11, False)
    bandit('marauder', 13)
    print('ok')
