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
add('block.lordsoutpost.storehouse_door', 'Дверь склада', 'Storehouse Door')
add(P + 'msg.store_locked', 'Склад заперт: войти могут только жители форта, Лорд и союзники.', 'The storehouse is locked: only residents, the Lord and allies may enter.')
add('item.lordsoutpost.lord_standard.desc', 'Установите во дворе захваченного аванпоста, чтобы стать его Лордом.', 'Place it in the yard of a conquered outpost to become its Lord.')
add('entity.lordsoutpost.fighter', 'Боец аванпоста', 'Outpost Fighter')
add('entity.lordsoutpost.captain', 'Лорд мародёров', 'Marauder Lord')
add('entity.lordsoutpost.worker', 'Житель аванпоста', 'Outpost Resident')
add('key.categories.lordsoutpost', "Lord's Outpost", "Lord's Outpost")
add('key.lordsoutpost.map', 'Карта аванпостов', 'Outpost map')
add('key.lordsoutpost.manage', 'Управление аванпостом', 'Manage outpost')

# --- Сложность, роли, ресурсы ---
for k, ru, en in [('frontier_post', 'Пограничная Застава', 'Frontier Post'), ('watch_fort', 'Дозорный Форт', 'Watch Fort'),
                  ('redoubt', 'Укреплённый Редут', 'Fortified Redoubt'), ('marauder_citadel', 'Цитадель Мародёров', 'Marauder Citadel'),
                  ('great_fortress', 'Великая Крепость', 'Great Fortress')]:
    add(P + 'difficulty.' + k, ru, en)
for k, ru, en in [('steward', 'Управляющий', 'Steward'), ('builder', 'Строитель', 'Builder'),
                  ('herder', 'Скотовод', 'Rancher'), ('farmer', 'Фермер', 'Farmer'), ('miner', 'Шахтёр', 'Miner'), ('lumberjack', 'Лесоруб', 'Lumberjack'),
                  ('smith', 'Кузнец', 'Smith'), ('mage', 'Придворный Маг', 'Court Mage'), ('caravaneer', 'Караванщик', 'Caravaneer'),
                  ('shieldman', 'Щитовик', 'Shieldman'), ('berserker', 'Берсерк', 'Berserker'), ('archer', 'Лучник', 'Archer'),
                  ('crossbowman', 'Арбалетчик', 'Crossbowman'), ('captain', 'Лорд', 'Lord')]:
    add(P + 'role.' + k, ru, en)
for k, ru, en in [('wood', 'Дерево', 'Wood'), ('stone', 'Камень', 'Stone'), ('iron', 'Железо', 'Iron'), ('food', 'Еда', 'Food'),
                  ('wheat', 'Пшеница', 'Wheat'), ('seeds', 'Семена', 'Seeds'), ('hides', 'Шкуры', 'Hides'), ('gold', 'Казна', 'Treasury'), ('arcane', 'Эссенция', 'Arcane')]:
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
add(P + 'standard.not_conquered', 'Сначала победите Лорда мародёров этого аванпоста.', "Defeat this outpost's Marauder Lord first.")
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
add(P + 'npc.captain_bar', 'Лорд %s — %s', 'Lord %s — %s')

