package com.ad.avatarelements.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.ad.avatarelements.AbilityManager;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;

/**
 * Server-side handler لضربات Combo باليد العارية.
 * يُطلق هجوماً عنصرياً خفيفاً مع منطق تراكم الـ Combo.
 */
public class UseComboHitHandler {

    public static void handle(final UseComboHitPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            PlayerElementData data;
            try {
                data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
            } catch (Exception e) { return; }

            if (data.currentElement().equals("none")) return;

            // التأكد أن اللاعب يمسك بيده الفارغة
            if (!player.getMainHandItem().isEmpty()) return;

            AbilityManager.handleComboHit(player, data);
        });
    }
}
