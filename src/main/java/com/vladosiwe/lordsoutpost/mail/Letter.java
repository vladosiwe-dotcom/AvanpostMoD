package com.vladosiwe.lordsoutpost.mail;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Письмо или посылка. У каждого письма свой id; сервер хранит отправителя, получателя, вложения и статус.
 * Вложения выдаются только из записи и тут же из неё удаляются, поэтому одно письмо нельзя получить дважды:
 * ни повторным щелчком по почтомату, ни после перезапуска, ни при выгрузке чанков.
 */
public final class Letter {
    public enum Status {
        /** Ждёт получателя в любом почтомате. */
        WAITING,
        /** Всё получено; запись хранится недолго для истории. */
        CLAIMED;

        public static final Status[] VALUES = values();
    }

    public final UUID id;
    public final UUID recipient;
    /** Ключ перевода темы и её аргументы (имя форта, сумма и т. п.). */
    public final String subject;
    public final List<String> args = new ArrayList<>();
    public final String sender;
    public final List<ItemStack> items = new ArrayList<>();
    public final long sentAt;
    public Status status = Status.WAITING;
    public long claimedAt;

    public Letter(UUID id, UUID recipient, String sender, String subject, long sentAt) {
        this.id = id;
        this.recipient = recipient;
        this.sender = sender;
        this.subject = subject;
        this.sentAt = sentAt;
    }

    public boolean waiting() {
        return status == Status.WAITING;
    }

    public int itemCount() {
        int n = 0;
        for (ItemStack s : items) n += s.getCount();
        return n;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        t.putUUID("to", recipient);
        t.putString("from", sender);
        t.putString("subject", subject);
        ListTag a = new ListTag();
        for (String s : args) a.add(StringTag.valueOf(s));
        t.put("args", a);
        ListTag it = new ListTag();
        for (ItemStack s : items) if (!s.isEmpty()) it.add(s.save(registries));
        t.put("items", it);
        t.putLong("sent", sentAt);
        t.putByte("status", (byte) status.ordinal());
        t.putLong("claimed", claimedAt);
        return t;
    }

    public static Letter load(CompoundTag t, HolderLookup.Provider registries) {
        Letter l = new Letter(t.getUUID("id"), t.getUUID("to"), t.getString("from"), t.getString("subject"), t.getLong("sent"));
        ListTag a = t.getList("args", Tag.TAG_STRING);
        for (int i = 0; i < a.size(); i++) l.args.add(a.getString(i));
        ListTag it = t.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < it.size(); i++) ItemStack.parse(registries, it.getCompound(i)).filter(s -> !s.isEmpty()).ifPresent(l.items::add);
        l.status = Status.VALUES[Math.max(0, Math.min(Status.VALUES.length - 1, t.getByte("status")))];
        l.claimedAt = t.getLong("claimed");
        return l;
    }
}
