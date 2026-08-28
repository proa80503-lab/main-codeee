package com.ad.avatarelements.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.client.Minecraft;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;

/**
 * معالج يُطبَّق على جانب الكلاينت لتحديث بيانات العنصر المخزنة محلياً.
 */
public class SyncElementHandler {
    public static void handle(final SyncElementPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            net.minecraft.client.player.LocalPlayer player = mc.player;
            if (player == null) return;

            // تحديث بيانات اللاعب محلياً على الكلاينت (مع activeAbility)
            PlayerElementData newData = new PlayerElementData(
                payload.element(),
                payload.stamina(),
                payload.maxStamina(),
                payload.abilityCooldown(),
                payload.flightTicks(),
                payload.isCharging(),
                payload.chargeTicks(),
                payload.idleTicks(),
                payload.idlePhase(),
                payload.specialAbilityTicks(),
                payload.specialAbilityType(),
                payload.activeAbility()
            );
            player.setData(ModAttachmentTypes.ELEMENT_DATA, newData);
        });
    }
}
