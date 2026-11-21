package com.vladosiwe.lordsoutpost.protection;

import com.vladosiwe.lordsoutpost.data.OutpostSavedData;
import com.vladosiwe.lordsoutpost.outpost.AccessLevel;
import com.vladosiwe.lordsoutpost.outpost.AccessRules;
import com.vladosiwe.lordsoutpost.outpost.Outpost;
import com.vladosiwe.lordsoutpost.outpost.OutpostState;
import com.vladosiwe.lordsoutpost.outpost.Pact;
import com.vladosiwe.lordsoutpost.outpost.Relation;

import java.util.UUID;

/** Вычисление уровня доступа игрока к аванпосту. */
public final class Access {
    private Access() {}

    public static AccessLevel level(OutpostSavedData data, Outpost o, UUID player) {
        if (o.state != OutpostState.OWNED) return AccessLevel.GUEST;
        if (player.equals(o.owner)) return AccessLevel.LORD;
        AccessRules.Entry entry = o.access.players.get(player);
        if (entry != null) return entry.level();
        if (o.access.autoAllyPacts) {
            Outpost theirs = data.lordOutpost(player);
            if (theirs != null) {
                Relation rel = o.relations.get(theirs.id);
                if (rel != null && rel.pact != Pact.NONE) return AccessLevel.ALLY;
            }
        }
        return o.access.defaultLevel;
    }
}
