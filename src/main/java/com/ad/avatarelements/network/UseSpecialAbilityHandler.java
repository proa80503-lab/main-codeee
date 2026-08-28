package com.ad.avatarelements.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import com.ad.avatarelements.AbilityManager;

/**
 * معالج القوة الخاصة في السيرفر
 */
public class UseSpecialAbilityHandler {
    public static void handle(final UseSpecialAbilityPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            PlayerElementData data;
            try {
                data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
            } catch (Exception e) {
                return;
            }

            String element = data.currentElement();
            if (element == null || element.equals("none")) return;

            // تمرير إلى مدير القدرات العامة (تم التبديل لتصبح المهارة المختارة في زر ج)
            AbilityManager.handleAbilityInput(player, data, payload.pressed());
        });
    }
}
