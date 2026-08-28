package com.ad.avatarelements.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import com.ad.avatarelements.AvatarElements;

/**
 * Client → Server: اللاعب اختار مهارة نشطة (1-4) من شاشة Shift.
 */
public record SetActiveAbilityPayload(int slot) implements CustomPacketPayload {

    public static final Type<SetActiveAbilityPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "set_active_ability"));

    public static final StreamCodec<FriendlyByteBuf, SetActiveAbilityPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, pkt) -> buf.writeInt(pkt.slot()),
            buf -> new SetActiveAbilityPayload(buf.readInt())
        );

    @Override
    public Type<SetActiveAbilityPayload> type() { return TYPE; }
}
