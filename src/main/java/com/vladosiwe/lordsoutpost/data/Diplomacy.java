package com.vladosiwe.lordsoutpost.data;

import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;
import com.vladosiwe.lordsoutpost.outpost.Resource;

/**
 * Дипломатия. С диким аванпостом договор покупается у Капитана за золото казны;
 * с владением другого игрока — предлагается и ждёт его согласия.
 */
public final class Diplomacy {
    public enum Result { OK, OFFERED, TOO_FAR, NO_GOLD, NEED_TRADE_FIRST, INVALID, ALREADY }

    public static final double RANGE = 3000;

    private Diplomacy() {}

    public static int cost(Outpost target, Pact pact) {
        if (target.state != OutpostState.WILD) return 0;
        return pact == Pact.TRADE ? 20 * target.difficulty.skulls : pact == Pact.MILITARY ? 50 * target.difficulty.skulls : 0;
    }

    public static Result propose(OutpostSavedData data, Outpost o, Outpost target, Pact pact, long now) {
        if (o == target || o.state != OutpostState.OWNED || pact == Pact.NONE || target.state == OutpostState.CONQUERED) return Result.INVALID;
        if (target.distanceSqr(o.center.getX(), o.center.getZ()) > RANGE * RANGE) return Result.TOO_FAR;
        Relation rel = o.relations.computeIfAbsent(target.id, k -> new Relation());
        if (rel.pact.ordinal() >= pact.ordinal()) return Result.ALREADY;
        if (pact == Pact.MILITARY && rel.pact != Pact.TRADE) return Result.NEED_TRADE_FIRST;
        if (target.state == OutpostState.WILD) {
            int cost = cost(target, pact);
            if (!o.take(Resource.GOLD, cost)) return Result.NO_GOLD;
            set(o, target, pact);
            o.log(now, "lordsoutpost.event.pact", pact.id, target.difficulty.skulls + "*");
            data.setDirty();
            return Result.OK;
        }
        // Если другой Лорд уже предлагал нам то же самое — это согласие.
        Relation theirs = target.relations.get(o.id);
        if (theirs != null && theirs.offered.ordinal() >= pact.ordinal()) {
            set(o, target, pact);
            o.log(now, "lordsoutpost.event.pact", pact.id, target.name);
            target.log(now, "lordsoutpost.event.pact", pact.id, o.name);
            data.setDirty();
            return Result.OK;
        }
        rel.offered = pact;
        o.dirtyForClients = true;
        target.dirtyForClients = true;
        data.setDirty();
        return Result.OFFERED;
    }

    private static void set(Outpost a, Outpost b, Pact pact) {
        Relation ra = a.relations.computeIfAbsent(b.id, k -> new Relation());
        Relation rb = b.relations.computeIfAbsent(a.id, k -> new Relation());
        ra.pact = pact;
        rb.pact = pact;
        ra.offered = Pact.NONE;
        rb.offered = Pact.NONE;
        a.dirtyForClients = true;
        b.dirtyForClients = true;
    }

    public static void breakPact(OutpostSavedData data, Outpost o, Outpost target, long now) {
        Relation ra = o.relations.get(target.id);
        if (ra == null) return;
        ra.pact = Pact.NONE;
        ra.offered = Pact.NONE;
        Relation rb = target.relations.get(o.id);
        if (rb != null) {
            rb.pact = Pact.NONE;
            rb.offered = Pact.NONE;
        }
        o.log(now, "lordsoutpost.event.pact_broken", target.state == OutpostState.WILD ? target.difficulty.skulls + "*" : target.name);
        o.dirtyForClients = true;
        target.dirtyForClients = true;
        data.setDirty();
    }
}
