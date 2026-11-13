package com.vladosiwe.lordsoutpost.protection;

import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostMember;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.function.BiPredicate;

/** Кто может пройти сквозь решётку ворот. Вызывается на обеих сторонах при каждом расчёте столкновений. */
public final class GateAccess {
    /** Клиентская проверка для локального игрока; устанавливается клиентским кодом. */
    public static volatile BiPredicate<BlockPos, Boolean> clientCheck = (pos, inner) -> true;

    private GateAccess() {}

    public static boolean canPass(Entity entity, BlockPos pos, boolean inner) {
        if (entity.level().isClientSide) {
            // Движение мобов считает сервер; на клиенте важен только сам игрок.
            return !(entity instanceof Player) || clientCheck.test(pos, inner);
        }
        if (!(entity.level() instanceof ServerLevel level)) return false;
        if (entity instanceof Player player && (player.isCreative() || player.isSpectator())) return true;
        Outpost o = OutpostSavedData.get(level).findAt(pos);
        if (o == null) return entity instanceof Player || (entity instanceof Mob && !(entity instanceof Enemy));
        if (entity instanceof OutpostMember member) {
            if (member.outpostId() == o.id) return true;
            Relation rel = o.relations.get(member.outpostId());
            return !inner && rel != null && rel.pact != Pact.NONE;
        }
        if (entity instanceof Player player) return playerCanPass(OutpostSavedData.get(level), o, player, inner);
        return entity instanceof Mob && !(entity instanceof Enemy);
    }

    public static boolean playerCanPass(OutpostSavedData data, Outpost o, Player player, boolean inner) {
        if (o.state == OutpostState.CONQUERED) return true;
        if (o.state == OutpostState.WILD) return false;
        return Access.level(data, o, player.getUUID()).atLeast(inner ? AccessLevel.ALLY : AccessLevel.GUEST);
    }
}
