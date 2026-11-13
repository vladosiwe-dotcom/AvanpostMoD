package com.vladosiwe.lordsoutpost.outpost;

/** Сущность, которая принадлежит аванпосту (житель, боец, караванщик). */
public interface OutpostMember {
    /** Id аванпоста или 0 для бродячей банды. */
    long outpostId();
}
