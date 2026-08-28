package com.ad.avatarelements;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = AvatarElements.MODID, value = Dist.CLIENT)
public class AvatarElementsClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        AvatarElements.LOGGER.info("Avatar Elements Client Setup Complete!");
        // ملاحظة: ElementHudRenderer مسجّل تلقائياً عبر @EventBusSubscriber
    }
}
