package com.ad.avatarelements;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.server.level.ServerPlayer;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import com.ad.avatarelements.network.SelectElementPayload;
import com.ad.avatarelements.network.SelectElementHandler;
import com.ad.avatarelements.network.SyncElementPayload;
import com.ad.avatarelements.network.SyncElementHandler;

@Mod(AvatarElements.MODID)
public class AvatarElements {
    public static final String MODID = "avatarelements";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AvatarElements(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);

        ModAttachmentTypes.ATTACHMENT_TYPES.register(modEventBus);

        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Avatar Elements Mod Setup Complete!");
    }

    /** تسجيل جميع حزم الشبكة */
    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(AvatarElements.MODID).versioned("1.0.0");

        // Client -> Server: اختيار العنصر
        registrar.playToServer(
            SelectElementPayload.TYPE,
            SelectElementPayload.STREAM_CODEC,
            SelectElementHandler::handle
        );

        // Client -> Server: استخدام القدرة (زر ])
        registrar.playToServer(
            com.ad.avatarelements.network.UseAbilityPayload.TYPE,
            com.ad.avatarelements.network.UseAbilityPayload.STREAM_CODEC,
            com.ad.avatarelements.network.UseAbilityHandler::handle
        );

        // Client -> Server: استخدام القوة الخاصة (زر [)
        registrar.playToServer(
            com.ad.avatarelements.network.UseSpecialAbilityPayload.TYPE,
            com.ad.avatarelements.network.UseSpecialAbilityPayload.STREAM_CODEC,
            com.ad.avatarelements.network.UseSpecialAbilityHandler::handle
        );

        // Client -> Server: تغيير المهارة النشطة عبر شاشة Shift
        registrar.playToServer(
            com.ad.avatarelements.network.SetActiveAbilityPayload.TYPE,
            com.ad.avatarelements.network.SetActiveAbilityPayload.STREAM_CODEC,
            com.ad.avatarelements.network.SetActiveAbilityHandler::handle
        );

        // Client -> Server: ضربة Combo باليد العارية
        registrar.playToServer(
            com.ad.avatarelements.network.UseComboHitPayload.TYPE,
            com.ad.avatarelements.network.UseComboHitPayload.STREAM_CODEC,
            com.ad.avatarelements.network.UseComboHitHandler::handle
        );

        // Server -> Client: مزامنة بيانات العنصر
        registrar.playToClient(
            SyncElementPayload.TYPE,
            SyncElementPayload.STREAM_CODEC,
            SyncElementHandler::handle
        );
    }

    /** عند دخول لاعب للعالم، أرسل بياناته من السيرفر إلى الكلاينت */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerElementData data;
            try {
                data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
            } catch (Exception e) {
                data = new PlayerElementData();
            }
            PacketDistributor.sendToPlayer(player,
                new SyncElementPayload(data.currentElement(), data.stamina(), data.maxStamina(),
                    data.abilityCooldown(), data.flightTicks(), data.isCharging(),
                    data.chargeTicks(), data.idleTicks(), data.idlePhase(),
                    data.specialAbilityTicks(), data.specialAbilityType(),
                    data.activeAbility())
            );
            LOGGER.debug("Synced element data to player: {} -> {}", player.getName().getString(), data.currentElement());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Avatar Elements Server Starting...");
    }
}
