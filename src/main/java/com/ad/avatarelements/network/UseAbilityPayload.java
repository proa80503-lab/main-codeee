package com.ad.avatarelements.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import com.ad.avatarelements.AvatarElements;

public record UseAbilityPayload(boolean pressed) implements CustomPacketPayload {
    public static final Type<UseAbilityPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "use_ability"));

    public static final StreamCodec<FriendlyByteBuf, UseAbilityPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UseAbilityPayload::pressed,
            UseAbilityPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
