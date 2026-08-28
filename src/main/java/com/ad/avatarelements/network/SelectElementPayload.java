package com.ad.avatarelements.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import com.ad.avatarelements.AvatarElements;

public record SelectElementPayload(String element) implements CustomPacketPayload {
    public static final Type<SelectElementPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "select_element"));

    public static final StreamCodec<FriendlyByteBuf, SelectElementPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SelectElementPayload::element,
            SelectElementPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
