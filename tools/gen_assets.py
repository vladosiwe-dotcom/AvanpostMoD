"""Создаёт текстуры, модели и файлы данных мода. Все текстуры нарисованы этим скриптом, чужих изображений нет.

Запуск: python3 tools/gen_assets.py
"""
import json, os, shutil
from PIL import Image

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources')
A = os.path.join(ROOT, 'assets', 'lordsoutpost')
D = os.path.join(ROOT, 'data')
MOD = 'lordsoutpost'


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write('\n')


def save(img, *parts):
    path = os.path.join(A, 'textures', *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


def rgb(h, a=255):
    return ((h >> 16) & 255, (h >> 8) & 255, h & 255, a)


def noise(x, y, seed=0):
    n = (x * 374761393 + y * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 255) / 255.0


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3],)


# ============================== Текстуры ==============================

def gate(base, light, dark, rivet):
    """Кованая решётка: вертикальные прутья, три поперечины, заклёпки, острия внизу."""
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = img.load()
    for bx in (1, 5, 9, 13):
        for y in range(16):
            px[bx, y] = shade(rgb(base), 0.9 + 0.2 * noise(bx, y))
            px[bx + 1, y] = shade(rgb(dark), 0.9 + 0.2 * noise(bx + 1, y))
        px[bx, 0] = rgb(light)
    for by in (2, 8, 13):
        for x in range(16):
            px[x, by] = shade(rgb(light), 0.9 + 0.2 * noise(x, by, 3))
            px[x, by + 1] = shade(rgb(dark), 0.85 + 0.2 * noise(x, by + 1, 3))
        for bx in (1, 5, 9, 13):
            px[bx, by] = rgb(rivet)
    return img


save(gate(0x4B5059, 0x7A818C, 0x2B2E34, 0xB8BEC8), 'block', 'outpost_gate.png')
save(gate(0x7A5B24, 0xC29A45, 0x4A3613, 0xF0D27A), 'block', 'outpost_gate_inner.png')


