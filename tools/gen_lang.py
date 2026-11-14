"""Пишет ru_ru.json и en_us.json из одной таблицы, чтобы наборы ключей всегда совпадали.

Запуск: python3 tools/gen_lang.py
"""
import json, os

P = 'lordsoutpost.'
T = {}


def add(key, ru, en):
    T[key] = (ru, en)


# --- Предметы, блоки, сущности, клавиши ---
add('itemGroup.lordsoutpost', "Lord's Outpost", "Lord's Outpost")
add('block.lordsoutpost.lord_standard', 'Штандарт Лорда', "Lord's Standard")
add('block.lordsoutpost.outpost_gate', 'Решётка ворот аванпоста', 'Outpost Gate')
add('block.lordsoutpost.quest_board', 'Доска заданий', 'Quest Board')
add('item.lordsoutpost.lord_standard.desc', 'Установите во дворе захваченного аванпоста, чтобы стать его Лордом.', 'Place it in the yard of a conquered outpost to become its Lord.')
add('entity.lordsoutpost.fighter', 'Боец аванпоста', 'Outpost Fighter')
add('entity.lordsoutpost.captain', 'Капитан', 'Captain')
add('entity.lordsoutpost.worker', 'Житель аванпоста', 'Outpost Resident')
add('key.categories.lordsoutpost', "Lord's Outpost", "Lord's Outpost")
add('key.lordsoutpost.map', 'Карта аванпостов', 'Outpost map')
add('key.lordsoutpost.manage', 'Управление аванпостом', 'Manage outpost')

# --- Сложность, роли, ресурсы ---
for k, ru, en in [('frontier_post', 'Пограничная Застава', 'Frontier Post'), ('watch_fort', 'Дозорный Форт', 'Watch Fort'),
                  ('redoubt', 'Укреплённый Редут', 'Fortified Redoubt'), ('marauder_citadel', 'Цитадель Мародёров', 'Marauder Citadel'),
                  ('great_fortress', 'Великая Крепость', 'Great Fortress')]:
    add(P + 'difficulty.' + k, ru, en)
for k, ru, en in [('steward', 'Управляющий', 'Steward'), ('builder', 'Строитель', 'Builder'), ('hunter', 'Охотник', 'Hunter'),
                  ('herder', 'Скотовод', 'Herder'), ('miner', 'Шахтёр', 'Miner'), ('lumberjack', 'Лесоруб', 'Lumberjack'),
                  ('smith', 'Кузнец', 'Smith'), ('mage', 'Придворный Маг', 'Court Mage'), ('caravaneer', 'Караванщик', 'Caravaneer'),
                  ('shieldman', 'Щитовик', 'Shieldman'), ('berserker', 'Берсерк', 'Berserker'), ('archer', 'Лучник', 'Archer'),
                  ('crossbowman', 'Арбалетчик', 'Crossbowman'), ('captain', 'Капитан', 'Captain')]:
    add(P + 'role.' + k, ru, en)
for k, ru, en in [('wood', 'Дерево', 'Wood'), ('stone', 'Камень', 'Stone'), ('iron', 'Железо', 'Iron'), ('food', 'Еда', 'Food'),
                  ('hides', 'Шкуры', 'Hides'), ('gold', 'Казна', 'Treasury'), ('arcane', 'Эссенция', 'Arcane')]:
    add(P + 'resource.' + k, ru, en)
for i, (ru, en) in enumerate([('кожа', 'leather'), ('кольчуга', 'chainmail'), ('железо', 'iron'), ('алмаз', 'diamond')]):
    add(P + 'gear.%d' % i, ru, en)

# --- Законы ---
add(P + 'law.none', 'Без закона', 'No law')
add(P + 'law.none.desc', 'Обычный уклад: ни бонусов, ни штрафов.', 'Business as usual: no bonuses, no penalties.')
add(P + 'law.martial_law', 'Военное положение', 'Martial Law')
add(P + 'law.martial_law.desc', 'Оборона +30%, стража бьёт сильнее, новобранцев в полтора раза больше. Добыча ресурсов −25%.',
    'Defense +30%, guards hit harder, half again as many recruits. Resource output −25%.')
add(P + 'law.trade_and_magic', 'Торговля и Магия', 'Trade and Magic')
add(P + 'law.trade_and_magic.desc', 'Дань и прибыль караванов +50%, Маг работает вдвое быстрее. Оборона −10%.',
    'Tribute and caravan profit +50%, the Mage works twice as fast. Defense −10%.')
