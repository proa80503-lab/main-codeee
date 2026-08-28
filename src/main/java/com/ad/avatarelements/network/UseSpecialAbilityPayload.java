package com.ad.avatarelements.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import com.ad.avatarelements.AvatarElements;

/**
 * حزمة شبكة لتفعيل القوة الخاصة (زر [)
 */
public record UseSpecialAbilityPayload(boolean pressed) implements CustomPacketPayload {
    public static final Type<UseSpecialAbilityPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "use_special_ability"));

    public static final StreamCodec<FriendlyByteBuf, UseSpecialAbilityPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, UseSpecialAbilityPayload::pressed,
            UseSpecialAbilityPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
