package com.ad.avatarelements.network;

import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.minecraft.server.level.ServerPlayer;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public class SelectElementHandler {

    public static void handle(final SelectElementPayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;

            // قراءة بيانات العنصر الحالي بشكل آمن
            PlayerElementData data;
            try {
                data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
            } catch (Exception e) {
                data = new PlayerElementData();
            }

            String current = data.currentElement();

            // السماح بالاختيار فقط إذا لم يكن للاعب عنصر بعد
            if (current == null || current.isBlank() || current.equals("none")) {
                String chosenElement = payload.element();

                // تحقق من صحة العنصر
                if (!isValidElement(chosenElement)) {
                    player.sendSystemMessage(Component.literal("§c[AvatarMod] عنصر غير صالح!"));
                    return;
                }

                // حفظ البيانات على السيرفر (activeAbility = 1 افتراضياً)
                PlayerElementData newData = new PlayerElementData(
                    chosenElement, 100, 100, 0, 0, false, 0, 0, 0, 0, 0, 1);
                player.setData(ModAttachmentTypes.ELEMENT_DATA, newData);

                // مزامنة الكلاينت
                PacketDistributor.sendToPlayer(player,
                    new SyncElementPayload(chosenElement, 100, 100, 0, 0, false, 0, 0, 0, 0, 0, 1));

                // رسالة تأكيد
                String elementNameAr = getElementNameAr(chosenElement);
                player.sendSystemMessage(
                    Component.literal("§6✦ §e" + elementNameAr + " §6✦§r - §7لقد اخترت قدرتك!")
                );
            } else {
                // أرسل البيانات الحالية للكلاينت على أي حال (لضمان المزامنة)
                PacketDistributor.sendToPlayer(player, new SyncElementPayload(
                    current, data.stamina(), data.maxStamina(), data.abilityCooldown(), data.flightTicks(),
                    data.isCharging(), data.chargeTicks(), data.idleTicks(), data.idlePhase(),
                    data.specialAbilityTicks(), data.specialAbilityType(), data.activeAbility()));

                player.sendSystemMessage(
                    Component.literal("§c[AvatarMod] §7اخترت مسبقاً: §e" + getElementNameAr(current))
                );
            }
        });
    }

    private static boolean isValidElement(String element) {
        return element != null && (
            element.equals("fire")  ||
            element.equals("water") ||
            element.equals("earth") ||
            element.equals("air")   ||
            element.equals("light") ||
            element.equals("dark")
        );
    }

    private static String getElementNameAr(String element) {
        return switch (element) {
            case "fire"  -> "النار 🔥";
            case "water" -> "الماء 💧";
            case "earth" -> "الأرض ⛰";
            case "air"   -> "الهواء 🌪";
            case "light" -> "النور ✨";
            case "dark"  -> "الظلام 🌑";
            default      -> element;
        };
    }
}