add(P + 'law.shock_construction', 'Ударная стройка', 'Shock Construction')
add(P + 'law.shock_construction.desc', 'Стройка вдвое быстрее. Еды уходит на четверть больше, добыча −15%.',
    'Construction twice as fast. Food use up by a quarter, output −15%.')

# --- Доступ ---
add(P + 'access.hostile', 'Враг', 'Hostile')
add(P + 'access.guest', 'Гость', 'Guest')
add(P + 'access.ally', 'Союзник', 'Ally')
add(P + 'access.lord', 'Лорд', 'Lord')
add(P + 'access.ally.desc', 'Стража дружелюбна, ворота и цитадель открыты, сундуки и станки доступны. Ломать постройки нельзя.',
    'Guards are friendly, gates and citadel open, chests and workstations usable. Cannot break buildings.')
add(P + 'access.guest.desc', 'Доступ во внешний двор. Цитадель и сундуки закрыты.', 'May enter the outer yard. Citadel and chests are closed.')
add(P + 'access.hostile.desc', 'Стража атакует при входе в периметр. Любое взаимодействие запрещено.',
    'Guards attack on entering the perimeter. No interaction allowed.')
add(P + 'access.ally.short', 'ворота, цитадель, сундуки', 'gates, citadel, chests')
add(P + 'access.guest.short', 'только внешний двор', 'outer yard only')
add(P + 'access.hostile.short', 'стража атакует', 'guards attack')

# --- Отряды и приказы ---
add(P + 'squad.0', 'Стража', 'Guard')
add(P + 'squad.1', 'Дозор', 'Watch')
add(P + 'squad.2', 'Свита', 'Retinue')
add(P + 'order.posts', 'Посты', 'Posts')
add(P + 'order.patrol', 'Патруль', 'Patrol')
add(P + 'order.hold', 'Держать', 'Hold')
add(P + 'order.retinue', 'За Лордом', 'Follow')
add(P + 'order.posts.tip', 'Стоять на постах: стрелки на стенах и башнях, остальные у ворот.', 'Stand at posts: archers on walls and towers, others at the gate.')
add(P + 'order.patrol.tip', 'Ходить по кругу через путевые точки. Точки задаются на карте; без них — обход по умолчанию.',
    'Walk a loop through waypoints. Set them on the map; without them the default round is used.')
add(P + 'order.retinue.tip', 'Сопровождать Лорда в походе. Павших в походе выносят в лазарет.', 'Escort the Lord on journeys. The fallen are carried to the infirmary.')

# --- Договоры ---
add(P + 'pact.none', 'нет договора', 'no pact')
add(P + 'pact.trade', 'торговый пакт', 'trade pact')
add(P + 'pact.military', 'военный пакт', 'military pact')

# --- Задания ---
add(P + 'quest.build_material', 'Материал для стройки: %s', 'Building material: %s')
add(P + 'quest.armor_repair', 'Починка брони стражи: %s', 'Guard armor repair: %s')
add(P + 'quest.food', 'Запасы на исходе: %s', 'Supplies running low: %s')
add(P + 'quest.rare_ingredient', 'Редкие ингредиенты для Мага: %s', 'Rare ingredients for the Mage: %s')
add(P + 'quest.done', 'Задание выполнено: %s. Награда: %s изумр.', 'Quest complete: %s. Reward: %s emeralds')
add(P + 'questkind.build_material', 'материал для стройки', 'building material')
add(P + 'questkind.armor_repair', 'починка брони', 'armor repair')
add(P + 'questkind.rare_ingredient', 'ингредиенты для Мага', 'mage ingredients')

# --- HUD и титулы ---
add(P + 'hud.found', 'Найден Аванпост', 'Outpost Found')
add(P + 'title.conquered', 'Аванпост захвачен', 'Outpost Conquered')
add(P + 'title.conquered.sub', 'Поставьте Штандарт Лорда во дворе', "Place the Lord's Standard in the yard")
add(P + 'title.lord', 'Титул получен: Лорд', 'Title earned: Lord')
add(P + 'title.lord.sub', 'Ваши владения: %s', 'Your domain: %s')
add(P + 'title.prefix', '[Лорд]', '[Lord]')
add(P + 'chat.founded', '%s поднимает Штандарт: отныне это владение Лорда (%s).', "%s raises the Standard: this is now a Lord's domain (%s).")