# --- Хроника и оповещения ---
add(P + 'event.founded', 'Лорд %s основал владение.', 'Lord %s founded the domain.')
add(P + 'event.abandoned', 'Лорд %s покинул аванпост.', 'Lord %s abandoned the outpost.')
add(P + 'event.conquered', 'Лорд мародёров пал, гарнизон разбежался.', 'The Marauder Lord fell and the garrison scattered.')
add(P + 'event.reclaimed', 'Мародёры вернулись в брошенный форт.', 'Marauders returned to the abandoned fort.')
add(P + 'event.tier_up', 'Достроен тир %s!', 'Tier %s complete!')
add(P + 'event.catch_up', 'Пока мир был закрыт, форт прожил %s дн.: тир %s → %s.', 'While the world was closed, the outpost lived %s days: tier %s → %s.')
add(P + 'event.settler', 'Новый житель: %s (%s).', 'New resident: %s (%s).')
add(P + 'event.starving', 'Голод: кладовые пусты, работа идёт вполсилы.', 'Famine: the larders are empty and work goes at half pace.')
add(P + 'event.law', 'Объявлен закон: %s.', 'Law proclaimed: %s.')
add(P + 'event.raid_start', 'Набег! Сила врага: %s', 'Raid! Enemy strength: %s')
add(P + 'event.raid_won', 'Набег отбит. Потери: %s.', 'Raid repelled. Losses: %s.')
add(P + 'event.raid_lost', 'Набег не отбит: склады разграблены. Раненых: %s, погибших: %s.', 'The raid was not repelled: stores plundered. Wounded: %s, dead: %s.')
add(P + 'event.wounded', '%s ранен и отправлен в лазарет.', '%s is wounded and sent to the infirmary.')
add(P + 'event.died', '%s (%s) погиб.', '%s (%s) has died.')
add(P + 'event.quest_done', 'Лорд помог: %s (%s).', 'The Lord helped: %s (%s).')
add(P + 'event.mage_work', 'Придворный маг закончил работу: готовое на складе.', 'The Court Mage finished a work: it is in the storehouse.')
add(P + 'event.mage_crafted', 'Придворный маг изготовил: %s.', 'The Court Mage crafted: %s.')
add(P + 'event.pact', 'Заключён %s: %s.', 'Sealed a %s: %s.')
add(P + 'event.pact_broken', 'Договор расторгнут: %s.', 'Pact broken: %s.')
add(P + 'event.caravan_sent', 'Караван вышел: %s.', 'Caravan set out: %s.')
add(P + 'event.caravan_back', 'Караван вернулся. Выручка: %s золота.', 'Caravan returned. Earned: %s gold.')
add(P + 'event.caravan_lost', 'Караван пропал в пути.', 'A caravan was lost on the road.')
add(P + 'event.caravan_visit', 'Прибыл караван из форта %s.', 'A caravan arrived from %s.')
add(P + 'event.ambush', 'Караван попал в засаду!', 'The caravan was ambushed!')
add(P + 'event.ambush_won', 'Конвой отбил засаду на караван.', 'The escort fought off an ambush.')
add(P + 'event.pyromancer_joined', 'К башне мага пришёл пиромант: он защищает форт огнём и торгует свитками.', 'A pyromancer arrived at the mage tower: he defends the fort with fire and trades scrolls.')
add(P + 'event.pyromancer_died', 'Пиромант пал. Новый придёт к башне мага через несколько дней.', 'The pyromancer fell. A new one will come to the mage tower in a few days.')
add(P + 'event.project_ordered', 'Лорд заказал постройку: %s.', 'The Lord ordered a building: %s.')
add(P + 'event.project_done', 'Достроено: %s.', 'Completed: %s.')
add(P + 'event.project_cancelled', 'Заказ отменён: %s.', 'Order cancelled: %s.')
add(P + 'event.caravan_goods_lost', 'Вьючное животное каравана пало — груз остался на дороге.', 'The caravan\'s pack animal fell — its cargo lies on the road.')
add(P + 'event.mine_ready', 'Новый шахтный ствол прокопан: шахтёр начинает новый штрек.', 'The new mine shaft is dug: the miner starts a new tunnel.')
add(P + 'event.zone_moved', 'Рабочая зона «%s» перенесена в %s.', 'Work zone "%s" moved to %s.')
add(P + 'event.zone_reset', 'Рабочая зона «%s» возвращена на место по чертежу.', 'Work zone "%s" returned to its blueprint place.')
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
                  ('quests', 'Задания', 'Quests'), ('diplomacy', 'Договоры', 'Pacts'), ('economy', 'Хозяйство', 'Economy'),
                  ('build', 'Стройка', 'Build')]:
    add(P + 'ui.tab.' + k, ru, en)
