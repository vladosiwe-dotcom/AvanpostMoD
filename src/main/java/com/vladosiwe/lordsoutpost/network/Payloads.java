package com.vladosiwe.lordsoutpost.network;

import com.vladosiwe.lordsoutpost.LordsOutpost;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Пакеты мода. Клиент шлёт только запросы; сервер проверяет каждый (владение, расстояние, значения)
 * и отвечает снимком состояния. Снимки передаются как NBT: формат один для окна управления и карты.
 */
public final class Payloads {
    private Payloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> of(String name) {
        return new CustomPacketPayload.Type<>(LordsOutpost.id(name));
    }

    /** Сервер -> клиент: открыть окно управления (tab) со снимком аванпоста. */
    public record OpenOutpost(int tab, CompoundTag data) implements CustomPacketPayload {
        public static final Type<OpenOutpost> TYPE = of("open_outpost");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenOutpost> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenOutpost::tab, ByteBufCodecs.TRUSTED_COMPOUND_TAG, OpenOutpost::data, OpenOutpost::new);

        @Override
        public Type<OpenOutpost> type() {
            return TYPE;
        }
    }

    /** Сервер -> клиент: свежий снимок аванпоста для уже открытого окна. */
    public record OutpostData(CompoundTag data) implements CustomPacketPayload {
        public static final Type<OutpostData> TYPE = of("outpost_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, OutpostData> CODEC = StreamCodec.composite(
                ByteBufCodecs.TRUSTED_COMPOUND_TAG, OutpostData::data, OutpostData::new);

        @Override
        public Type<OutpostData> type() {
            return TYPE;
        }
    }

    /** Сервер -> клиент: метки карты (форты, слухи, патрули, караваны) и права рядом стоящих фортов. */
    public record MapData(CompoundTag data) implements CustomPacketPayload {
        public static final Type<MapData> TYPE = of("map_data");
        public static final StreamCodec<RegistryFriendlyByteBuf, MapData> CODEC = StreamCodec.composite(
                ByteBufCodecs.TRUSTED_COMPOUND_TAG, MapData::data, MapData::new);

        @Override
        public Type<MapData> type() {
            return TYPE;
        }
    }

    /** Сервер -> клиент: баннер «Найден Аванпост». */
    public record Discovery(int skulls, int x, int z) implements CustomPacketPayload {
        public static final Type<Discovery> TYPE = of("discovery");
        public static final StreamCodec<RegistryFriendlyByteBuf, Discovery> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Discovery::skulls, ByteBufCodecs.INT, Discovery::x, ByteBufCodecs.INT, Discovery::z, Discovery::new);

        @Override
        public Type<Discovery> type() {
            return TYPE;
        }
    }

    /** Сервер -> клиент: плитка карты биомов для ещё не разведанной местности (64x64 точки на регион). */
    public record BiomeTile(int rx, int rz, byte[] colors) implements CustomPacketPayload {
        public static final Type<BiomeTile> TYPE = of("biome_tile");
        public static final StreamCodec<RegistryFriendlyByteBuf, BiomeTile> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, BiomeTile::rx, ByteBufCodecs.INT, BiomeTile::rz, ByteBufCodecs.byteArray(1 << 16), BiomeTile::colors, BiomeTile::new);

        @Override
        public Type<BiomeTile> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: действие с аванпостом (см. {@link Action}). */
    public record OutpostAction(long outpost, int action, CompoundTag args) implements CustomPacketPayload {
        public static final Type<OutpostAction> TYPE = of("action");
        public static final StreamCodec<RegistryFriendlyByteBuf, OutpostAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, OutpostAction::outpost, ByteBufCodecs.VAR_INT, OutpostAction::action,
                ByteBufCodecs.COMPOUND_TAG, OutpostAction::args, OutpostAction::new);

        @Override
        public Type<OutpostAction> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: карта открыта или обновляется; ответом придёт {@link MapData}. */
    public record MapRequest(boolean open) implements CustomPacketPayload {
        public static final Type<MapRequest> TYPE = of("map_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, MapRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, MapRequest::open, MapRequest::new);

        @Override
        public Type<MapRequest> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: нужна плитка биомов региона. */
    public record TileRequest(int rx, int rz) implements CustomPacketPayload {
        public static final Type<TileRequest> TYPE = of("tile_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, TileRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.INT, TileRequest::rx, ByteBufCodecs.INT, TileRequest::rz, TileRequest::new);

        @Override
        public Type<TileRequest> type() {
            return TYPE;
        }
    }

    /** Клиент -> сервер: открыть окно аванпоста (с карты или по клавише). 0 — свой аванпост. */
    public record OpenRequest(long outpost, int tab) implements CustomPacketPayload {
        public static final Type<OpenRequest> TYPE = of("open_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, OpenRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, OpenRequest::outpost, ByteBufCodecs.VAR_INT, OpenRequest::tab, OpenRequest::new);

        @Override
        public Type<OpenRequest> type() {
            return TYPE;
        }
    }
}