# --- Штандарт ---
add(P + 'standard.already_lord', 'У вас уже есть аванпост. Один Лорд — одно владение.', 'You already own an outpost. One Lord, one domain.')
add(P + 'standard.not_conquered', 'Сначала победите Капитана этого аванпоста.', "Defeat this outpost's Captain first.")
add(P + 'standard.outside', 'Штандарт ставится во дворе захваченного аванпоста.', 'The Standard must be placed in the yard of a conquered outpost.')
add(P + 'standard.name_invalid', 'Недопустимое имя.', 'Invalid name.')

# --- Сообщения ---
add(P + 'msg.abandoned', 'Вы покинули аванпост. Жители ушли.', 'You abandoned the outpost. The residents have left.')
add(P + 'msg.standard_sneak', 'Чтобы покинуть аванпост, сломайте Штандарт крадучись.', 'Break the Standard while sneaking to abandon the outpost.')
add(P + 'msg.protected', 'Эта территория под защитой Лорда.', "This land is under the Lord's protection.")
add(P + 'msg.locked', 'Устав гарнизона не позволяет вам это открыть.', 'The garrison charter does not let you open this.')
add(P + 'msg.no_lord', 'У этого аванпоста нет Лорда.', 'This outpost has no Lord.')
add(P + 'msg.not_lord', 'У вас нет аванпоста.', 'You do not own an outpost.')
add(P + 'msg.no_access', '%s — владение Лорда %s. Управление доступно союзникам.', "%s is Lord %s's domain. Management is for allies.")
add(P + 'msg.resident', '%s, %s. %s', '%s, %s. %s')
add(P + 'msg.law_cooldown', 'Закон можно менять раз в игровой день.', 'The law can be changed once per in-game day.')
add(P + 'msg.unknown_player', 'Игрок с таким именем не найден.', 'No player with that name was found.')
add(P + 'msg.deliver_far', 'Сдавать ресурсы можно только на территории аванпоста.', 'Resources can only be delivered inside the outpost.')
add(P + 'msg.delivered', 'Сдано: %s из %s', 'Delivered: %s of %s')
add(P + 'msg.nothing_to_deliver', 'В инвентаре нет подходящих предметов.', 'No matching items in your inventory.')
add(P + 'msg.pact_incoming', 'Лорд форта %s предлагает %s. Ответьте во вкладке «Дипломатия».', 'The Lord of %s offers a %s. Answer in the Diplomacy tab.')
add(P + 'msg.pact.ok', 'Договор заключён.', 'The pact is sealed.')
add(P + 'msg.pact.offered', 'Предложение отправлено. Ждём ответа другого Лорда.', "Offer sent. Waiting for the other Lord's answer.")
add(P + 'msg.pact.too_far', 'Этот аванпост слишком далеко для договора.', 'That outpost is too far for a pact.')
add(P + 'msg.pact.no_gold', 'В казне не хватает золота.', 'Not enough gold in the treasury.')
add(P + 'msg.pact.need_trade_first', 'Сначала нужен торговый пакт.', 'A trade pact is needed first.')
add(P + 'msg.pact.invalid', 'С этим аванпостом договориться нельзя.', 'No pact is possible with that outpost.')
add(P + 'msg.pact.already', 'Такой договор уже есть.', 'That pact already exists.')
add(P + 'msg.caravan.ok', 'Караван отправлен.', 'Caravan dispatched.')
add(P + 'msg.caravan.no_pact', 'Для каравана нужен торговый пакт.', 'A caravan needs a trade pact.')
add(P + 'msg.caravan.no_leader', 'Нет свободного Караванщика (появляется со второго тира).', 'No free Caravaneer (they arrive from tier 2).')
add(P + 'msg.caravan.no_cargo', 'Нечего везти: на складе нет излишков.', 'Nothing to carry: no surplus in stock.')
add(P + 'msg.caravan.invalid', 'Туда караван не отправить.', 'A caravan cannot go there.')

# --- Жители ---
add(P + 'npc.marauder', 'мародёр, %s', 'marauder, %s')
add(P + 'npc.raider', 'Налётчик (%s)', 'Raider (%s)')
add(P + 'npc.captain_bar', 'Капитан %s — %s', 'Captain %s — %s')