# --- Почта ---
add('block.lordsoutpost.postbox', 'Почтомат', 'Postbox')
add(P + 'mail.arrived', 'Вам письмо от «%s» — заберите его в любом почтомате.', 'You have mail from "%s" — collect it at any postbox.')
add(P + 'mail.read', 'Письмо от «%s»: %s', 'Letter from "%s": %s')
add(P + 'mail.none', 'Писем нет.', 'No mail.')
add(P + 'mail.claimed', 'Получено писем: %s', 'Letters collected: %s')
add(P + 'mail.inventory_full', 'Не всё поместилось — остаток ждёт в почтомате.', 'Not everything fit — the rest waits in the postbox.')
add(P + 'mail.tribute', 'дань владения (%s стопок)', 'tribute of the domain (%s stacks)')
add(P + 'mail.tribute_empty', 'Новой дани нет: всё уже в Сундуке Дани.', 'No new tribute: everything is already in the Tribute Chest.')
add(P + 'mail.caravan_delivered', 'караван доставил на склад %s предметов, казна заплатила %s золота', 'a caravan delivered %s items to the storehouse, the treasury paid %s gold')
add(P + 'ui.people', 'Жители сейчас', 'Residents now')
add(P + 'ui.people.tip', 'Что делает каждый житель прямо сейчас. «Вдали» — житель вне загруженных чанков: его работу считает A-Life.', 'What each resident is doing right now. "Far away" — outside loaded chunks: A-Life simulates their work.')
for k, ru, en in [('idle', 'ищет работу', 'looking for work'), ('pickup', 'подбирает вещи', 'picking up items'), ('deposit', 'несёт на склад', 'carrying to the storehouse'),
                  ('craft', 'мастерит у склада', 'crafting at the storehouse'), ('chore', 'отдыхает, обходит двор', 'resting, walking the yard'), ('talk', 'разговаривает', 'talking'),
                  ('sink', 'копает новый ствол', 'sinking a new shaft'), ('vein', 'выбирает рудную жилу', 'mining an ore vein'), ('tunnel', 'ведёт штрек', 'digging the tunnel'),
                  ('clear', 'расчищает двор', 'clearing the yard'), ('chop', 'рубит дерево', 'felling a tree'), ('hunt', 'охотится', 'hunting'), ('herd', 'в загоне со скотом', 'tending the herd'),
                  ('farm', 'работает в поле', 'working the field'), ('smelt', 'плавит руду', 'smelting ore'), ('smith', 'в кузнице', 'at the smithy'), ('shop', 'в лавке', 'at the shop'),
                  ('tidy', 'прибирает в постройках', 'tidying the buildings'), ('work', 'работает', 'working'), ('build', 'строит', 'building'), ('mage', 'колдует у башни', 'working at the tower'),
                  ('caravan', 'в караване', 'with a caravan'), ('shelter', 'прячется от опасности', 'taking shelter'), ('sleep', 'спит', 'sleeping'),
                  ('fight', 'сражается', 'fighting'), ('guard', 'на посту', 'on guard'), ('away', 'вне форта', 'outside the fort'), ('virtual', 'работает вдали', 'working far away')]:
    add(P + 'activity.' + k, ru, en)
