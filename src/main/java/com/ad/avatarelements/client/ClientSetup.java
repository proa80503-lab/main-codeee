package com.ad.avatarelements.client;

import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import com.ad.avatarelements.AvatarElements;

@EventBusSubscriber(modid = AvatarElements.MODID, value = Dist.CLIENT)
public class ClientSetup {

    public static final KeyMapping OPEN_SELECTION_KEY = new KeyMapping(
            "key.avatarelements.open_selection",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_K,
            "key.categories.avatarelements"
    );

    /** مفتاح القوة الخاصة الثابتة - زر ] (د) */
    public static final KeyMapping USE_ABILITY_KEY = new KeyMapping(
            "key.avatarelements.use_ability",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET,
            "key.categories.avatarelements"
    );

    /** مفتاح المهارة النشطة المختارة - زر [ (ج) */
    public static final KeyMapping USE_SPECIAL_ABILITY_KEY = new KeyMapping(
            "key.avatarelements.use_special_ability",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET,
            "key.categories.avatarelements"
    );

    @SubscribeEvent
    public static void registerKeyBindings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SELECTION_KEY);
        event.register(USE_ABILITY_KEY);
        event.register(USE_SPECIAL_ABILITY_KEY);
    }
}
