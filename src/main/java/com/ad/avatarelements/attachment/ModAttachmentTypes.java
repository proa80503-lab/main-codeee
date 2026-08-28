package com.ad.avatarelements.attachment;

import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import com.ad.avatarelements.AvatarElements;

public class ModAttachmentTypes {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, AvatarElements.MODID);

    public static final Supplier<AttachmentType<PlayerElementData>> ELEMENT_DATA = ATTACHMENT_TYPES.register("element_data",
            () -> AttachmentType.builder(PlayerElementData::new)
                    .serialize(PlayerElementData.CODEC)
                    .copyOnDeath()
                    .build());
}