add(P + 'ui.mail.tribute', 'Дань: %s стоп.', 'Tribute: %s stacks')
add(P + 'ui.mail.send', 'Почтой', 'By mail')
add(P + 'ui.mail.send.tip', 'Отправить накопленную дань посылкой: её можно забрать в любом почтомате.', 'Send the accumulated tribute as a parcel: collect it at any postbox.')
# --- HUD владения ---
add(P + 'hud.tier', 'Тир %s · стройка %s%%', 'Tier %s · building %s%%')
add(P + 'hud.tier_done', 'Тир %s · крепость достроена', 'Tier %s · fortress complete')
add(P + 'hud.people', 'Жители: %s/%s', 'Residents: %s/%s')
add(P + 'hud.food', 'Еда: %s дн.', 'Food: %s days')
add(P + 'hud.scarce', 'Не хватает: %s', 'Short of: %s')
add(P + 'hud.projects', 'Заказов в работе: %s', 'Orders in progress: %s')
add(P + 'hud.problems', 'Проблем в рабочих зонах: %s', 'Work zone problems: %s')
add(P + 'hud.raid', 'Набег!', 'Raid!')
# --- Хозяйство и стройка ---
add(P + 'ui.director', 'Планировщик', 'Director')
add(P + 'ui.director.food', 'Еды на %s дн.', 'Food for %s days')
add(P + 'ui.director.no_shortage', 'Стройке всего хватает', 'Construction has everything it needs')
add(P + 'ui.director.shortage', 'Дефицит: %s', 'Shortage: %s')
add(P + 'ui.director.paused', 'Голод: стройка идёт вдвое медленнее, вдали — стоит', 'Famine: building runs at half speed (paused while you are away)')
add(P + 'ui.director.priorities', 'Важнее всего: %s', 'Top priorities: %s')
add(P + 'ui.tasks', 'Задачи жителей', 'Residents\' tasks')
add(P + 'ui.tasks.empty', 'Особых поручений нет: каждый занят своим делом.', 'No special orders: everyone is about their own work.')
add(P + 'ui.zones', 'Рабочие зоны', 'Work zones')
add(P + 'ui.zones.problem', 'Проблема: %s', 'Problem: %s')
add(P + 'ui.zones.here', 'Сюда', 'Here')
add(P + 'ui.zones.here.tip', 'Перенести зону туда, где вы стоите (во дворе).', 'Move the zone to where you stand (in the yard).')
add(P + 'ui.zones.reset', 'Назад', 'Reset')
add(P + 'ui.zones.rebuild', 'Новое место', 'New place')
add(P + 'ui.zones.rebuild.tip', 'Построить этот объект перед вами (смотрите туда, где строить). Когда постройка будет готова, работник переедет туда.', 'Build this object in front of you (look where to build). When it is done, the worker moves there.')
add(P + 'ui.zones.reset.tip', 'Вернуть зону на место по чертежу.', 'Return the zone to its blueprint place.')
add(P + 'ui.shop', 'Лавка (%s/9)', 'Shop (%s/9)')
add(P + 'ui.shop.empty', 'Пусто: торговец принесёт излишки склада.', 'Empty: the merchant brings storehouse surplus.')
add(P + 'ui.magic', 'Магия', 'Magic')
add(P + 'ui.magic.no_tower', 'Нужна башня мага (тир 3).', 'Needs the mage tower (tier 3).')
add(P + 'ui.magic.mages', 'Придворных магов: %s · эссенции: %s', 'Court mages: %s · essence: %s')
add(P + 'ui.magic.work.tip', 'Рядом с вами маг работает руками: свитки огня в кузне свитков (бумага, чернила, стержень ифрита), чернила, эссенция. Вдали — тратит накопленную эссенцию (20 за работу). Всё готовое — на складе.', 'Near you the mage works by hand: fire scrolls at the scroll forge (paper, ink, blaze rod), inks, essence. Far away he spends stored essence (20 per work). Everything goes to the storehouse.')
add(P + 'ui.magic.pyro_none', 'Пиромант: нужен мод Iron\'s Spells \'n Spellbooks', 'Pyromancer: needs Iron\'s Spells \'n Spellbooks')
add(P + 'ui.magic.pyro_here', 'Пиромант охраняет башню', 'The pyromancer guards the tower')
add(P + 'ui.magic.pyro_wait', 'Пиромант придёт через ~%s мин.', 'The pyromancer arrives in ~%s min.')
add(P + 'ui.magic.next', 'Маг готовит: %s', 'The mage is preparing: %s')
add(P + 'ui.magic.idle', 'Маг ждёт ингредиентов на складе', 'The mage waits for ingredients in the storehouse')
add(P + 'ui.magic.recipes.tip', 'Рецепты мага (всё со склада, через резерв): свиток огня — бумага + чернила + стержень ифрита у кузни свитков; чернила — пузырёк + 2 угля; чернила выше — 4 чернила + медь/железо/золото/аметист; эссенция — 3 лазурита + аметист. Без Iron\'s Spells — зачарованная книга из книги и 3 лазуритов.', 'Mage recipes (all from the storehouse, reserved): fire scroll — paper + ink + blaze rod at the scroll forge; ink — bottle + 2 coal; better ink — 4 inks + copper/iron/gold/amethyst; essence — 3 lapis + amethyst. Without Iron\'s Spells — an enchanted book from a book and 3 lapis.')
add(P + 'ui.magic.no_forge', 'Для свитков поставьте кузню свитков у башни мага.', 'Place a scroll forge by the mage tower for scrolls.')
add(P + 'ui.shelf', 'Полка мага: %s/%s', 'Mage shelf: %s/%s')
add(P + 'ui.shelf.tip', 'Маг сам приносит со склада готовые магические товары и продаёт их у башни (ПКМ по магу). Выручка — в казну.', 'The mage brings finished magic goods from the storehouse and sells them by the tower (right-click him). Proceeds go to the treasury.')
add(P + 'ui.shelf.empty', 'Пусто: продаётся только то, что реально сделано или сдано на склад.', 'Empty: only goods really made or stored are sold.')
add(P + 'ui.shelf.price', '%s изумр.', '%s emeralds')
add(P + 'ui.shelf.title', '%s — магические товары', '%s — magic goods')
add(P + 'msg.shelf_empty', '%s: на полке пока пусто — товар появится, когда маг его изготовит.', '%s: the shelf is empty for now — goods appear once the mage makes them.')
add(P + 'msg.mage_refuses', '%s не торгует с вами.', '%s will not trade with you.')
add(P + 'task.mage_craft', 'Работа мага', 'Mage work')
for rid, ru, en in [('fire_scroll_common', 'свиток огня (обычные чернила)', 'fire scroll (common ink)'),
                    ('fire_scroll_uncommon', 'свиток огня (необычные чернила)', 'fire scroll (uncommon ink)'),
                    ('fire_scroll_rare', 'свиток огня (редкие чернила)', 'fire scroll (rare ink)'),
                    ('fire_scroll_epic', 'свиток огня (эпические чернила)', 'fire scroll (epic ink)'),
                    ('fire_scroll_legendary', 'свиток огня (легендарные чернила)', 'fire scroll (legendary ink)'),
                    ('ink_common', 'обычные чернила', 'common ink'), ('ink_uncommon', 'необычные чернила', 'uncommon ink'),
                    ('ink_rare', 'редкие чернила', 'rare ink'), ('ink_epic', 'эпические чернила', 'epic ink'),
                    ('ink_legendary', 'легендарные чернила', 'legendary ink'), ('arcane_essence', 'магическая эссенция', 'arcane essence'),
                    ('enchanted_book', 'зачарованная книга', 'enchanted book')]:
    add(P + 'craft.' + rid, ru, en)
