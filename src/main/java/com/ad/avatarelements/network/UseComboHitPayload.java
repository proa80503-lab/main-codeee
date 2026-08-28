package com.ad.avatarelements.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.ad.avatarelements.AvatarElements;

/**
 * Client → Server: ضربة يد عارية للـ Combo
 */
public record UseComboHitPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UseComboHitPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "use_combo_hit"));

    public static final StreamCodec<FriendlyByteBuf, UseComboHitPayload> STREAM_CODEC =
        StreamCodec.of((buf, pkt) -> {}, buf -> new UseComboHitPayload());

    @Override
    public CustomPacketPayload.Type<UseComboHitPayload> type() { return TYPE; }
}