# --- Хроника и оповещения ---
add(P + 'event.founded', 'Лорд %s основал владение.', 'Lord %s founded the domain.')
add(P + 'event.abandoned', 'Лорд %s покинул аванпост.', 'Lord %s abandoned the outpost.')
add(P + 'event.conquered', 'Капитан пал, гарнизон разбежался.', 'The Captain fell and the garrison scattered.')
add(P + 'event.reclaimed', 'Мародёры вернулись в брошенный форт.', 'Marauders returned to the abandoned fort.')
add(P + 'event.tier_up', 'Достроен тир %s!', 'Tier %s complete!')
add(P + 'event.settler', 'Новый житель: %s (%s).', 'New resident: %s (%s).')
add(P + 'event.starving', 'Голод: кладовые пусты, работа идёт вполсилы.', 'Famine: the larders are empty and work goes at half pace.')
add(P + 'event.law', 'Объявлен закон: %s.', 'Law proclaimed: %s.')
add(P + 'event.raid_start', 'Набег! Сила врага: %s', 'Raid! Enemy strength: %s')
add(P + 'event.raid_won', 'Набег отбит. Потери: %s.', 'Raid repelled. Losses: %s.')
add(P + 'event.raid_lost', 'Набег не отбит: склады разграблены. Раненых: %s, погибших: %s.', 'The raid was not repelled: stores plundered. Wounded: %s, dead: %s.')
add(P + 'event.wounded', '%s ранен и отправлен в лазарет.', '%s is wounded and sent to the infirmary.')
add(P + 'event.died', '%s (%s) погиб.', '%s (%s) has died.')
add(P + 'event.quest_done', 'Лорд помог: %s (%s).', 'The Lord helped: %s (%s).')
add(P + 'event.mage_work', 'Придворный Маг закончил работу: плоды в Сундуке Дани.', 'The Court Mage finished a work: see the Tribute Chest.')
add(P + 'event.pact', 'Заключён %s: %s.', 'Sealed a %s: %s.')
add(P + 'event.pact_broken', 'Договор расторгнут: %s.', 'Pact broken: %s.')
add(P + 'event.caravan_sent', 'Караван вышел: %s.', 'Caravan set out: %s.')
add(P + 'event.caravan_back', 'Караван вернулся. Выручка: %s золота.', 'Caravan returned. Earned: %s gold.')
add(P + 'event.caravan_lost', 'Караван пропал в пути.', 'A caravan was lost on the road.')
add(P + 'event.caravan_visit', 'Прибыл караван из форта %s.', 'A caravan arrived from %s.')
add(P + 'event.ambush', 'Караван попал в засаду!', 'The caravan was ambushed!')
add(P + 'event.ambush_won', 'Конвой отбил засаду на караван.', 'The escort fought off an ambush.')
add(P + 'event.ambush_lost', 'Засада: караван потерял половину груза.', 'Ambush: the caravan lost half its cargo.')

# --- Карта ---
add(P + 'map.title', 'Карта аванпостов', 'Outpost Map')
add(P + 'map.overworld_only', 'Аванпосты есть только в Верхнем мире.', 'Outposts exist only in the Overworld.')
add(P + 'map.help', 'Перетаскивание — сдвиг · Колесо — масштаб · Пробел — к игроку · Home — к своему форту · Щелчок по метке — сведения',
    'Drag to pan · Wheel to zoom · Space: to player · Home: to your fort · Click a marker for details')
add(P + 'map.help_route', 'ЛКМ — добавить точку · ПКМ — убрать последнюю · «Готово» — отправить патруль по кругу',
    'LMB: add waypoint · RMB: remove last · Done: send the patrol around the loop')
add(P + 'map.help_hold', 'Щёлкните место, которое отряд должен удерживать', 'Click the spot the squad should hold')
add(P + 'map.unknown', 'Неразведанный аванпост', 'Unexplored Outpost')
add(P + 'map.unknown.hint', 'Подойдите ближе, чтобы разведать.', 'Come closer to scout it.')
add(P + 'map.state.wild', 'Мародёры · %s', 'Marauders · %s')
add(P + 'map.state.conquered', 'Захвачен, ждёт Лорда', 'Conquered, awaiting a Lord')
add(P + 'map.state.owned', 'Лорд: %s', 'Lord: %s')
add(P + 'map.tier', 'Тир %s · %s%%', 'Tier %s · %s%%')
add(P + 'map.distance', '%s м · %s, %s', '%s m · %s, %s')
add(P + 'map.manage', 'Управление', 'Manage')
add(P + 'map.pact_trade', 'Торговый пакт (%s)', 'Trade pact (%s)')
add(P + 'map.pact_trade.tip', 'Открывает караваны и увеличивает дань. С мародёрами — за золото казны, с Лордом — по согласию.',
    'Enables caravans and raises tribute. With marauders it costs treasury gold; with a Lord it needs consent.')