add(P + 'ui.build.advice', 'Совет: %s — жителей %s, мест %s, нужно %s', 'Advice: %s — residents %s, places %s, needed %s')
add(P + 'ui.build.advice.go', 'Построить', 'Build')
add(P + 'ui.build.advice.go.tip', 'Место выберется само: вокруг форта, входом к нему, без захвата места будущих тиров.', 'The spot is chosen automatically: around the fort, entrance facing it, without taking space of future tiers.')
add(P + 'ui.build.no_advice', 'Планировщик пока ничего не советует.', 'The director has no advice right now.')
add(P + 'ui.build.auto_on', 'Авто-застройка: вкл', 'Auto-build: on')
add(P + 'ui.build.auto_off', 'Авто-застройка: выкл', 'Auto-build: off')
add(P + 'ui.build.auto.tip', 'Включено — планировщик сам заказывает то, что советует. Выключено — только советует, решаете вы. План тиров форта растёт сам в любом случае.', 'On — the director orders what it advises by itself. Off — it only advises and you decide. The fort tier plan grows by itself either way.')
add(P + 'hud.advice', 'Совет: построить %s', 'Advice: build %s')
add(P + 'msg.project.not_loaded', 'Подойдите к аванпосту: место выбирается по настоящей местности.', 'Come closer to the outpost: the spot is chosen on the real terrain.')
add(P + 'event.project_auto', 'Авто-застройка: заказано %s.', 'Auto-build ordered: %s.')
add(P + 'event.autobuild_on', 'Лорд включил авто-застройку.', 'The Lord enabled auto-build.')
add(P + 'event.autobuild_off', 'Лорд выключил авто-застройку.', 'The Lord disabled auto-build.')
add(P + 'ui.build.order', 'Заказать здесь', 'Order here')
add(P + 'ui.build.order.tip', 'Встаньте там, откуда строить, и смотрите в сторону постройки: она встанет перед вами, вход — к вам. Место будущих тиров форта занимать нельзя.', 'Stand where you want it and look that way: it is placed in front of you with the entrance facing you. Space reserved for future fort tiers cannot be used.')
add(P + 'ui.build.limit', 'Одновременно строится не больше %s заказов.', 'At most %s orders can be built at once.')
add(P + 'ui.build.list', 'Заказы: %s из %s · домов построено: %s', 'Orders: %s of %s · houses built: %s')
add(P + 'ui.build.empty', 'Заказов пока нет.', 'No orders yet.')
add(P + 'ui.build.cancel', 'Отменить', 'Cancel')
add(P + 'ui.build.cancel.tip', 'Построенное останется, неистраченные материалы вернутся на склад.', 'What is built stays; unused materials go back to storage.')
add(P + 'ui.build.hint', 'Постройки оплачиваются из тех же ресурсов и строятся теми же строителями, что и форт, но после работ по плану тира. Пока вас нет, оплаченное встанет при возвращении. При голоде стройка ждёт.', 'Orders use the same resources and builders as the fort, after the tier plan work. While you are away, what is paid for is placed when you return. During famine construction waits.')
for k, ru, en, d_ru, d_en in [
        ('house', 'Дом', 'House', '+2 места для жителей, две кровати.', '+2 resident places, two beds.'),
        ('watchtower', 'Сторожевая башня', 'Watchtower', 'Пост для стрелка, +5% к обороне.', 'A post for an archer, +5% defence.'),
        ('wall', 'Участок стены', 'Wall section', 'Стена 11 блоков, +3% к обороне.', '11-block wall, +3% defence.'),
        ('pen', 'Новый загон', 'New pen', 'Загон 7×7. Когда готов, скотовод переводит туда скот.', 'A 7×7 pen. When done, the rancher moves the herd there.'),
        ('smithy', 'Новая кузница', 'New smithy', 'Печи, наковальня, стол. Кузнец переезжает и забирает содержимое старых печей.', 'Furnaces, anvil, table. The smith moves and empties the old furnaces.'),
        ('mine', 'Новая шахта', 'New mine', 'Копёр над стволом. Ствол с лестницами прокапывает шахтёр, затем ведёт новый штрек.', 'A headframe. The miner digs the laddered shaft, then a new tunnel.')]:
    add(P + 'project.' + k, ru, en)
    add(P + 'project.' + k + '.desc', d_ru, d_en)
