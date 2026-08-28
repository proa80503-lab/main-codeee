package com.ad.avatarelements.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import com.ad.avatarelements.AbilityManager;

public class UseAbilityHandler {
    public static void handle(final UseAbilityPayload payload, final IPayloadContext context) {
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

            // تمرير حالة الضغط إلى مدير القدرات الخاصة (تم التبديل)
            AbilityManager.handleSpecialAbilityInput(player, data, payload.pressed());
        });
    }
}
