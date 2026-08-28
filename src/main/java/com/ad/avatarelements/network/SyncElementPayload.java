package com.ad.avatarelements.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import com.ad.avatarelements.AvatarElements;

/**
 * يُرسل من السيرفر إلى الكلاينت لمزامنة بيانات عنصر اللاعب.
 * تم إضافة حقل activeAbility (1-4) لنظام اختيار المهارة.
 */
public record SyncElementPayload(
    String element,
    int stamina, int maxStamina, int abilityCooldown, int flightTicks,
    boolean isCharging,
    int chargeTicks, int idleTicks, int idlePhase,
    int specialAbilityTicks, int specialAbilityType,
    int activeAbility
) implements CustomPacketPayload {

    public static final Type<SyncElementPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "sync_element"));

    public static final StreamCodec<FriendlyByteBuf, SyncElementPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.element());
                buf.writeInt(p.stamina());
                buf.writeInt(p.maxStamina());
                buf.writeInt(p.abilityCooldown());
                buf.writeInt(p.flightTicks());
                buf.writeBoolean(p.isCharging());
                buf.writeInt(p.chargeTicks());
                buf.writeInt(p.idleTicks());
                buf.writeInt(p.idlePhase());
                buf.writeInt(p.specialAbilityTicks());
                buf.writeInt(p.specialAbilityType());
                buf.writeInt(p.activeAbility());
            },
            buf -> new SyncElementPayload(
                buf.readUtf(),
                buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readBoolean(),
                buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readInt(), buf.readInt(),
                buf.readInt()
            )
    );

    /** Backward-compatible 11-field constructor (activeAbility defaults to 1) */
    public SyncElementPayload(String element, int stamina, int maxStamina, int abilityCooldown,
                               int flightTicks, boolean isCharging, int chargeTicks, int idleTicks,
                               int idlePhase, int specialAbilityTicks, int specialAbilityType) {
        this(element, stamina, maxStamina, abilityCooldown, flightTicks, isCharging,
             chargeTicks, idleTicks, idlePhase, specialAbilityTicks, specialAbilityType, 1);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
