package com.ad.avatarelements.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;

/**
 * Server-side handler: يُحدِّث activeAbility في بيانات اللاعب ويزامن للكلاينت.
 */
public class SetActiveAbilityHandler {

    public static void handle(final SetActiveAbilityPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            int slot = payload.slot();
            if (slot < 1 || slot > 4) return; // حماية من القيم الغير صالحة

            PlayerElementData data;
            try {
                data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
            } catch (Exception e) {
                return;
            }

            if (data.currentElement().equals("none")) return;

            // تحديث المهارة النشطة مع الحفاظ على باقي الحقول
            PlayerElementData updated = PlayerElementData.withActiveAbility(data, slot);
            player.setData(ModAttachmentTypes.ELEMENT_DATA, updated);

            // مزامنة فورية مع الكلاينت
            PacketDistributor.sendToPlayer(player, new SyncElementPayload(
                updated.currentElement(), updated.stamina(), updated.maxStamina(),
                updated.abilityCooldown(), updated.flightTicks(), updated.isCharging(),
                updated.chargeTicks(), updated.idleTicks(), updated.idlePhase(),
                updated.specialAbilityTicks(), updated.specialAbilityType(),
                updated.activeAbility()
            ));

            String[] slotNames = {"", "🔥 المهارة الأولى", "⚡ المهارة الثانية", "🌀 المهارة الثالثة", "💥 المهارة الرابعة"};
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "§e✦ تم تحديد: §f" + (slot < slotNames.length ? slotNames[slot] : "") + " §e(فتحة " + slot + ")"
            ));
        });
    }
}