add(P + 'map.pact_military', 'Военный пакт (%s)', 'Military pact (%s)')
add(P + 'map.pact_military.tip', 'Сосед перестаёт устраивать набеги и помогает в обороне. Требует торгового пакта.',
    'The neighbor stops raiding and helps in defense. Requires a trade pact.')
add(P + 'map.allied', 'Военный союз', 'Military alliance')
add(P + 'map.caravan', 'Отправить караван', 'Send caravan')
add(P + 'map.caravan.tip', 'Караванщик с конвоем повезёт излишки. По дорогам караван идёт быстрее.', 'A caravaneer with escort carries the surplus. Caravans are faster on roads.')
add(P + 'map.break', 'Расторгнуть договор', 'Break pact')
add(P + 'map.route', 'Маршрут', 'Route')
add(P + 'map.route.tip', 'Проложить круговой маршрут патруля по карте.', 'Draw a patrol loop on the map.')
add(P + 'map.hold', 'Позиция', 'Hold')
add(P + 'map.hold.tip', 'Указать место, которое отряд будет удерживать. Повторное нажатие снимает приказ.', 'Pick a spot for the squad to hold. Press again to cancel.')
add(P + 'map.mode.route', 'Маршрут: %s · точек %s/%s', 'Route: %s · waypoints %s/%s')
add(P + 'map.mode.hold', 'Позиция для отряда: %s', 'Hold position for: %s')
add(P + 'map.done', 'Готово', 'Done')
add(P + 'map.clear', 'Сброс', 'Clear')
add(P + 'map.cancel', 'Отмена', 'Cancel')

# --- Окно управления ---
add(P + 'ui.title', 'Аванпост', 'Outpost')
add(P + 'ui.header', 'Лорд %s · тир %s · день %s', 'Lord %s · tier %s · day %s')
add(P + 'ui.rename', 'Переименовать', 'Rename')
add(P + 'ui.name', 'Имя форта', 'Fort name')
add(P + 'ui.naming', 'Как назовём ваши владения?', 'What shall your domain be called?')
add(P + 'ui.naming.hint', 'До 32 символов. Имя можно сменить позже.', 'Up to 32 characters. You can rename it later.')
add(P + 'ui.confirm', 'Назвать', 'Name it')
add(P + 'ui.later', 'Позже', 'Later')
for k, ru, en in [('overview', 'Обзор', 'Overview'), ('garrison', 'Гарнизон', 'Garrison'), ('access', 'Доступ', 'Access'),
                  ('quests', 'Задания', 'Quests'), ('diplomacy', 'Дипломатия', 'Diplomacy')]:
    add(P + 'ui.tab.' + k, ru, en)
add(P + 'ui.tier_done', 'Тир %s — крепость достроена', 'Tier %s — the fortress is complete')
add(P + 'ui.tier_progress', 'Тир %s → %s: %s%%', 'Tier %s → %s: %s%%')
add(P + 'ui.stalled', 'Стройка стоит: не хватает ресурса «%s»', 'Construction stalled: short of %s')
add(P + 'ui.steps', 'Блоков уложено: %s из %s', 'Blocks placed: %s of %s')
add(P + 'ui.population', 'Жители: %s/%s', 'Residents: %s/%s')
add(P + 'ui.starving', 'ГОЛОД', 'FAMINE')
add(P + 'ui.fighters', 'Бойцов: %s', 'Fighters: %s')
add(P + 'ui.raid', 'ИДЁТ НАБЕГ', 'RAID IN PROGRESS')
add(P + 'ui.law', 'Закон Лорда', "Lord's Law")
add(P + 'ui.law_cooldown', 'смена через %s мин', 'change in %s min')
add(P + 'ui.stock', 'Склад', 'Stock')
add(P + 'ui.pen', 'Скот: %s/%s', 'Livestock: %s/%s')
add(P + 'ui.boosted', 'Ускорение от помощи Лорда: ещё %s мин', "Boost from the Lord's help: %s min left")
add(P + 'ui.chronicle', 'Хроника', 'Chronicle')
add(P + 'ui.day', 'День %s:', 'Day %s:')
add(P + 'ui.abandon', 'Покинуть форт', 'Abandon fort')
add(P + 'ui.abandon_confirm', 'Точно покинуть?', 'Really abandon?')
add(P + 'ui.abandon.tip', 'Вы перестанете быть Лордом, жители уйдут, постройки останутся. Нажмите дважды.',
    'You stop being the Lord, residents leave, buildings stay. Click twice.')