for k, ru, en in [('mine', 'Шахта', 'Mine'), ('woodyard', 'Дровяной двор', 'Woodyard'), ('pen', 'Загон', 'Pen'), ('farm', 'Поле', 'Field'),
                  ('smithy', 'Кузница', 'Smithy'), ('storehouse', 'Склад', 'Storehouse'), ('mage_tower', 'Башня мага', 'Mage tower'),
                  ('shop', 'Лавка', 'Shop')]:
    add(P + 'zone.' + k, ru, en)
for k, ru, en in [('repair_armor', 'Ремонт брони', 'Armour repair'), ('upgrade_gear', 'Перековка снаряжения', 'Gear upgrade'),
                  ('breed_animals', 'Разведение', 'Breeding'), ('fetch_seeds', 'Семена', 'Seeds'), ('fetch_torches', 'Факелы', 'Torches'),
                  ('forge_tool', 'Ковка', 'Forging'), ('fetch_feed', 'Корм', 'Feed'), ('fetch_tools', 'Инструменты', 'Tools'),
                  ('fetch_pickaxe', 'Кирка', 'Pickaxe'), ('smelt_ore', 'Переплавка', 'Smelting'), ('fetch_sapling', 'Саженец', 'Sapling'),
                  ('stock_shop', 'Товар в лавку', 'Shop stock'), ('fetch_build', 'Материал для стройки', 'Building material'),
                  ('fetch_ladders', 'Лестницы для ствола', 'Shaft ladders')]:
    add(P + 'task.' + k, ru, en)
