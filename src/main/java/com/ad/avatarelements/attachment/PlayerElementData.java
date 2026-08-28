package com.ad.avatarelements.attachment;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record PlayerElementData(
    String currentElement,
    int stamina,
    int maxStamina,
    int abilityCooldown,
    int flightTicks,
    boolean isCharging,
    int chargeTicks,
    int idleTicks,
    int idlePhase,
    int specialAbilityTicks,
    int specialAbilityType,
    int activeAbility          // ← المهارة النشطة (1-4) المختارة عبر الشاشة
) {
    public static final Codec<PlayerElementData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("currentElement", "none").forGetter(PlayerElementData::currentElement),
            Codec.INT.optionalFieldOf("stamina", 100).forGetter(PlayerElementData::stamina),
            Codec.INT.optionalFieldOf("maxStamina", 100).forGetter(PlayerElementData::maxStamina),
            Codec.INT.optionalFieldOf("abilityCooldown", 0).forGetter(PlayerElementData::abilityCooldown),
            Codec.INT.optionalFieldOf("flightTicks", 0).forGetter(PlayerElementData::flightTicks),
            Codec.BOOL.optionalFieldOf("isCharging", false).forGetter(PlayerElementData::isCharging),
            Codec.INT.optionalFieldOf("chargeTicks", 0).forGetter(PlayerElementData::chargeTicks),
            Codec.INT.optionalFieldOf("idleTicks", 0).forGetter(PlayerElementData::idleTicks),
            Codec.INT.optionalFieldOf("idlePhase", 0).forGetter(PlayerElementData::idlePhase),
            Codec.INT.optionalFieldOf("specialAbilityTicks", 0).forGetter(PlayerElementData::specialAbilityTicks),
            Codec.INT.optionalFieldOf("specialAbilityType", 0).forGetter(PlayerElementData::specialAbilityType),
            Codec.INT.optionalFieldOf("activeAbility", 1).forGetter(PlayerElementData::activeAbility)
    ).apply(instance, PlayerElementData::new));

    /** مُنشئ الحالة الافتراضية */
    public PlayerElementData() {
        this("none", 100, 100, 0, 0, false, 0, 0, 0, 0, 0, 1);
    }

    /** مُنشئ توافقي (11 حقل) — يحافظ على activeAbility = 1 */
    public PlayerElementData(String currentElement, int stamina, int maxStamina, int abilityCooldown,
                              int flightTicks, boolean isCharging, int chargeTicks, int idleTicks,
                              int idlePhase, int specialAbilityTicks, int specialAbilityType) {
        this(currentElement, stamina, maxStamina, abilityCooldown, flightTicks, isCharging,
             chargeTicks, idleTicks, idlePhase, specialAbilityTicks, specialAbilityType, 1);
    }

    /** مُنشئ توافقي قديم (8 حقول) */
    public PlayerElementData(String currentElement, int stamina, int maxStamina, int abilityCooldown,
                              int flightTicks, boolean isCharging, int chargeTicks, int idleTicks) {
        this(currentElement, stamina, maxStamina, abilityCooldown, flightTicks, isCharging,
             chargeTicks, idleTicks, 0, 0, 0, 1);
    }

    /** منشئ مساعد يحافظ على activeAbility من البيانات القديمة */
    public static PlayerElementData withActiveAbility(PlayerElementData base, int newActiveAbility) {
        return new PlayerElementData(
            base.currentElement(), base.stamina(), base.maxStamina(), base.abilityCooldown(),
            base.flightTicks(), base.isCharging(), base.chargeTicks(), base.idleTicks(),
            base.idlePhase(), base.specialAbilityTicks(), base.specialAbilityType(),
            newActiveAbility
        );
    }
}