add(P + 'ui.order', 'Приказ: %s', 'Order: %s')
add(P + 'ui.route', 'Маршрут (%s)', 'Route (%s)')
add(P + 'ui.wounded', 'ранен %sс', 'hurt %ss')
add(P + 'ui.on_caravan', 'в караване', 'on caravan')
add(P + 'ui.fighter.tip', '%s, %s\nЗдоровье %s%% · броня %s%% (%s) · побед: %s\nЩелчок — перевести в следующий отряд',
    '%s, %s\nHealth %s%% · armor %s%% (%s) · kills: %s\nClick to move to the next squad')
add(P + 'ui.garrison.hint', 'Щёлкните бойца, чтобы перевести его в другой отряд. Маршруты и позиции задаются на карте.',
    'Click a fighter to move them to another squad. Routes and positions are set on the map.')
add(P + 'ui.lord_only', 'Это решает только Лорд.', 'Only the Lord decides this.')
add(P + 'ui.access.list', 'Гарнизонный Устав', 'Garrison Charter')
add(P + 'ui.access.empty', 'Список пуст: для всех действует уровень по умолчанию.', 'The list is empty: everyone gets the default level.')
add(P + 'ui.access.remove', 'Убрать из списка', 'Remove from the list')
add(P + 'ui.access.add', 'Добавить игрока по имени', 'Add a player by name')
add(P + 'ui.player_name', 'Имя игрока', 'Player name')
add(P + 'ui.access.default', 'Незнакомцы:', 'Strangers:')
add(P + 'ui.access.default.tip', 'Уровень доступа для игроков, которых нет в списке.', 'Access level for players who are not on the list.')
add(P + 'ui.on', 'Вкл', 'On')
add(P + 'ui.off', 'Выкл', 'Off')
add(P + 'ui.toggle.auto_ally', 'Союз с Лордами союзных фортов', 'Ally the Lords of allied forts')
add(P + 'ui.toggle.auto_ally.tip', 'Лорды фортов, с которыми у вас договор, автоматически считаются союзниками.', 'Lords of forts you have a pact with count as allies automatically.')
add(P + 'ui.toggle.allies_tribute', 'Союзники открывают Сундук Дани', 'Allies may open the Tribute Chest')
add(P + 'ui.toggle.allies_tribute.tip', 'Если выключено, Сундук Дани открывает только Лорд.', 'When off, only the Lord opens the Tribute Chest.')
add(P + 'ui.toggle.protect', 'Защита территории от чужаков', 'Protect the land from strangers')
add(P + 'ui.toggle.protect.tip', 'Гости и враги не могут ломать и ставить блоки, взрывы не вредят постройкам. Союзники не ломают в любом случае.',
    'Guests and hostiles cannot break or place blocks and explosions do no damage. Allies never break blocks either way.')
add(P + 'ui.toggle.auto_caravans', 'Караваны сами', 'Auto caravans')
add(P + 'ui.toggle.auto_caravans.tip', 'Управляющий сам отправляет караваны с излишками к торговым партнёрам.', 'The Steward dispatches surplus caravans to trade partners on his own.')
add(P + 'ui.quests.title', 'Доска заданий: чего не хватает поселению', 'Quest board: what the settlement is short of')
add(P + 'ui.quests.empty', 'Нехваток нет. Поселение справляется само.', 'No shortages. The settlement is coping on its own.')
add(P + 'ui.quests.reward', 'Награда: %s', 'Reward: %s')
add(P + 'ui.quests.deliver', 'Сдать', 'Deliver')
add(P + 'ui.quests.deliver.tip', 'Отдать подходящие предметы из инвентаря (нужно стоять на территории форта).', 'Hand over matching items from your inventory (stand inside the fort).')
add(P + 'ui.quests.hint', 'Без вашей помощи жители восполнят нехватку сами, просто медленнее. Закрытое задание ускоряет связанную работу вдвое. Предметы можно также класть в бочку снабжения на складе.',
    'Without your help the residents make up the shortage themselves, only slower. A completed quest doubles the speed of the related work. You can also put items into the supply barrel at the storehouse.')