for k, ru, en in [('created', 'создана', 'created'), ('available', 'ждёт исполнителя', 'waiting'), ('reserved', 'материалы отложены', 'materials reserved'),
                  ('in_progress', 'в работе', 'in progress'), ('paused', 'приостановлена', 'paused')]:
    add(P + 'taskstate.' + k, ru, en)
for k, ru, en in [('ok', 'Заказ принят: строители возьмутся за него после работ по плану.', 'Order accepted: builders take it after the tier plan work.'),
                  ('not_here', 'Слишком далеко от форта.', 'Too far from the fort.'),
                  ('overlaps_fort', 'Здесь встанет следующий тир форта — выберите другое место.', 'The next fort tier goes here — pick another spot.'),
                  ('occupied', 'Место занято постройками, сундуками или другим заказом.', 'The spot is taken by buildings, chests or another order.'),
                  ('too_many', 'Слишком много заказов одновременно.', 'Too many orders at once.'),
                  ('no_ground', 'Здесь нет ровной земли рядом с фортом.', 'There is no level ground near the fort here.'),
                  ('uneven', 'Слишком неровная земля: под полом обрыв или холм.', 'The ground is too uneven: a drop or a hill under the floor.'),
                  ('no_resources', 'Не хватает ресурсов на складе (с учётом резерва других заказов).', 'Not enough resources in the storehouse (other orders\' reserve included).')]:
    add(P + 'msg.project.' + k, ru, en)
add(P + 'rts.title', 'Режим строительства', 'Construction mode')
add(P + 'rts.open', 'Режим строительства', 'Construction mode')
add(P + 'rts.open.tip', 'Стратегическая камера над аванпостом: выберите постройку, место и поворот — строители сделают остальное.', 'A strategic camera over the outpost: pick a building, a spot and a rotation — the builders do the rest.')
add(P + 'rts.help', 'WASD — камера · Q/E, средняя кнопка — поворот · R/F — наклон · колесо — приближение; с выбранной постройкой — её поворот (Ctrl+колесо — приближение) · Пробел — к центру · Tab — обзор · ЛКМ — заказать · ПКМ — отменить выбор · Esc — выход', 'WASD — camera · Q/E and middle button — rotate · R/F — tilt · wheel — zoom (with a building — rotate it, Ctrl+wheel — zoom) · Space — to centre · Tab — overview · LMB — order · RMB — cancel')
add(P + 'rts.catalog', 'Постройки', 'Buildings')
add(P + 'rts.stock', 'Склад (резерв)', 'Stock (reserved)')
add(P + 'rts.stock_line', '%s (%s)', '%s (%s)')
add(P + 'rts.pick', 'Выберите постройку слева, затем место на земле. Зелёный — можно строить, красный — нельзя.', 'Pick a building on the left, then a spot on the ground. Green — can build, red — cannot.')
add(P + 'rts.cost_line', '%s из %s', '%s of %s')
add(P + 'rts.point', 'Наведите на землю.', 'Point at the ground.')
add(P + 'rts.checking', 'Проверка места…', 'Checking the spot…')
add(P + 'rts.can_place', 'Можно строить: ЛКМ — заказать.', 'Can build: LMB to order.')
add(P + 'rts.missing', 'Не хватает: %s', 'Missing: %s')
add(P + 'rts.confirm', 'Заказать', 'Order')
add(P + 'rts.confirm.tip', 'Ресурсы резервируются на складе; строители принесут материал и поставят постройку блок за блоком.', 'Resources are reserved in the storehouse; builders bring materials and place the building block by block.')
add(P + 'rts.cancel_pick', 'Отменить выбор', 'Cancel')
add(P + 'rts.rotation', 'Поворот: %s° (колесо мыши)', 'Rotation: %s° (mouse wheel)')
add(P + 'rts.exit', 'Выйти из режима строительства', 'Leave construction mode')
add(P + 'rts.orders', 'Заказы: %s из %s', 'Orders: %s of %s')
add(P + 'rts.no_orders', 'Заказов нет.', 'No orders.')
add(P + 'rts.up', 'Выше в очереди', 'Higher priority')
add(P + 'rts.down', 'Ниже в очереди', 'Lower priority')
add(P + 'rts.cancel_order', 'Отменить заказ (неистраченное вернётся на склад)', 'Cancel the order (unspent materials return)')
add(P + 'rts.builders', 'Строителей: %s', 'Builders: %s')
add(P + 'rts.lack', 'Нехватка: %s', 'Shortage: %s')
add(P + 'rts.remaining', 'Осталось: %s', 'Remaining: %s')
add(P + 'rts.show', 'Показать на местности', 'Show on the map')
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