def wood(seed, base=0x7A5630):
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            f = 0.82 + 0.25 * noise(x // 2, y, seed) + (0.06 if y % 4 == 0 else 0)
            px[x, y] = shade(rgb(base), f)
        for x in range(16):
            if y % 4 == 3:
                px[x, y] = shade(px[x, y], 0.72)
    return img


def board():
    img = wood(7, 0x9A7446)
    px = img.load()
    for i in range(16):
        for t in (0, 15):
            px[i, t] = shade(rgb(0x5A3D1E), 0.9 + 0.2 * noise(i, t, 5))
            px[t, i] = shade(rgb(0x5A3D1E), 0.9 + 0.2 * noise(t, i, 5))
    # Листки с заданиями.
    for (x0, y0, w, h, col) in ((2, 2, 5, 6, 0xECE3C4), (9, 3, 5, 5, 0xE2D8B2), (3, 9, 4, 5, 0xE8DDBA), (8, 9, 6, 5, 0xF0E8CC)):
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                px[x, y] = shade(rgb(col), 0.94 + 0.08 * noise(x, y, 9))
        for y in range(y0 + 2, y0 + h - 1, 2):
            for x in range(x0 + 1, x0 + w - 1):
                px[x, y] = rgb(0x6E6450)
        px[x0 + w // 2, y0] = rgb(0xC0392B)
    return img


save(board(), 'block', 'quest_board.png')
save(wood(11, 0x6B4A2B), 'block', 'quest_board_back.png')
save(wood(3, 0x5C4024), 'block', 'standard_pole.png')


def cloth():
    """Полотнище штандарта: тёмно-красное поле, золотая кайма и корона."""
    img = Image.new('RGBA', (16, 16))
    px = img.load()
    for y in range(16):
        for x in range(16):
            px[x, y] = shade(rgb(0x9C1F24), 0.86 + 0.2 * noise(x, y, 21) - 0.01 * y)
    for i in range(16):
        px[i, 0] = rgb(0xE2B44C)
        px[0, i] = rgb(0xC29A45)
        px[15, i] = rgb(0xC29A45)
    crown = ["..#...#...#..", "..##.###.##..", "..#########..", "..#########..", "...#######..."]
    for j, row in enumerate(crown):
        for i, ch in enumerate(row):
            if ch == '#':
                px[2 + i, 4 + j] = rgb(0xF2D27A if j < 2 else 0xE2B44C)
    for x in range(4, 13):
        px[x, 10] = rgb(0xC29A45)
    # Раздвоенный нижний край.
    for x in range(16):
        d = abs(x - 7.5)
        cut = 16 - int(max(0, 3 - d * 0.6))
        for y in range(cut, 16):
            px[x, y] = (0, 0, 0, 0)
    return img


save(cloth(), 'block', 'standard_cloth.png')


def standard_icon():
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(1, 16):
        px[3, y] = rgb(0x6B4A2B)
        px[4, y] = rgb(0x4A3319)
    px[3, 0] = rgb(0xF2D27A)
    px[4, 0] = rgb(0xE2B44C)
    for y in range(2, 11):
        for x in range(5, 14):
            if y >= 9 and 8 <= x <= 10:
                continue
            px[x, y] = shade(rgb(0xA82329), 0.9 + 0.15 * noise(x, y, 4))
    for x in range(5, 14):
        px[x, 2] = rgb(0xE2B44C)
    for (x, y) in ((7, 4), (9, 4), (11, 4), (7, 5), (8, 5), (9, 5), (10, 5), (11, 5), (7, 6), (8, 6), (9, 6), (10, 6), (11, 6)):
        px[x, y] = rgb(0xF2D27A)
    return img


save(standard_icon(), 'item', 'lord_standard.png')

# ============================== Модели и состояния блоков ==============================

def tex(name):
    return f'{MOD}:block/{name}'


for name, texture in (('outpost_gate', 'outpost_gate'), ('outpost_gate_inner', 'outpost_gate_inner')):
    write(os.path.join(A, 'models', 'block', name + '.json'), {
        'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
        'textures': {'bars': tex(texture), 'particle': tex(texture)},
        'elements': [{'from': [0, 0, 7.5], 'to': [16, 16, 8.5], 'faces': {
            'north': {'uv': [0, 0, 16, 16], 'texture': '#bars'}, 'south': {'uv': [0, 0, 16, 16], 'texture': '#bars'},
            'up': {'uv': [0, 2, 16, 3], 'texture': '#bars'}, 'down': {'uv': [0, 13, 16, 14], 'texture': '#bars'},
            'east': {'uv': [1, 0, 2, 16], 'texture': '#bars'}, 'west': {'uv': [13, 0, 14, 16], 'texture': '#bars'}}}]})
write(os.path.join(A, 'blockstates', 'outpost_gate.json'), {'variants': {
    'axis=x,inner=false': {'model': f'{MOD}:block/outpost_gate'},
    'axis=z,inner=false': {'model': f'{MOD}:block/outpost_gate', 'y': 90},
    'axis=x,inner=true': {'model': f'{MOD}:block/outpost_gate_inner'},
    'axis=z,inner=true': {'model': f'{MOD}:block/outpost_gate_inner', 'y': 90}}})
write(os.path.join(A, 'models', 'item', 'outpost_gate.json'), {'parent': 'minecraft:item/generated', 'textures': {'layer0': tex('outpost_gate')}})

write(os.path.join(A, 'models', 'block', 'quest_board.json'), {
    'parent': 'minecraft:block/block',
    'textures': {'front': tex('quest_board'), 'back': tex('quest_board_back'), 'particle': tex('quest_board_back')},
    'elements': [
        {'from': [0, 3, 7], 'to': [16, 16, 9], 'faces': {
            'south': {'uv': [0, 0, 16, 13], 'texture': '#front'}, 'north': {'uv': [0, 0, 16, 13], 'texture': '#back'},
            'up': {'uv': [0, 0, 16, 2], 'texture': '#back'}, 'down': {'uv': [0, 0, 16, 2], 'texture': '#back'},
            'east': {'uv': [0, 0, 2, 13], 'texture': '#back'}, 'west': {'uv': [0, 0, 2, 13], 'texture': '#back'}}},
        {'from': [1, 0, 7], 'to': [3, 3, 9], 'faces': {d: {'uv': [0, 0, 2, 3], 'texture': '#back'} for d in ('north', 'south', 'east', 'west', 'down')}},
        {'from': [13, 0, 7], 'to': [15, 3, 9], 'faces': {d: {'uv': [0, 0, 2, 3], 'texture': '#back'} for d in ('north', 'south', 'east', 'west', 'down')}}],
    'display': {'gui': {'rotation': [20, 200, 0], 'scale': [0.75, 0.75, 0.75]}, 'fixed': {'rotation': [0, 180, 0], 'scale': [0.8, 0.8, 0.8]}}})
write(os.path.join(A, 'blockstates', 'quest_board.json'), {'variants': {
    'facing=south': {'model': f'{MOD}:block/quest_board'}, 'facing=west': {'model': f'{MOD}:block/quest_board', 'y': 90},
    'facing=north': {'model': f'{MOD}:block/quest_board', 'y': 180}, 'facing=east': {'model': f'{MOD}:block/quest_board', 'y': 270}}})
write(os.path.join(A, 'models', 'item', 'quest_board.json'), {'parent': f'{MOD}:block/quest_board'})

pole_faces = {d: {'uv': [7, 0, 9, 16], 'texture': '#pole'} for d in ('north', 'south', 'east', 'west')}
write(os.path.join(A, 'models', 'block', 'lord_standard.json'), {
    'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
    'textures': {'pole': tex('standard_pole'), 'cloth': tex('standard_cloth'), 'particle': tex('standard_pole')},
    'elements': [
        {'from': [5, 0, 5], 'to': [11, 1, 11], 'faces': {d: {'uv': [5, 5, 11, 11], 'texture': '#pole'} for d in ('north', 'south', 'east', 'west', 'up', 'down')}},
        {'from': [7, 1, 7], 'to': [9, 16, 9], 'faces': pole_faces},
        {'from': [7, 16, 7], 'to': [9, 32, 9], 'faces': dict(pole_faces, up={'uv': [7, 7, 9, 9], 'texture': '#pole'})},
        {'from': [1, 29, 7.5], 'to': [15, 30, 8.5], 'faces': {d: {'uv': [1, 3, 15, 4], 'texture': '#pole'} for d in ('north', 'south', 'east', 'west', 'up', 'down')}},
        {'from': [2, 13, 7.9], 'to': [14, 29, 8.1], 'faces': {
            'north': {'uv': [0, 0, 16, 16], 'texture': '#cloth'}, 'south': {'uv': [0, 0, 16, 16], 'texture': '#cloth'}}}]})
write(os.path.join(A, 'blockstates', 'lord_standard.json'), {'variants': {'': {'model': f'{MOD}:block/lord_standard'}}})
write(os.path.join(A, 'models', 'item', 'lord_standard.json'), {'parent': 'minecraft:item/generated', 'textures': {'layer0': f'{MOD}:item/lord_standard'}})

# ============================== Данные ==============================

DM = os.path.join(D, MOD)
write(os.path.join(DM, 'worldgen', 'structure', 'outpost.json'), {
    'type': f'{MOD}:outpost', 'biomes': f'#{MOD}:has_structure/outpost', 'step': 'surface_structures',
    'spawn_overrides': {}, 'terrain_adaptation': 'beard_box'})
# Сетка: регион 64 чанка (1024 блока), отступ 28 чанков — соседние форты не ближе ~450 блоков.
write(os.path.join(DM, 'worldgen', 'structure_set', 'outposts.json'), {
    'structures': [{'structure': f'{MOD}:outpost', 'weight': 1}],
    'placement': {'type': 'minecraft:random_spread', 'spacing': 64, 'separation': 28, 'salt': 1701215077}})
write(os.path.join(DM, 'tags', 'worldgen', 'biome', 'has_structure', 'outpost.json'), {'values': [
    '#minecraft:is_forest', '#minecraft:is_taiga', '#minecraft:is_savanna', '#minecraft:is_badlands', '#minecraft:is_jungle',
    'minecraft:plains', 'minecraft:sunflower_plains', 'minecraft:meadow', 'minecraft:desert', 'minecraft:snowy_plains',
    'minecraft:cherry_grove', 'minecraft:swamp', 'minecraft:windswept_hills', 'minecraft:stony_shore',
    {'id': '#c:is_plains', 'required': False}]})
write(os.path.join(DM, 'tags', 'block', 'roads.json'), {'values': [
    'minecraft:dirt_path', 'minecraft:gravel', 'minecraft:cobblestone', 'minecraft:mossy_cobblestone', 'minecraft:stone_bricks',
    'minecraft:mossy_stone_bricks', 'minecraft:cracked_stone_bricks', 'minecraft:cobblestone_slab', 'minecraft:stone_brick_slab',
    'minecraft:polished_andesite', 'minecraft:bricks', 'minecraft:mud_bricks', 'minecraft:packed_mud', 'minecraft:smooth_stone',
    'minecraft:sandstone', 'minecraft:smooth_sandstone', 'minecraft:cut_sandstone', 'minecraft:deepslate_bricks', 'minecraft:cobbled_deepslate']})
write(os.path.join(DM, 'tags', 'item', 'arcane.json'), {'values': [
    {'id': 'irons_spellbooks:arcane_essence', 'required': False}, {'id': 'irons_spellbooks:common_ink', 'required': False},
    {'id': 'irons_spellbooks:uncommon_ink', 'required': False}, {'id': 'irons_spellbooks:rare_ink', 'required': False}]})
write(os.path.join(D, 'minecraft', 'tags', 'block', 'mineable', 'pickaxe.json'), {'values': [f'{MOD}:outpost_gate']})
write(os.path.join(D, 'minecraft', 'tags', 'block', 'mineable', 'axe.json'), {'values': [f'{MOD}:lord_standard', f'{MOD}:quest_board']})

for block in ('outpost_gate', 'lord_standard', 'quest_board'):
    write(os.path.join(DM, 'loot_table', 'blocks', block + '.json'), {
        'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'{MOD}:{block}'}],
                                             'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})


def item(name, weight, lo=1, hi=1, extra=None):
    e = {'type': 'minecraft:item', 'name': name, 'weight': weight}
    fn = []
    if hi > 1 or lo > 1:
        fn.append({'function': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}})
    if extra:
        fn.extend(extra)
    if fn:
        e['functions'] = fn
    return e


ENCHANT = [{'function': 'minecraft:enchant_randomly'}]
for skulls in range(1, 6):
    supplies = [item('minecraft:bread', 10, 1, 4), item('minecraft:cooked_beef', 6, 1, 3), item('minecraft:arrow', 8, 4, 12),
                item('minecraft:coal', 8, 2, 6), item('minecraft:iron_ingot', 6, 1, 2 + skulls), item('minecraft:leather', 6, 1, 4),
                item('minecraft:emerald', 5, 1, 1 + skulls), item('minecraft:oak_log', 6, 4, 12), item('minecraft:cobblestone', 6, 8, 24),
                item('minecraft:wheat', 6, 2, 8), item('minecraft:string', 4, 1, 4)]
    rare = [item('minecraft:iron_sword', 6), item('minecraft:shield', 5), item('minecraft:crossbow', 5), item('minecraft:bow', 5),
            item('minecraft:gold_ingot', 6, 1, 1 + skulls), item('minecraft:saddle', 2), item('minecraft:lead', 4, 1, 2),
            item('minecraft:experience_bottle', 4, 1, skulls)]
    if skulls >= 2:
        rare += [item('minecraft:iron_chestplate', 3), item('minecraft:iron_helmet', 3), item('minecraft:book', 4, extra=ENCHANT)]
    if skulls >= 3:
        rare += [item('minecraft:diamond', 3, 1, skulls - 1), item('minecraft:golden_apple', 2)]
    if skulls >= 4:
        rare += [item('minecraft:diamond_sword', 2, extra=ENCHANT), item('minecraft:diamond_chestplate', 1, extra=ENCHANT),
                 item('minecraft:ender_pearl', 3, 1, 3)]
    if skulls >= 5:
        rare += [item('minecraft:netherite_scrap', 1, 1, 2), item('minecraft:enchanted_golden_apple', 1)]
    write(os.path.join(DM, 'loot_table', 'chests', f'outpost_{skulls}.json'), {
        'type': 'minecraft:chest', 'pools': [
            {'rolls': {'type': 'minecraft:uniform', 'min': 3, 'max': 5 + skulls}, 'entries': supplies},
            {'rolls': {'type': 'minecraft:uniform', 'min': 1, 'max': 1 + skulls // 2}, 'entries': rare}]})

write(os.path.join(DM, 'loot_table', 'gameplay', 'mage_work.json'), {
    'type': 'minecraft:gift', 'pools': [{'rolls': 1, 'entries': [
        item('minecraft:book', 6, extra=ENCHANT), item('minecraft:experience_bottle', 8, 3, 8), item('minecraft:lapis_lazuli', 6, 4, 10),
        item('minecraft:amethyst_shard', 4, 2, 6), item('minecraft:glowstone_dust', 4, 3, 8), item('minecraft:ender_pearl', 2, 1, 2),
        item('minecraft:golden_apple', 1)]}]})

# Iron's Spells 'n Spellbooks: таблицы загружаются только при установленном моде.
IRONS = [{'type': 'neoforge:mod_loaded', 'modid': 'irons_spellbooks'}]
SCROLL = {'type': 'minecraft:item', 'name': 'irons_spellbooks:scroll', 'weight': 10,
          'functions': [{'function': 'irons_spellbooks:randomize_spell', 'quality': {'min': 0.0, 'max': 0.6}}]}
GOOD_SCROLL = {'type': 'minecraft:item', 'name': 'irons_spellbooks:scroll', 'weight': 10,
               'functions': [{'function': 'irons_spellbooks:randomize_spell', 'quality': {'min': 0.4, 'max': 1.0}}]}
RUNES = [item(f'irons_spellbooks:{r}_rune', 2) for r in ('fire', 'ice', 'lightning', 'holy', 'ender', 'blood', 'evocation', 'nature', 'mana', 'cooldown', 'protection')]
write(os.path.join(DM, 'loot_table', 'compat', 'irons', 'mage_work.json'), {
    'neoforge:conditions': IRONS, 'type': 'minecraft:gift', 'pools': [{'rolls': 1, 'entries': [
        SCROLL, item('irons_spellbooks:arcane_essence', 6, 2, 6), item('irons_spellbooks:common_ink', 4, 1, 3),
        item('irons_spellbooks:uncommon_ink', 3, 1, 2), item('irons_spellbooks:rare_ink', 2)] + RUNES}]})
write(os.path.join(DM, 'loot_table', 'compat', 'irons', 'captain_treasure.json'), {
    'neoforge:conditions': IRONS, 'type': 'minecraft:chest', 'pools': [
        {'rolls': {'type': 'minecraft:uniform', 'min': 1, 'max': 3}, 'entries': [GOOD_SCROLL]},
        {'rolls': 1, 'entries': [item('irons_spellbooks:arcane_essence', 6, 3, 8), item('irons_spellbooks:rare_ink', 3)] + RUNES}]})

# Рецепты: решётку и доску можно сделать самому, Штандарт — только трофей.
write(os.path.join(DM, 'recipe', 'outpost_gate.json'), {
    'type': 'minecraft:crafting_shaped', 'category': 'building', 'pattern': ['BIB', 'BIB', 'BIB'],
    'key': {'B': {'item': 'minecraft:iron_bars'}, 'I': {'item': 'minecraft:iron_ingot'}},
    'result': {'id': f'{MOD}:outpost_gate', 'count': 6}})
write(os.path.join(DM, 'recipe', 'quest_board.json'), {
    'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': ['PPP', 'PBP', 'S S'],
    'key': {'P': {'tag': 'minecraft:planks'}, 'B': {'item': 'minecraft:paper'}, 'S': {'item': 'minecraft:stick'}},
    'result': {'id': f'{MOD}:quest_board', 'count': 1}})

# Пустой шаблон для игровых тестов (1x1x1 воздуха).
src = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'empty.nbt')
if os.path.exists(src):
    os.makedirs(os.path.join(DM, 'structure'), exist_ok=True)
    shutil.copy(src, os.path.join(DM, 'structure', 'empty.nbt'))
print('assets ok')