add(P + 'ui.quests.boost', 'Ускорение ×2: %s — ещё %s мин', 'Boost ×2: %s — %s min left')
add(P + 'ui.diplomacy.title', 'Соседние аванпосты', 'Neighboring outposts')
add(P + 'ui.diplomacy.treasury', 'Казна: %s', 'Treasury: %s')
add(P + 'ui.diplomacy.empty', 'Вы ещё не разведали соседей. Найдите их на карте (клавиша M).', 'You have not scouted any neighbors yet. Find them on the map (M).')
add(P + 'ui.diplomacy.incoming', 'предлагает пакт', 'offers a pact')
add(P + 'ui.diplomacy.offered', 'ждём ответа', 'awaiting answer')
add(P + 'ui.diplomacy.accept', 'Принять', 'Accept')
add(P + 'ui.diplomacy.trade', 'Торговый (%s)', 'Trade (%s)')
add(P + 'ui.diplomacy.military', 'Военный (%s)', 'Military (%s)')
add(P + 'ui.diplomacy.caravan', 'Караван', 'Caravan')
add(P + 'ui.diplomacy.caravan.tip', 'Отправить караван с излишками. Рейсов выполнено: %s. Доля пути по дорогам: %s%%.',
    'Send a caravan with the surplus. Trips done: %s. Share of the way on roads: %s%%.')
add(P + 'ui.caravans', 'Караваны в пути: %s (караванщиков: %s)', 'Caravans on the road: %s (caravaneers: %s)')
add(P + 'ui.caravans.none', 'Караванов нет. Излишков на складе: %s.', 'No caravans. Surplus in stock: %s.')
add(P + 'ui.caravan.phase.0', 'Идёт к: %s', 'Heading to: %s')
add(P + 'ui.caravan.phase.1', 'Торгует: %s', 'Trading at: %s')
add(P + 'ui.caravan.phase.2', 'Возвращается из: %s', 'Returning from: %s')
add(P + 'ui.caravan.escort', 'конвой %s', 'escort %s')
add(P + 'ui.wild_fort', 'Мародёры %s', 'Marauders %s')

# --- Команды ---
add(P + 'cmd.none_here', 'Здесь нет аванпоста, и своего у вас тоже нет.', 'There is no outpost here and you do not own one.')
add(P + 'cmd.none_near', 'Поблизости нет подходящих мест для аванпоста.', 'No outpost sites nearby.')
add(P + 'cmd.list', 'Известно аванпостов: %s (ближайшие 20)', 'Known outposts: %s (nearest 20)')
add(P + 'cmd.revealed', 'Разведано аванпостов: %s', 'Outposts revealed: %s')
add(P + 'cmd.created', 'Создан аванпост: ', 'Outpost created: ')
add(P + 'cmd.too_close', 'Слишком близко к другому аванпосту: ', 'Too close to another outpost: ')
add(P + 'cmd.not_wild', 'Этот аванпост уже не принадлежит мародёрам.', 'This outpost is no longer held by marauders.')
add(P + 'cmd.not_owned', 'У этого аванпоста нет Лорда.', 'This outpost has no Lord.')
add(P + 'cmd.captured', 'Гарнизон распущен. Штандарт Лорда выдан.', "Garrison dismissed. Lord's Standard given.")
add(P + 'cmd.tier_down', 'Понизить тир нельзя.', 'The tier cannot be lowered.')
add(P + 'cmd.bad_resource', 'Нет такого ресурса.', 'No such resource.')
add(P + 'cmd.raid', 'Набег начнётся в ближайшие секунды.', 'A raid will start within seconds.')
add(P + 'cmd.removed', 'Аванпост удалён из мира (постройки остались).', 'Outpost removed from the world (buildings remain).')

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources', 'assets', 'lordsoutpost', 'lang')
os.makedirs(root, exist_ok=True)
for name, idx in (('ru_ru.json', 0), ('en_us.json', 1)):
    with open(os.path.join(root, name), 'w', encoding='utf-8') as f:
        json.dump({k: v[idx] for k, v in T.items()}, f, ensure_ascii=False, indent=2)
        f.write('\n')
print('keys:', len(T))