# --- Управление поселением ---
add(P + 'settlement.header', 'Еды на %s дн. Дефицит: %s %s', 'Food for %s days. Shortage: %s %s')
add(P + 'settlement.priorities', 'Приоритеты планировщика: %s', 'Director priorities: %s')
add(P + 'settlement.no_tasks', 'Активных задач нет.', 'No active tasks.')
add(P + 'settlement.zone_fixed', 'Эту зону нельзя перенести без перестройки: доступны farm, woodyard и shop.', 'This zone cannot be moved without rebuilding: farm, woodyard and shop can be moved.')
add(P + 'msg.shop_empty', '%s: лавка пока пуста — товар принесут со склада, когда появятся излишки.', '%s: the shop is empty for now — goods come from the storehouse when there is a surplus.')
add(P + 'settlement.zone_outside', 'Встаньте во дворе своего аванпоста.', 'Stand in the yard of your outpost.')
add(P + 'settlement.zone_no_soil', 'Здесь нет земли под грядку 5×5.', 'There is no soil for a 5×5 field here.')
add(P + 'settlement.zone_done', 'Зона «%s» обновлена.', 'Zone "%s" updated.')

# --- Команды ---
add(P + 'cmd.none_here', 'Здесь нет аванпоста, и своего у вас тоже нет.', 'There is no outpost here and you do not own one.')
add(P + 'cmd.none_near', 'Поблизости нет подходящих мест для аванпоста.', 'No outpost sites nearby.')
add(P + 'cmd.list', 'Известно аванпостов: %s (ближайшие 20)', 'Known outposts: %s (nearest 20)')
add(P + 'cmd.revealed', 'Разведано аванпостов: %s', 'Outposts revealed: %s')
add(P + 'cmd.created', 'Создан аванпост: ', 'Outpost created: ')
add(P + 'cmd.water', 'Здесь вода: форт ставится на сушу.', 'There is water here: forts go on dry land.')
add(P + 'cmd.too_close', 'Слишком близко к другому аванпосту: ', 'Too close to another outpost: ')
add(P + 'cmd.not_wild', 'Этот аванпост уже не принадлежит мародёрам.', 'This outpost is no longer held by marauders.')
add(P + 'cmd.not_owned', 'У этого аванпоста нет Лорда.', 'This outpost has no Lord.')
add(P + 'cmd.captured', 'Лорд мародёров повержен, гарнизон распущен. Штандарт Лорда выдан.', "Garrison dismissed. Lord's Standard given.")
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
