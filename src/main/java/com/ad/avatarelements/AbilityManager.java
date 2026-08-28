package com.ad.avatarelements;

import net.minecraft.server.level.ServerPlayer;
import com.ad.avatarelements.attachment.PlayerElementData;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import com.ad.avatarelements.network.SyncElementPayload;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

@EventBusSubscriber(modid = AvatarElements.MODID)
public class AbilityManager {

    public static final java.util.Map<java.util.UUID, Integer> abilityStates = new java.util.HashMap<>();
    public static final java.util.Map<java.util.UUID, java.util.UUID> shadowTargets = new java.util.HashMap<>();

    // ====== كاش Earth Uplift لمنع تغيير الإحداثيات أثناء تنفيذ القدرة ======
    // int[] = { centerX, centerZ, baseY }
    private static final java.util.Map<java.util.UUID, int[]> earthUpliftCache = new java.util.HashMap<>();

    // ====== Ultimate Domain & Allied Summons tracking ======
    public static record UltimateDomainData(int baseType, net.minecraft.world.phys.Vec3 center, long expireTime) {}
    public static final java.util.Map<java.util.UUID, UltimateDomainData> ultimateDomains = new java.util.concurrent.ConcurrentHashMap<>();
    public static final java.util.Map<java.util.UUID, Long> summonedAllies = new java.util.concurrent.ConcurrentHashMap<>();

    // ====== Combo Tracker ======
    // int[] = { comboCount, lastHitTick }
    public static final java.util.Map<java.util.UUID, int[]> comboTracker = new java.util.HashMap<>();
    public static final int COMBO_WINDOW_TICKS = 40; // 2 ثوانٍ بين الضربات لمواصلة الـ Combo

    // ====== Shield Tracking ======
    public static record ShieldData(String element, int remainingTicks) {}
    public static final java.util.Map<java.util.UUID, ShieldData> activeShields = new java.util.concurrent.ConcurrentHashMap<>();

    // ====== Castle Tracking ======
    public static record GrandCastleData(BlockPos center, String element, int radius, long creationTime) {}
    public static final java.util.Map<java.util.UUID, GrandCastleData> activeCastles = new java.util.concurrent.ConcurrentHashMap<>();

    // ====== Teleport Tracking (نظام الانتقال الجماعي والتصادم) ======
    public static record TeleportProcess(String element, int ticks, BlockPos destination) {}
    public static final java.util.Map<java.util.UUID, TeleportProcess> activeTeleports = new java.util.concurrent.ConcurrentHashMap<>();

    // ====== ثوابت النظام ======
    private static final int MAX_CHARGE_TICKS = 60;
    private static final int IDLE_THRESHOLD_TICKS = 600;     // 30 ثانية
    private static final int IDLE_CINEMATIC_DURATION = 120;   // مدة حركة الخمول (6 ثوانٍ)
    private static final int COOLDOWN_TICKS = 20 * 12;
    private static final int SPECIAL_COOLDOWN_TICKS = 20 * 15;

    // =====================================================================
    //  زر ] - الهجوم المشحون
    // =====================================================================
    public static void handleAbilityInput(ServerPlayer player, PlayerElementData data, boolean pressed) {
        int activeSlot = data.activeAbility(); // 1-4

        if (activeSlot == 1) {
            // ── المهارة الأولى: الهجوم المشحون (السلوك القديم) ──
            if (pressed) {
                if (data.abilityCooldown() <= 0 && data.stamina() >= 20) {
                    updatePlayerData(player, new PlayerElementData(
                        data.currentElement(), data.stamina(), data.maxStamina(),
                        data.abilityCooldown(), data.flightTicks(), true, 0, 0, 0,
                        data.specialAbilityTicks(), data.specialAbilityType(),
                        activeSlot));
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6⚡ جاري تجميع الطاقة..."));
                } else if (data.abilityCooldown() > 0) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cانتظر §e" + (data.abilityCooldown()/20) + " §cثانية."));
                }
            } else {
                if (data.isCharging()) {
                    performChargedAttack(player, data);
                }
            }
        } else if (pressed) {
            // ── المهارات 2/3/4: تُطلق فوراً بدون شحن ──
            if (data.abilityCooldown() > 0) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cانتظر §e" + (data.abilityCooldown()/20) + " §cثانية."));
                return;
            }
            int staminaNeeded = switch (activeSlot) {
                case 2 -> 30;
                case 3 -> 25;
                case 4 -> 40;
                default -> 30;
            };
            if (data.stamina() < staminaNeeded) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cلا تملك طاقة كافية!"));
                return;
            }
            performInstantAbility(player, data, activeSlot);
        }
    }

    // =====================================================================
    //  تشغيل المهارات الفورية (Slots 2/3/4)
    // =====================================================================
    private static void performInstantAbility(ServerPlayer player, PlayerElementData data, int slot) {
        String element = data.currentElement();
        int staminaCost = switch (slot) { case 2 -> 30; case 3 -> 25; case 4 -> 40; default -> 30; };

        // تطبيق المهارة
        if (slot == 2) {
            // المهارة الثانية = نفس القوة الخاصة (] key)
            handleSpecialAbilityInput(player, data, true);
            return;
        } else if (slot == 3) {
            performAbility3(player, data, element);
        } else if (slot == 4) {
            performAbility4(player, data, element);
        }

        updatePlayerData(player, new PlayerElementData(
            element, Math.max(0, data.stamina() - staminaCost), data.maxStamina(),
            COOLDOWN_TICKS, data.flightTicks(), false, 0, 0, 0,
            data.specialAbilityTicks(), data.specialAbilityType(),
            data.activeAbility()));
    }

    // =====================================================================
    //  المهارة الثالثة (Slot 3) — درع/جدار/شفاء/اندفاع
    // =====================================================================
    private static void performAbility3(ServerPlayer player, PlayerElementData data, String element) {
        // تفعيل الدرع المطور لمدة 15 ثانية (300 تيك)
        activeShields.put(player.getUUID(), new ShieldData(element, 300));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§l§e🛡️ تفعيل الدرع الروحي لـ " + element.toUpperCase() + "!"));
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.0f, 1.5f);
    }

    private static void tickActiveShields(ServerPlayer player) {
        ShieldData shield = activeShields.get(player.getUUID());
        if (shield == null) return;

        if (shield.remainingTicks() <= 0) {
            activeShields.remove(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7✦ تبدد الدرع الروحي."));
            return;
        }

        activeShields.put(player.getUUID(), new ShieldData(shield.element(), shield.remainingTicks() - 1));

        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position().add(0, 1, 0);
        String element = shield.element();
        int tick = shield.remainingTicks();

        // جزيئات الدرع الدوارة (بشكل جمالي)
        double radius = 2.5;
        for (int i = 0; i < 3; i++) {
            double angle = (tick * 0.15) + (i * Math.PI * 2 / 3);
            double px = pos.x + radius * Math.cos(angle);
            double pz = pos.z + radius * Math.sin(angle);
            double py = pos.y + Math.sin(tick * 0.1 + i) * 0.5;

            switch (element) {
                case "fire" -> {
                    level.sendParticles(ParticleTypes.FLAME, px, py, pz, 3, 0.1, 0.1, 0.1, 0.02);
                    level.sendParticles(ParticleTypes.LAVA, px, py, pz, 1, 0, 0, 0, 0);
                }
                case "water" -> {
                    level.sendParticles(ParticleTypes.SPLASH, px, py, pz, 5, 0.1, 0.1, 0.1, 0.05);
                    level.sendParticles(ParticleTypes.SNOWFLAKE, px, py, pz, 2, 0.1, 0.1, 0.1, 0.02);
                }
                case "earth" -> {
                    level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py, pz, 2, 0.1, 0.1, 0.1, 0.02);
                    BlockState s = level.getBlockState(player.blockPosition().below());
                    if (!s.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), px, py, pz, 2, 0.1, 0.1, 0.1, 0.05);
                }
                case "air" -> {
                    level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 3, 0.1, 0.1, 0.1, 0.02);
                    level.sendParticles(ParticleTypes.POOF, px, py, pz, 1, 0, 0, 0, 0);
                }
                case "light" -> {
                    level.sendParticles(ParticleTypes.END_ROD, px, py, pz, 2, 0.1, 0.1, 0.1, 0.02);
                    level.sendParticles(ParticleTypes.INSTANT_EFFECT, px, py, pz, 1, 0, 0, 0, 0);
                }
                case "dark" -> {
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, px, py, pz, 4, 0.1, 0.1, 0.1, 0.02);
                    level.sendParticles(ParticleTypes.SQUID_INK, px, py, pz, 1, 0, 0, 0, 0);
                }
            }
        }

        // تأثيرات الدرع على من يقترب (كل ثانية)
        AABB area = player.getBoundingBox().inflate(radius + 1.0);
        
        // --- ميزة جديدة: إيقاف السهام والمقذوفات (للدرع النوراني وبقية الدروع) ---
        for (Entity projectile : level.getEntitiesOfClass(Entity.class, area)) {
            if (projectile instanceof net.minecraft.world.entity.projectile.Projectile p) {
                if (p.getOwner() != player) {
                    level.sendParticles(ParticleTypes.FLASH, p.getX(), p.getY(), p.getZ(), 1, 0, 0, 0, 0);
                    level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0f, 1.5f);
                    p.discard(); // تدمير المقذوف فور لمس الدرع
                }
            }
        }

        if (tick % 20 == 0) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (e == player || e.getTags().contains("avatar_ally")) continue;

                switch (element) {
                    case "fire" -> {
                        e.igniteForSeconds(5);
                        e.hurt(level.damageSources().onFire(), 4.0f);
                    }
                    case "water" -> {
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 4)); // تجميد (بطء شديد)
                        e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 1));
                    }
                    case "earth" -> {
                        e.setDeltaMovement(0, 0.5, 0); // رفع خفيف لتعطيل الحركة
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2));
                    }
                    case "air" -> {
                        Vec3 knockback = e.position().subtract(player.position()).normalize().scale(1.5);
                        e.setDeltaMovement(knockback.x, 0.3, knockback.z);
                    }
                    case "light" -> {
                        e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0));
                        player.heal(1.0f); // شفاء اللاعب عند لمس الأعداء للدرع
                    }
                    case "dark" -> {
                        e.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 1));
                    }
                }
                e.hurtMarked = true;
            }
        }
    }


    private static void tickTeleportProcess(ServerPlayer player) {
        if (!player.isAlive()) {
            activeTeleports.remove(player.getUUID());
            return;
        }
        TeleportProcess process = activeTeleports.get(player.getUUID());
        if (process == null) return;

        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        int ticks = process.ticks();
        int maxTicks = 40; // ثانيتان من تجميع الطاقة

        if (ticks >= maxTicks) {
            // تنفيذ الانتقال النهائي للاعب وكل من حوله
            BlockPos dest = process.destination();
            AABB friendsArea = player.getBoundingBox().inflate(6.0);
            
            // صوت انفجار طاقة عند الانتقال
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 2.0f, 0.5f);
            
            for (Entity e : level.getEntitiesOfClass(Entity.class, friendsArea)) {
                if (e instanceof LivingEntity le) {
                    // تأثير بصري عند وجهة الوصول
                    le.teleportTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
                    if (le instanceof ServerPlayer sp) {
                        sp.sendSystemMessage(net.minecraft.network.chat.Component.literal("§l§d⚡ تم الانتقال الجماعي بنجاح إلى المعقل!"));
                        // خصم الطاقة من القائد فقط (الذي فعل المهارة)
                        if (sp == player) {
                            PlayerElementData data = sp.getData(ModAttachmentTypes.ELEMENT_DATA);
                            updatePlayerData(sp, new PlayerElementData(process.element(), Math.max(0, data.stamina() - 20), data.maxStamina(), COOLDOWN_TICKS, data.flightTicks(), false, 0, 0, 0, 0, 0, data.activeAbility()));
                        }
                    }
                }
            }
            activeTeleports.remove(player.getUUID());
            return;
        }

        // تأثيرات أثناء تجميع الطاقة: الارتفاع عن الأرض وحماية هالة
        AABB friendsArea = player.getBoundingBox().inflate(6.0);
        for (Entity e : level.getEntitiesOfClass(Entity.class, friendsArea)) {
            if (e instanceof LivingEntity le) {
                // حماية إضافية أثناء الانتقال
                le.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 10, 2, false, false, false));
                
                // رفع الجميع ببطء (Levitation effect style)
                le.setDeltaMovement(le.getDeltaMovement().x, 0.1, le.getDeltaMovement().z);
                le.hurtMarked = true;
                
                // جزيئات الحماية لكل شخص
                level.sendParticles(ParticleTypes.END_ROD, le.getX(), le.getY() + 1, le.getZ(), 3, 0.3, 0.5, 0.3, 0.05);
            }
        }

        // جزيئات طاقة دوارة حول المجموعة
        double radius = 4.0;
        double angle = ticks * 0.3;
        level.sendParticles(ParticleTypes.WITCH, pos.x + radius * Math.cos(angle), pos.y + 1, pos.z + radius * Math.sin(angle), 10, 0.2, 0.2, 0.2, 0.02);
        level.sendParticles(ParticleTypes.WITCH, pos.x - radius * Math.cos(angle), pos.y + 1, pos.z - radius * Math.sin(angle), 10, 0.2, 0.2, 0.2, 0.02);

        // تحديث التيكس
        activeTeleports.put(player.getUUID(), new TeleportProcess(process.element(), ticks + 1, process.destination()));
    }

    private static void tickCastleEffects(ServerPlayer player) {
        // تم تعطيل آلية الصعود التلقائي للنور — الانتقال يتم عبر Slot 4 فقط
        // (كانت تسبب حلقة انتقال لانهائية)
    }



    // =====================================================================
    //  المهارة الرابعة (Slot 4) — Ultimate
    // =====================================================================
    private static void performAbility4(ServerPlayer player, PlayerElementData data, String element) {
        GrandCastleData castle = activeCastles.get(player.getUUID());
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();

        // 1. التحقق من النطاق: منع بناء قلعة قريبة من قلعة أشخاص آخرين (نطاق 200 بلوكة)
        if (castle == null || (castle != null && !castle.element().equals(element))) {
            BlockPos currentPos = player.blockPosition();
            for (java.util.Map.Entry<java.util.UUID, GrandCastleData> entry : activeCastles.entrySet()) {
                if (!entry.getKey().equals(player.getUUID()) && entry.getValue().center().closerThan(currentPos, 200)) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cهذا النطاق تابع لقلعة أخرى!"));
                    return;
                }
            }
        }

        // 2. إدارة تبديل العنصر: إذا كان هناك قلعة قديمة لعنصر مختلف، اسمح ببناء واحدة جديدة
        if (castle != null && !castle.element().equals(element)) {
            if (data.stamina() >= 100) {
                activeCastles.remove(player.getUUID());
                castle = null;
            }
        }

        // 2. بناء القلعة (تحتاج 100 طاقة)
        if (castle == null) {
            if (data.stamina() >= 100) {
                BlockPos base = player.blockPosition();
                activeCastles.put(player.getUUID(), new GrandCastleData(base, element, 100, System.currentTimeMillis()));
                buildGrandCastle(player, element);
                // خصم كل الطاقة (المهارة العظمى)
                updatePlayerData(player, new PlayerElementData(element, 0, data.maxStamina(), COOLDOWN_TICKS * 3, data.flightTicks(), false, 0, 0, 0, 0, 0, data.activeAbility()));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§l§b✨ تم بناء قلعة الـ " + element.toUpperCase() + " بنجاح!"));
                return;
            } else {
                // مهارة ثانوية إذا كانت الطاقة أقل من 100
                executeSecondaryAbility4(player, level, pos, element);
                return;
            }
        }

        // 3. الانتقال للقلعة الموجودة (تحتاج 20 طاقة فقط)
        if (data.stamina() < 20) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cتحتاج 20 طاقة للانتقال!"));
            return;
        }

        // فحص تضارب القوى (Clash) إذا حاول شخصان التفعيل معاً في نفس المكان
        for (java.util.UUID otherId : activeTeleports.keySet()) {
            ServerPlayer other = level.getServer().getPlayerList().getPlayer(otherId);
            if (other != null && other != player && other.level() == player.level() && other.distanceTo(player) < 8) {
                handleTeleportClash(player, other);
                activeTeleports.remove(otherId);
                return;
            }
        }

        // بدء عملية الانتقال الجماعي (Delayed Teleport)
        activeTeleports.put(player.getUUID(), new TeleportProcess(element, 1, castle.center().above(1)));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§l§d✨ جاري تجميع طاقة الانتقال... ابقَ بجانب أصدقائك!"));
    }

    private static void handleTeleportClash(ServerPlayer p1, ServerPlayer p2) {
        ServerLevel level = p1.serverLevel();
        Vec3 mid = p1.position().add(p2.position()).scale(0.5);
        
        level.playSound(null, mid.x, mid.y, mid.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.5f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, mid.x, mid.y + 1, mid.z, 5, 0.5, 0.5, 0.5, 0.1);
        level.sendParticles(ParticleTypes.SONIC_BOOM, mid.x, mid.y + 1, mid.z, 2, 0, 0, 0, 0);

        Vec3 v1 = p1.position().subtract(mid).normalize().scale(2.0).add(0, 0.5, 0);
        Vec3 v2 = p2.position().subtract(mid).normalize().scale(2.0).add(0, 0.5, 0);
        
        p1.setDeltaMovement(v1);
        p2.setDeltaMovement(v2);
        p1.hurtMarked = true;
        p2.hurtMarked = true;
        
        p1.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c⚠️ حدث تضارب في القوى! تدافع طاقة كليكما!"));
        p2.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c⚠️ حدث تضارب في القوى! تدافع طاقة كليكما!"));
    }

    private static void executeSecondaryAbility4(ServerPlayer player, ServerLevel level, Vec3 pos, String element) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§eتحتاج 100 طاقة لبناء القلعة! تفعيل مهارة ثانوية..."));
        switch (element) {
            case "fire" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2.0f, 0.5f);
                Vec3 look = player.getLookAngle();
                for (int step = 0; step < 12; step++) {
                    Vec3 sp = pos.add(look.scale(step * 1.5));
                    level.sendParticles(ParticleTypes.FLAME, sp.x, sp.y + 1, sp.z, 8, 0.4, 0.3, 0.4, 0.05);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(sp.x-2, sp.y-1, sp.z-2, sp.x+2, sp.y+3, sp.z+2))) {
                        if (e != player && e.isAlive()) { e.igniteForSeconds(8); e.hurt(level.damageSources().playerAttack(player), 10.0f); }
                    }
                }
                player.setDeltaMovement(look.scale(2.5));
                player.hurtMarked = true;
            }
            case "water" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 2.0f, 1.5f);
                player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 10, 2, false, false, false));
            }
            case "earth" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 2.0f, 0.3f);
                Vec3 look = player.getLookAngle();
                for (double t = 2.0; t < 20; t += 0.7) {
                    Vec3 tp = player.getEyePosition().add(look.scale(t));
                    level.sendParticles(ParticleTypes.LARGE_SMOKE, tp.x, tp.y, tp.z, 3, 0.3, 0.2, 0.3, 0.05);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(tp.x-1.5, tp.y-1.5, tp.z-1.5, tp.x+1.5, tp.y+1.5, tp.z+1.5))) {
                        if (e != player && e.isAlive()) { e.hurt(level.damageSources().playerAttack(player), 20.0f); e.hurtMarked = true; }
                    }
                }
            }
            case "air" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 2.0f, 1.8f);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(15.0))) {
                    if (e != player && e.isAlive()) {
                        Vec3 push = e.position().subtract(pos).normalize().scale(4.0);
                        e.setDeltaMovement(push.x, 1.5, push.z); e.hurtMarked = true;
                    }
                }
            }
            case "dark" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1.5f, 0.3f);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(15.0))) {
                    if (e != player && e.isAlive()) {
                        e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 100, 0));
                        e.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 1));
                    }
                }
            }
            case "light" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 2.0f, 1.5f);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(15.0))) {
                    if (e != player && e.isAlive()) e.hurt(level.damageSources().magic(), 25.0f);
                }
            }
        }
    }



    // =====================================================================
    //  نظام Combo باليد العارية
    // =====================================================================
    public static void handleComboHit(ServerPlayer player, PlayerElementData data) {
        java.util.UUID uid = player.getUUID();
        int[] combo = comboTracker.getOrDefault(uid, new int[]{0, 0});
        int comboCount = combo[0];
        int lastHitTick = combo[1];

        // إعادة تعيين الـ Combo إذا مضى وقت طويل
        if (player.tickCount - lastHitTick > COMBO_WINDOW_TICKS) {
            comboCount = 0;
        }
        comboCount++;
        comboTracker.put(uid, new int[]{ comboCount, player.tickCount });

        String element = data.currentElement();
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        Vec3 look = player.getLookAngle();

        // مضاعف القوة يزيد مع تراكم الـ Combo
        float mult = Math.min(1.0f + (comboCount - 1) * 0.4f, 2.8f);
        boolean isFinisher = (comboCount % 4 == 0);

        if (isFinisher) {
            // ضربة إنهاء (Finisher)
            performComboFinisher(player, data, element, mult, level, pos, look);
            comboTracker.put(uid, new int[]{ 0, player.tickCount }); // إعادة التعيين بعد الـ Finisher
        } else {
            // ضربة عادية
            performComboQuickAttack(player, element, comboCount, mult, level, pos, look);
        }
    }

    /** ضربة Combo سريعة (1-2-3) */
    private static void performComboQuickAttack(ServerPlayer player, String element, int comboNum,
                                                  float mult, ServerLevel level, Vec3 pos, Vec3 look) {
        float damage = 6.0f * mult;
        double range = 10.0;
        switch (element) {
            case "fire" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.0f, 1.2f + comboNum * 0.1f);
                Vec3 tip = player.getEyePosition();
                for (double t = 0; t < range; t += 0.5) {
                    Vec3 p = tip.add(look.scale(t));
                    level.sendParticles(ParticleTypes.FLAME, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.04);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p.x-0.8, p.y-0.8, p.z-0.8, p.x+0.8, p.y+0.8, p.z+0.8))) {
                        if (e != player && e.isAlive()) { e.igniteForSeconds(3); e.hurt(level.damageSources().playerAttack(player), damage); }
                    }
                }
            }
            case "water" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 1.0f, 1.5f);
                Vec3 tip = player.getEyePosition();
                for (double t = 0; t < range; t += 0.5) {
                    Vec3 p = tip.add(look.scale(t));
                    level.sendParticles(ParticleTypes.SPLASH, p.x, p.y, p.z, 3, 0.1, 0.1, 0.1, 0.05);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p.x-0.8, p.y-0.8, p.z-0.8, p.x+0.8, p.y+0.8, p.z+0.8))) {
                        if (e != player && e.isAlive()) { e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 1, false, false, false)); e.hurt(level.damageSources().playerAttack(player), damage); }
                    }
                }
            }
            case "earth" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 1.0f, 0.6f);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(3.0 * mult))) {
                    if (e != player && e.isAlive()) {
                        Vec3 kb = e.position().subtract(pos).normalize().scale(1.2);
                        e.setDeltaMovement(kb.x, 0.4, kb.z); e.hurtMarked = true;
                        e.hurt(level.damageSources().playerAttack(player), damage);
                        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, e.getX(), e.getY() + 1, e.getZ(), 5, 0.2, 0.2, 0.2, 0.03);
                    }
                }
            }
            case "air" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.0f, 1.5f);
                Vec3 tip = player.getEyePosition();
                for (double t = 0; t < range + 4; t += 0.5) {
                    Vec3 p = tip.add(look.scale(t));
                    level.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.02);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p.x-1, p.y-1, p.z-1, p.x+1, p.y+1, p.z+1))) {
                        if (e != player && e.isAlive()) { Vec3 kb = look.scale(1.5); e.setDeltaMovement(kb.x, 0.5, kb.z); e.hurtMarked = true; e.hurt(level.damageSources().playerAttack(player), damage * 0.8f); }
                    }
                }
            }
            case "light" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.0f, 2.0f);
                Vec3 tip = player.getEyePosition();
                for (double t = 0; t < range + 5; t += 0.4) {
                    Vec3 p = tip.add(look.scale(t));
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 2, 0.03, 0.03, 0.03, 0.01);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(p.x-0.7, p.y-0.7, p.z-0.7, p.x+0.7, p.y+0.7, p.z+0.7))) {
                        if (e != player && e.isAlive()) { e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 30, 0, false, false, false)); e.hurt(level.damageSources().magic(), damage); }
                    }
                }
            }
            case "dark" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(4.0 * mult))) {
                    if (e != player && e.isAlive()) {
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL, e.getX(), e.getY() + 1, e.getZ(), 8, 0.5, 0.5, 0.5, 0.05);
                        e.hurt(level.damageSources().wither(), damage);
                        player.heal(damage * 0.3f);
                    }
                }
            }
        }
    }

    /** ضربة إنهاء Combo (كل 4 ضربات) */
    private static void performComboFinisher(ServerPlayer player, PlayerElementData data,
                                              String element, float mult, ServerLevel level, Vec3 pos, Vec3 look) {
        level.sendParticles(ParticleTypes.SONIC_BOOM, pos.x, pos.y + 1, pos.z, 1, 0, 0, 0, 0);
        float finisherDmg = 18.0f * mult;
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c§l★ FINISHER! §r§e×" + String.format("%.1f", mult)));
        switch (element) {
            case "fire", "light" ->
                damageInLine(player, 20.0, finisherDmg);
            default ->
                damageNearby(player, 6.0 * mult, finisherDmg, true);
        }
        // جزيئات الإنهاء
        for (int i = 0; i < 12; i++) {
            double a = i * (Math.PI * 2 / 12);
            for (int h = 0; h < 4; h++) {
                switch (element) {
                    case "fire" -> level.sendParticles(ParticleTypes.FLAME, pos.x + 3 * Math.cos(a), pos.y + h, pos.z + 3 * Math.sin(a), 3, 0.1, 0.05, 0.1, 0.05);
                    case "water" -> level.sendParticles(ParticleTypes.FALLING_WATER, pos.x + 3 * Math.cos(a), pos.y + h, pos.z + 3 * Math.sin(a), 3, 0.1, 0.05, 0.1, 0.03);
                    case "earth" -> level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x + 3 * Math.cos(a), pos.y + h, pos.z + 3 * Math.sin(a), 2, 0.2, 0.1, 0.2, 0.03);
                    case "air" -> level.sendParticles(ParticleTypes.CLOUD, pos.x + 3 * Math.cos(a), pos.y + h, pos.z + 3 * Math.sin(a), 2, 0.1, 0.05, 0.1, 0.03);
                    case "light" -> level.sendParticles(ParticleTypes.END_ROD, pos.x + 3 * Math.cos(a), pos.y + h, pos.z + 3 * Math.sin(a), 3, 0.05, 0.05, 0.05, 0.02);
                    case "dark" -> level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x + 3 * Math.cos(a), pos.y + h, pos.z + 3 * Math.sin(a), 3, 0.1, 0.05, 0.1, 0.05);
                }
            }
        }
    }

    // =====================================================================
    //  زر [ - القوة الخاصة
    // =====================================================================
    public static void handleSpecialAbilityInput(ServerPlayer player, PlayerElementData data, boolean pressed) {
        if (!pressed) return;

        String element = data.currentElement();

        // الهواء: تفعيل/إيقاف الطيران
        if (element.equals("air") && data.specialAbilityType() == 4 && data.specialAbilityTicks() > 0) {
            // إيقاف الطيران
            stopAirFlight(player, data);
            return;
        }

        if (data.specialAbilityTicks() > 0) return;
        if (data.abilityCooldown() > 0) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cانتظر §e" + (data.abilityCooldown()/20) + " §cثانية."));
            return;
        }
        if (data.stamina() < 30) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cلا تملك طاقة كافية!"));
            return;
        }

        int type = switch (element) {
            case "earth" -> 1;
            case "water" -> 2;
            case "fire" -> 3;
            case "air" -> 4;
            case "light" -> 5;
            case "dark" -> 6;
            default -> 0;
        };
        if (type == 0) return;

        // التحقق من تفعيل الشحن الأسطوري للطاقة الخاصة إذا كانت الطاقة ممتلئة (100)
        if (data.stamina() >= 100) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§d✨ جاري شحن الطاقة الخاصة القصوى..."));
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE, net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 0.5f);
            updatePlayerData(player, new PlayerElementData(
                element, data.stamina(), data.maxStamina(),
                data.abilityCooldown(), data.flightTicks(), false, 0, 0, 0, 1, 100 + type, data.activeAbility()
            ));
            return;
        }

        String abilityName = switch (type) {
            case 1 -> "§6🪨 اقتلاع الأرض!";
            case 2 -> "§b❄ قفص الجليد!";
            case 3 -> "§c🔥 جدار النار!";
            case 4 -> "§f🌪️ الطيران الحر!";
            case 5 -> "§e☀ قصف نوراني!";
            case 6 -> "§8🌑 استنزاف الأرواح!";
            default -> "";
        };
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(abilityName));

        int staminaCost = (type == 4) ? 10 : 35; // الطيران يكلف أقل بالبداية لكن يستهلك مستمراً
        updatePlayerData(player, new PlayerElementData(
            element, Math.max(0, data.stamina() - staminaCost), data.maxStamina(),
            (type == 4) ? 0 : SPECIAL_COOLDOWN_TICKS, data.flightTicks(), false, 0, 0, 0, 1, type, data.activeAbility()
        ));
    }

    @SuppressWarnings("deprecation")
    private static void stopAirFlight(ServerPlayer player, PlayerElementData data) {
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§f🌪️ هبوط..."));
        if (player.gameMode.isSurvival()) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
        }
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 8, 0, false, false, false));
        // جزيئات هبوط
        ServerLevel level = player.serverLevel();
        level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY(), player.getZ(), 30, 1.5, 0.5, 1.5, 0.05);
        updatePlayerData(player, new PlayerElementData(
            data.currentElement(), data.stamina(), data.maxStamina(),
            SPECIAL_COOLDOWN_TICKS, data.flightTicks(), false, 0, 0, 0, 0, 0, data.activeAbility()
        ));
    }

    // =====================================================================
    //  الهجوم المشحون السينمائي
    // =====================================================================
    private static void performChargedAttack(ServerPlayer player, PlayerElementData data) {
        String element = data.currentElement();
        int charge = data.chargeTicks();
        float powerMult = 0.5f + (charge / (float)MAX_CHARGE_TICKS) * 2.0f;
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();

        switch (element) {
            case "water" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 2.0f, 0.6f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 0.8f, 1.5f);
                double radius = 8.0 * powerMult;
                for (int wave = 0; wave < 5; wave++) {
                    double waveR = radius * (wave + 1) / 5.0;
                    double waveY = pos.y + wave * 0.8;
                    spawnSpiralParticles(level, ParticleTypes.SPLASH, pos.x, waveY, pos.z, waveR, 80, wave * 0.5);
                    spawnSpiralParticles(level, ParticleTypes.FALLING_WATER, pos.x, waveY + 0.3, pos.z, waveR * 0.7, 40, wave * 0.3);
                }
                for (int i = 0; i < 30; i++) {
                    level.sendParticles(ParticleTypes.FALLING_WATER, pos.x, pos.y + 2 + i * 0.3, pos.z, 5, 0.5, 0.1, 0.5, 0.02);
                }
                damageNearby(player, radius, 18.0f * powerMult, true);
                player.setDeltaMovement(0, 1.2, 0);
                player.hurtMarked = true;
            }
            case "fire" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.7f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.5f, 0.5f);
                double radius = 9.0 * powerMult;
                for (int ring = 0; ring < 4; ring++) {
                    double ringR = radius * (ring + 1) / 4.0;
                    spawnCircleParticles(level, ParticleTypes.FLAME, new Vec3(pos.x, pos.y + ring * 0.5, pos.z), ringR, 100);
                    spawnCircleParticles(level, ParticleTypes.LAVA, new Vec3(pos.x, pos.y + ring * 0.3, pos.z), ringR * 0.5, 30);
                }
                for (int i = 0; i < 20; i++) {
                    level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 1 + i * 0.5, pos.z, 8, 0.3, 0.1, 0.3, 0.05);
                    level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 1 + i * 0.5, pos.z, 3, 0.4, 0.1, 0.4, 0.02);
                }
                level.sendParticles(ParticleTypes.FLAME, pos.x, pos.y + 12, pos.z, 50, 2.0, 0.5, 2.0, 0.1);
                damageNearby(player, radius, 22.0f * powerMult, true);
                Vec3 recoil = player.getLookAngle().reverse().scale(2.0);
                player.setDeltaMovement(recoil.x, 0.6, recoil.z);
                player.hurtMarked = true;
            }
            case "earth" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 2.0f, 0.3f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 1.5f, 0.5f);
                double radius = 7.0 * powerMult;
                for (int wave = 1; wave <= 6; wave++) {
                    double waveR = radius * wave / 6.0;
                    BlockPos basePos = player.blockPosition().below();
                    BlockState groundState = level.getBlockState(basePos);
                    if (!groundState.isAir()) {
                        spawnCircleParticles(level, new BlockParticleOption(ParticleTypes.BLOCK, groundState), new Vec3(pos.x, pos.y + 0.5, pos.z), waveR, 60);
                    }
                    spawnCircleParticles(level, ParticleTypes.GUST, new Vec3(pos.x, pos.y + 0.3, pos.z), waveR, 40);
                }
                level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 60, 2.0, 1.0, 2.0, 0.15);
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 1, pos.z, 20, 1.0, 2.0, 1.0, 0.05);
                damageNearby(player, radius, 28.0f * powerMult, true);
                player.setDeltaMovement(0, -2.5, 0);
                player.hurtMarked = true;
            }
            case "air" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 2.0f, 1.8f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.5f, 1.0f);
                double radius = 12.0 * powerMult;
                for (int h = 0; h < 10; h++) {
                    double spiralR = radius * (1.0 - h / 10.0);
                    spawnSpiralParticles(level, ParticleTypes.CLOUD, pos.x, pos.y + h * 1.5, pos.z, spiralR, 60, h * 0.8);
                    spawnSpiralParticles(level, ParticleTypes.GUST, pos.x, pos.y + h * 1.5 + 0.5, pos.z, spiralR * 0.6, 30, h * 0.5);
                }
                level.sendParticles(ParticleTypes.SONIC_BOOM, pos.x, pos.y + 1, pos.z, 3, 0, 0, 0, 0);
                damageNearby(player, radius, 16.0f * powerMult, true);
                Vec3 dash = player.getLookAngle().scale(5.0 * powerMult);
                player.setDeltaMovement(dash.x, 0.5, dash.z);
                player.hurtMarked = true;
            }
            case "light" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0f, 2.0f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 1.5f, 1.5f);
                for (int ring = 0; ring < 3; ring++) {
                    spawnCircleParticles(level, ParticleTypes.END_ROD, new Vec3(pos.x, pos.y + 1 + ring * 0.8, pos.z), 2.0 + ring * 0.5, 50);
                }
                damageInLine(player, 45.0 * powerMult, 28.0f * powerMult);
                Vec3 look = player.getLookAngle();
                for (double i = 0; i < 45.0 * powerMult; i += 0.3) {
                    Vec3 p = player.getEyePosition().add(look.scale(i));
                    level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 3, 0.05, 0.05, 0.05, 0.01);
                }
                player.setDeltaMovement(player.getLookAngle().reverse().scale(1.5));
                player.hurtMarked = true;
            }
            case "dark" -> {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 1.2f, 0.3f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.PLAYERS, 1.5f, 0.5f);
                double radius = 14.0 * powerMult;
                for (int ring = 0; ring < 8; ring++) {
                    double ringR = radius * (1.0 - ring / 8.0);
                    spawnSpiralParticles(level, ParticleTypes.SCULK_CHARGE_POP, pos.x, pos.y + 1 + ring * 0.3, pos.z, ringR, 50, ring * 0.4);
                    spawnSpiralParticles(level, ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 1 + ring * 0.2, pos.z, ringR * 0.7, 30, ring * 0.6);
                }
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius))) {
                    if (entity != player && entity.isAlive()) {
                        Vec3 pull = pos.subtract(entity.position()).normalize().scale(2.5);
                        entity.setDeltaMovement(pull.x, 0.8, pull.z);
                        entity.hurtMarked = true;
                        entity.hurt(level.damageSources().magic(), 14.0f * powerMult);
                    }
                }
                player.heal(8.0f * powerMult);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x, pos.y + 1, pos.z, 30, 0.5, 1.0, 0.5, 0.1);
            }
        }

        updatePlayerData(player, new PlayerElementData(element, Math.max(0, data.stamina() - 30), data.maxStamina(), COOLDOWN_TICKS, data.flightTicks(), false, 0, 0, 0, data.specialAbilityTicks(), data.specialAbilityType(), data.activeAbility()));
        player.hurtMarked = true;
    }

    // =====================================================================
    //  Tick Handler الرئيسي - مُصلَح
    // =====================================================================
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof ServerPlayer player) {
            PlayerElementData data;
            try { data = player.getData(ModAttachmentTypes.ELEMENT_DATA); } catch (Exception e) { return; }
            if (data.currentElement().equals("none")) return;

            int currentStam = data.stamina();
            int currentCooldown = data.abilityCooldown();
            int currentFlight = data.flightTicks();
            int idleTicks = data.idleTicks();
            int chargeTicks = data.chargeTicks();
            boolean isCharging = data.isCharging();
            int idlePhase = data.idlePhase();
            int specialTicks = data.specialAbilityTicks();
            int specialType = data.specialAbilityType();

            // 1. تجديد الطاقة
            if (player.tickCount % 10 == 0 && !isCharging && !(specialType == 4 && specialTicks > 0)) {
                currentStam = Math.min(data.maxStamina(), currentStam + 1);
            }
            if (currentCooldown > 0) currentCooldown--;
            if (currentFlight > 0) {
                currentFlight--;
                handleFlight(player, currentFlight);
            }

            // === كشف الحركة الأفقية فقط (تجاهل الجاذبية) ===
            double dx = player.getX() - player.xOld;
            double dz = player.getZ() - player.zOld;
            double horizontalMoveSq = dx * dx + dz * dz;
            boolean isPlayerStill = horizontalMoveSq < 0.0001 && player.onGround();

            // 2. منطق الشحن
            if (isCharging) {
                chargeTicks++;
                idleTicks = 0;
                idlePhase = 0;

                if (player.tickCount % 5 == 0) {
                    currentStam = Math.max(0, currentStam - 2);
                    if (currentStam <= 0) {
                        performChargedAttack(player, new PlayerElementData(data.currentElement(), 0, data.maxStamina(), currentCooldown, currentFlight, true, chargeTicks, idleTicks, 0, specialTicks, specialType, data.activeAbility()));
                        return;
                    }
                }

                spawnChargeParticles(player, data.currentElement(), chargeTicks);

                if (chargeTicks > 10) {
                    player.setDeltaMovement(player.getDeltaMovement().x * 0.7, 0.06, player.getDeltaMovement().z * 0.7);
                    player.hurtMarked = true;
                }

                if (chargeTicks >= MAX_CHARGE_TICKS) {
                    performChargedAttack(player, new PlayerElementData(data.currentElement(), currentStam, data.maxStamina(), currentCooldown, currentFlight, true, chargeTicks, idleTicks, 0, specialTicks, specialType, data.activeAbility()));
                    return;
                }
            } else {
                // 3. القوة الخاصة
                if (specialTicks > 0) {
                    specialTicks++;
                    idleTicks = 0;
                    idlePhase = 0;
                    int result = tickSpecialAbility(player, data.currentElement(), specialType, specialTicks, currentStam);
                    if (result <= 0) {
                        // القوة انتهت
                        specialTicks = 0;
                        specialType = 0;
                        if (result == -1) {
                            // الطاقة القصوى انتهت — تصفير الطاقة بالكامل
                            currentStam = 0;
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§8✦ §7انتهى النطاق الأسطوري. طاقتك استُنزفت بالكامل."));
                            player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 10, 1, false, false));
                            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 5, 1, false, false));
                        }
                    } else {
                        specialTicks = result;
                        // استهلاك مانا للطيران
                        if (specialType == 4 && player.tickCount % 8 == 0) {
                            currentStam = Math.max(0, currentStam - 2);
                        }
                    }
                }
                // 4. كشف الخمول - مُصلَح!
                else if (isPlayerStill) {
                    idleTicks++;
                    if (idleTicks >= IDLE_THRESHOLD_TICKS && idlePhase == 0) {
                        idlePhase = 1;
                        idleTicks = 0;
                    }
                } else {
                    idleTicks = 0;
                    if (idlePhase > 0 && idlePhase < IDLE_CINEMATIC_DURATION - 10) {
                        idlePhase = 0; // إلغاء الخمول عند الحركة
                    }
                }
            }

            // 5. تنفيذ حركة الخمول
            if (idlePhase > 0) {
                idlePhase++;
                tickIdleCinematic(player, data.currentElement(), idlePhase);
                if (idlePhase >= IDLE_CINEMATIC_DURATION) {
                    idlePhase = 0;
                }
                // إلغاء عند الحركة الأفقية فقط
                if (horizontalMoveSq > 0.01) {
                    idlePhase = 0;
                }
            }

            // حفظ وتحديث
            PlayerElementData newData = new PlayerElementData(data.currentElement(), currentStam, data.maxStamina(), currentCooldown, currentFlight, isCharging, chargeTicks, idleTicks, idlePhase, specialTicks, specialType, data.activeAbility());
            player.setData(ModAttachmentTypes.ELEMENT_DATA, newData);

            if (player.tickCount % 10 == 0) {
                syncToClient(player, newData);
            }

            handleLegacyAbilities(player, data);
            tickSummonedAlliesExpiry(player);
            tickActiveShields(player);
            tickCastleEffects(player);
            tickTeleportProcess(player);
        }
    }

    @SubscribeEvent
    public static void onLivingIncomingDamage(LivingIncomingDamageEvent event) {
        Entity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();
        if (attacker == null) return;

        boolean victimIsAlly = victim.getTags().contains("avatar_ally");
        boolean attackerIsAlly = attacker.getTags().contains("avatar_ally");

        // منع إيذاء الحلفاء من قبل اللاعب المالك أو العكس
        if (victimIsAlly && attacker instanceof ServerPlayer) {
            event.setCanceled(true);
        }
        if (victim instanceof ServerPlayer && attackerIsAlly) {
            event.setCanceled(true);
        }
        // منع الحلفاء من ضرب بعضهم البعض نهائياً
        if (victimIsAlly && attackerIsAlly) {
            event.setCanceled(true);
        }
        
        // منع وحوش المهارات من استهداف بعضها
        if (attacker instanceof net.minecraft.world.entity.Mob mob && attackerIsAlly && victimIsAlly) {
            mob.setTarget(null);
        }
    }

    // =====================================================================
    //  تنظيف الوحوش المستدعاة بعد انتهاء مدتها
    // =====================================================================
    private static void tickSummonedAlliesExpiry(ServerPlayer player) {
        if (summonedAllies.isEmpty() && ultimateDomains.isEmpty()) return;

        long now = System.currentTimeMillis();
        ServerLevel level = player.serverLevel();

        // تنظيف الوحوش منتهية المدة
        summonedAllies.entrySet().removeIf(entry -> {
            if (now >= entry.getValue()) {
                Entity e = level.getEntity(entry.getKey());
                if (e != null && e.isAlive()) {
                    // جزيئات اختفاء ثم إزالة
                    level.sendParticles(ParticleTypes.PORTAL,
                        e.getX(), e.getY() + 1, e.getZ(), 20, 0.5, 1.0, 0.5, 0.1);
                    e.discard();
                }
                return true;
            }
            return false;
        });

        // تنظيف نطاق النور (إزالة الإضاءة)
        UltimateDomainData domain = ultimateDomains.get(player.getUUID());
        if (domain != null && now >= domain.expireTime()) {
            if (domain.baseType() == 5) {
                removeLightDomain(level, domain.center(), 50);
            }
            ultimateDomains.remove(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7✦ تبددت آثار النطاق."));
        }
    }

    private static void removeLightDomain(ServerLevel level, Vec3 center, int radius) {
        // إزالة بلوكات الضوء التي وُضعت عند تفعيل النطاق
        for (int dx = -radius; dx <= radius; dx += 8) {
            for (int dz = -radius; dz <= radius; dz += 8) {
                for (int dy = -5; dy <= 15; dy += 8) {
                    BlockPos bp = BlockPos.containing(
                        center.x + dx, center.y + dy, center.z + dz);
                    if (level.getBlockState(bp).is(net.minecraft.world.level.block.Blocks.LIGHT)) {
                        level.setBlock(bp, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

    private static void applyLightDomain(ServerLevel level, Vec3 center, int radius) {
        // وضع بلوكات ضوء (LIGHT Block مستوى 15) منتشرة في كل نطاق 50 بلوكة
        // نستخدم شبكة كل 8 بلوكات لتغطية المنطقة دون إبطاء الخادم
        net.minecraft.world.level.block.state.BlockState lightState =
            net.minecraft.world.level.block.Blocks.LIGHT.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LightBlock.LEVEL, 15);

        for (int dx = -radius; dx <= radius; dx += 8) {
            for (int dz = -radius; dz <= radius; dz += 8) {
                if (dx * dx + dz * dz > radius * radius) continue; // دائرة فعلية
                for (int dy = 1; dy <= 10; dy += 6) {
                    BlockPos bp = BlockPos.containing(
                        center.x + dx, center.y + dy, center.z + dz);
                    if (level.getBlockState(bp).isAir()) {
                        level.setBlock(bp, lightState, 3);
                    }
                }
            }
        }
        level.playSound(null, center.x, center.y, center.z,
            SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5f, 1.5f);
    }

    // =====================================================================
    //  حركات الخمول السينمائية (Idle Cinematics) - مُحسَّنة
    // =====================================================================
    private static void tickIdleCinematic(ServerPlayer player, String element, int phase) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();
        double time = phase * 0.15;

        switch (element) {
            case "water" -> {
                // ===== موجات مائية تتصاعد → كرة ماء فوق الرأس → شلال =====
                if (phase < 35) {
                    // موجات حلزونية تتصاعد من الأرض
                    int streams = 4;
                    for (int s = 0; s < streams; s++) {
                        double angle = time * 3 + s * (Math.PI * 2 / streams);
                        double r = 2.5 * (1.0 - phase / 35.0);
                        double height = phase * 0.15;
                        double px = pos.x + r * Math.cos(angle);
                        double pz = pos.z + r * Math.sin(angle);
                        level.sendParticles(ParticleTypes.FALLING_WATER, px, pos.y + height, pz, 3, 0.03, 0.05, 0.03, 0.005);
                        level.sendParticles(ParticleTypes.SPLASH, px, pos.y + height - 0.2, pz, 1, 0.02, 0.02, 0.02, 0.005);
                        // قطرات صغيرة ترتفع
                        level.sendParticles(ParticleTypes.DRIPPING_WATER, px, pos.y + 0.5 + height * 0.5, pz, 1, 0.1, 0.3, 0.1, 0.01);
                    }
                    if (phase == 2) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WATER_AMBIENT, SoundSource.PLAYERS, 1.2f, 1.0f);
                    if (phase == 20) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.8f, 1.5f);
                } else if (phase < 80) {
                    // كرة ماء عملاقة فوق الرأس تنبض
                    double sphereY = pos.y + 4.5;
                    double pulse = 1.0 + Math.sin(time * 2) * 0.2;
                    double sphereR = 1.8 * pulse;
                    // سطح الكرة
                    for (int i = 0; i < 20; i++) {
                        double phi = Math.random() * Math.PI * 2;
                        double theta = Math.random() * Math.PI;
                        double sx = sphereR * Math.sin(theta) * Math.cos(phi);
                        double sy = sphereR * Math.sin(theta) * Math.sin(phi);
                        double sz = sphereR * Math.cos(theta);
                        level.sendParticles(ParticleTypes.FALLING_WATER, pos.x + sx, sphereY + sy, pos.z + sz, 1, 0, 0, 0, 0);
                    }
                    // حلقة تدور حول الكرة
                    for (int i = 0; i < 12; i++) {
                        double a = time * 2.5 + i * (Math.PI * 2 / 12);
                        level.sendParticles(ParticleTypes.SPLASH, pos.x + (sphereR + 0.5) * Math.cos(a), sphereY, pos.z + (sphereR + 0.5) * Math.sin(a), 1, 0, 0, 0, 0);
                    }
                    // تقطير من الكرة
                    if (phase % 3 == 0) {
                        level.sendParticles(ParticleTypes.DRIPPING_WATER, pos.x + (Math.random()-0.5)*2, sphereY - sphereR, pos.z + (Math.random()-0.5)*2, 2, 0.1, 0, 0.1, 0);
                    }
                } else {
                    // الكرة تنفجر كشلال دائري
                    if (phase == 80) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 1.2f, 0.8f);
                    double progress = (phase - 80) / 40.0;
                    double burstR = 2.0 + progress * 5.0;
                    double burstY = pos.y + 4.5 - progress * 4.0;
                    int drops = (int)(20 * (1.0 - progress));
                    for (int i = 0; i < drops; i++) {
                        double a = Math.random() * Math.PI * 2;
                        double r = Math.random() * burstR;
                        level.sendParticles(ParticleTypes.FALLING_WATER, pos.x + r * Math.cos(a), burstY + Math.random() * 0.5, pos.z + r * Math.sin(a), 1, 0.05, 0.2, 0.05, 0.03);
                    }
                }
            }
            case "fire" -> {
                // ===== لهب حلزوني صاعد → حلقة نار → انفجار نجمي =====
                if (phase < 40) {
                    int flames = 5;
                    for (int f = 0; f < flames; f++) {
                        double angle = time * 4 + f * (Math.PI * 2 / flames);
                        double r = 1.2 + phase * 0.02;
                        double height = phase * 0.13;
                        double px = pos.x + r * Math.cos(angle);
                        double pz = pos.z + r * Math.sin(angle);
                        level.sendParticles(ParticleTypes.FLAME, px, pos.y + height, pz, 1, 0.02, 0.02, 0.02, 0.008);
                        level.sendParticles(ParticleTypes.SMALL_FLAME, px, pos.y + height + 0.15, pz, 1, 0.01, 0.01, 0.01, 0.003);
                    }
                    // دخان خفيف
                    level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + phase * 0.1, pos.z, 1, 0.3, 0.1, 0.3, 0.005);
                    if (phase == 2) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_AMBIENT, SoundSource.PLAYERS, 0.7f, 1.2f);
                } else if (phase < 85) {
                    // حلقة نار نابضة فوق الرأس
                    double ringY = pos.y + 5.5 + Math.sin(time) * 0.15;
                    double ringR = 2.2 + Math.sin(time * 2) * 0.4;
                    for (int i = 0; i < 30; i++) {
                        double a = (i / 30.0) * Math.PI * 2 + time * 1.5;
                        level.sendParticles(ParticleTypes.FLAME, pos.x + ringR * Math.cos(a), ringY, pos.z + ringR * Math.sin(a), 1, 0.01, 0.01, 0.01, 0.005);
                    }
                    // نواة مضيئة
                    level.sendParticles(ParticleTypes.FLAME, pos.x, ringY, pos.z, 3, 0.3, 0.2, 0.3, 0.01);
                    level.sendParticles(ParticleTypes.LAVA, pos.x, ringY, pos.z, 1, 0.2, 0.1, 0.2, 0.005);
                } else {
                    // انفجار نجمي
                    if (phase == 85) {
                        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.5f, 0.7f);
                        double burstY = pos.y + 5.5;
                        // أشعة نجمية
                        for (int ray = 0; ray < 10; ray++) {
                            double rayAngle = ray * (Math.PI * 2 / 10);
                            for (int d = 1; d <= 5; d++) {
                                level.sendParticles(ParticleTypes.FLAME, pos.x + d * 0.8 * Math.cos(rayAngle), burstY + (Math.random()-0.5)*0.5, pos.z + d * 0.8 * Math.sin(rayAngle), 2, 0.03, 0.03, 0.03, 0.01);
                            }
                        }
                    }
                    double fade = (phase - 85) / 35.0;
                    level.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 5, pos.z, (int)(5 * (1.0-fade)), 2.0 * fade, 1.0, 2.0 * fade, 0.03);
                }
            }
            case "earth" -> {
                // ===== ارتجاج → صخور تطفو → سقوط مدوي =====
                if (phase < 30) {
                    if (phase % 4 == 0) {
                        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.2f + phase * 0.01f, 0.3f);
                    }
                    BlockPos below = player.blockPosition().below();
                    BlockState ground = level.getBlockState(below);
                    if (!ground.isAir()) {
                        for (int i = 0; i < 6; i++) {
                            double ox = (Math.random() - 0.5) * 5;
                            double oz = (Math.random() - 0.5) * 5;
                            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), pos.x + ox, pos.y + 0.3, pos.z + oz, 4, 0.1, 0.4, 0.1, 0.08);
                        }
                    }
                    // شقوق أرضية
                    level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x + (Math.random()-0.5)*3, pos.y + 0.1, pos.z + (Math.random()-0.5)*3, 2, 0.1, 0.0, 0.1, 0.01);
                } else if (phase < 80) {
                    // 6 صخور تطفو وتدور
                    int numRocks = 6;
                    for (int i = 0; i < numRocks; i++) {
                        double angle = time * 1.8 + i * (Math.PI * 2 / numRocks);
                        double r = 2.8;
                        double bobHeight = 1.8 + Math.sin(time * 1.2 + i * 0.9) * 0.6;
                        double px = pos.x + r * Math.cos(angle);
                        double pz = pos.z + r * Math.sin(angle);
                        BlockPos below = player.blockPosition().below();
                        BlockState ground = level.getBlockState(below);
                        if (!ground.isAir()) {
                            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), px, pos.y + bobHeight, pz, 6, 0.12, 0.12, 0.12, 0.008);
                        }
                        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, px, pos.y + bobHeight - 0.3, pz, 1, 0.05, 0.05, 0.05, 0.003);
                    }
                    // غبار من الأرض
                    if (phase % 5 == 0) {
                        level.sendParticles(ParticleTypes.GUST, pos.x, pos.y + 0.5, pos.z, 2, 1.5, 0.1, 1.5, 0.02);
                    }
                } else {
                    // صخور تسقط بقوة
                    if (phase == 80) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 1.0f, 0.5f);
                    double fallProgress = (phase - 80) / 40.0;
                    for (int i = 0; i < 6; i++) {
                        double angle = i * (Math.PI * 2 / 6);
                        double r = 2.8 * (1.0 - fallProgress * 0.7);
                        double height = 1.8 * (1.0 - fallProgress);
                        BlockPos below = player.blockPosition().below();
                        BlockState ground = level.getBlockState(below);
                        if (!ground.isAir()) {
                            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), pos.x + r * Math.cos(angle), pos.y + height, pos.z + r * Math.sin(angle), 10, 0.2, 0.2, 0.2, 0.12);
                        }
                    }
                    // غبار عند الارتطام
                    if (phase >= 115) {
                        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.5, pos.z, 15, 3.0, 0.3, 3.0, 0.05);
                    }
                }
            }
            case "air" -> {
                // ===== ارتفاع + دوامة ريح + تأمل عائم + هبوط =====
                if (phase < 25) {
                    // رياح تجتمع + ارتفاع تدريجي
                    double lift = Math.min(phase * 0.04, 0.8);
                    player.setDeltaMovement(0, lift, 0);
                    player.hurtMarked = true;
                    for (int i = 0; i < 8; i++) {
                        double angle = time * 6 + i * (Math.PI / 4);
                        double r = 1.8 + phase * 0.04;
                        level.sendParticles(ParticleTypes.CLOUD, pos.x + r * Math.cos(angle), pos.y + phase * 0.12, pos.z + r * Math.sin(angle), 1, 0.03, 0.03, 0.03, 0.003);
                    }
                    // رياح متطايرة من الأرض
                    level.sendParticles(ParticleTypes.GUST, pos.x, pos.y + 0.2, pos.z, 2, 1.0, 0.1, 1.0, 0.02);
                    if (phase == 2) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.8f, 1.6f);
                } else if (phase < 75) {
                    // عائم مع دوامة جميلة
                    player.setDeltaMovement(0, 0.03, 0);
                    player.hurtMarked = true;
                    for (int ring = 0; ring < 3; ring++) {
                        double ringR = 2.5 + ring * 0.6;
                        for (int i = 0; i < 10; i++) {
                            double a = time * (3 - ring * 0.5) + i * (Math.PI * 2 / 10) + ring * Math.PI / 3;
                            level.sendParticles(ParticleTypes.CLOUD, pos.x + ringR * Math.cos(a), pos.y - 1 + ring * 0.6, pos.z + ringR * Math.sin(a), 1, 0.02, 0.02, 0.02, 0.002);
                        }
                    }
                    // أوراق متطايرة (جزيئات gust)
                    if (phase % 4 == 0) {
                        level.sendParticles(ParticleTypes.GUST, pos.x + (Math.random()-0.5)*4, pos.y + Math.random()*2, pos.z + (Math.random()-0.5)*4, 1, 0, 0, 0, 0);
                    }
                    if (phase % 30 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.3f, 2.0f);
                } else {
                    // هبوط ناعم
                    double descent = -0.1 * ((phase - 75) / 45.0);
                    player.setDeltaMovement(0, descent, 0);
                    player.hurtMarked = true;
                    player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 30, 0, false, false, false));
                    level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y - 0.5, pos.z, 2, 0.8, 0.1, 0.8, 0.01);
                }
            }
            case "light" -> {
                // ===== أشعة ← هالة ملائكية ← عمود نور سماوي =====
                if (phase < 35) {
                    int rays = Math.min(phase / 2, 16);
                    for (int i = 0; i < rays; i++) {
                        double angle = i * (Math.PI * 2 / 16);
                        double length = 0.5 + phase * 0.06;
                        for (double d = 0.2; d < length; d += 0.25) {
                            level.sendParticles(ParticleTypes.END_ROD, pos.x + d * Math.cos(angle), pos.y + 1.5, pos.z + d * Math.sin(angle), 1, 0.01, 0.01, 0.01, 0.0005);
                        }
                    }
                    if (phase == 2) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 1.0f, 1.5f);
                } else if (phase < 85) {
                    // هالة فوق الرأس
                    double haloY = pos.y + 2.8 + Math.sin(time * 0.8) * 0.08;
                    spawnCircleParticles(level, ParticleTypes.END_ROD, new Vec3(pos.x, haloY, pos.z), 0.7, 16);
                    // أعمدة نور من وإلى السماء
                    if (phase % 8 == 0) {
                        for (int y = 0; y < 25; y++) {
                            level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y + 3 + y * 0.5, pos.z, 1, 0.05, 0, 0.05, 0.0005);
                        }
                    }
                    // أشعة أفقية دوارة
                    for (int i = 0; i < 4; i++) {
                        double a = time * 1.5 + i * (Math.PI / 2);
                        double d = 2.0;
                        level.sendParticles(ParticleTypes.END_ROD, pos.x + d * Math.cos(a), pos.y + 1.5, pos.z + d * Math.sin(a), 1, 0, 0, 0, 0);
                    }
                } else {
                    double fade = 1.0 - (phase - 85) / 35.0;
                    if (Math.random() < fade) {
                        spawnCircleParticles(level, ParticleTypes.END_ROD, new Vec3(pos.x, pos.y + 2.8, pos.z), 0.7, (int)(12 * fade));
                    }
                }
            }
            case "dark" -> {
                // ===== ظلال تتسرب ← أرواح تدور ← تعود للأرض =====
                if (phase < 30) {
                    for (int i = 0; i < 5; i++) {
                        double ox = (Math.random() - 0.5) * 4;
                        double oz = (Math.random() - 0.5) * 4;
                        double h = phase * 0.07 * Math.random();
                        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, pos.x + ox, pos.y + h, pos.z + oz, 1, 0.03, 0.08, 0.03, 0.008);
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x + ox * 0.7, pos.y + h * 0.8, pos.z + oz * 0.7, 1, 0.03, 0.08, 0.03, 0.008);
                    }
                    if (phase == 2) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
                } else if (phase < 80) {
                    // أرواح تلتف حول الجسم
                    int spirits = 6;
                    for (int i = 0; i < spirits; i++) {
                        double angle = time * 2.5 + i * (Math.PI * 2 / spirits);
                        double r = 1.5 + Math.sin(time * 1.8 + i * 0.8) * 0.5;
                        double h = 0.3 + Math.sin(time * 1.2 + i * 1.1) * 1.2;
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x + r * Math.cos(angle), pos.y + h, pos.z + r * Math.sin(angle), 2, 0.03, 0.03, 0.03, 0.008);
                        level.sendParticles(ParticleTypes.SQUID_INK, pos.x + r * Math.cos(angle), pos.y + h, pos.z + r * Math.sin(angle), 1, 0.01, 0.01, 0.01, 0.003);
                    }
                    // ومضات "عيون"
                    if (phase % 20 == 0) {
                        double a = Math.random() * Math.PI * 2;
                        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, pos.x + 2 * Math.cos(a), pos.y + 1.5, pos.z + 2 * Math.sin(a), 5, 0.05, 0.05, 0.05, 0.01);
                    }
                } else {
                    double fade = (phase - 80) / 40.0;
                    int spirits = (int)(6 * (1.0 - fade));
                    for (int i = 0; i < spirits; i++) {
                        double angle = time * 2 + i * (Math.PI * 2.0 / Math.max(spirits, 1));
                        double r = 1.5 * (1.0 - fade);
                        double h = 1.0 * (1.0 - fade);
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x + r * Math.cos(angle), pos.y + h, pos.z + r * Math.sin(angle), 1, 0.03, 0.08, 0.03, 0.015);
                    }
                }
            }
        }
    }

    // =====================================================================
    //  الطاقة الخاصة القصوى المشحونة بالكامل (Ultimate Domain & Summons)
    // =====================================================================
    private static int tickUltimateSpecial(ServerPlayer player, ServerLevel level, Vec3 pos, int baseType, int tick) {
        // مرحلة الشحن (3 ثوانٍ = 60 تيك) - تأثيرات مخصصة لكل عنصر
        if (tick < 60) {
            double progress = tick / 60.0;                          // 0→1
            double spiralR  = 3.5 - progress * 1.5;                // يضيق مع الوقت
            double spiralOffset = tick * 0.18;

            // حركة اللاعب (تجميد في الهواء بشكل درامي)
            player.setDeltaMovement(player.getDeltaMovement().multiply(0.3, 0.2, 0.3));
            if (tick < 25) player.setDeltaMovement(player.getDeltaMovement().add(0, 0.04, 0)); // ارتفاع خفيف
            player.hurtMarked = true;

            // تأثيرات صوتية
            if (tick == 1)  level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_ACTIVATE,        SoundSource.PLAYERS, 1.5f, 0.4f);
            if (tick == 20) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.5f, 0.5f);
            if (tick == 45) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_AMBIENT,         SoundSource.PLAYERS, 1.0f, 0.6f);

            // جزيئات اللحظة الحرجة (الضغط قبل الانفجار)
            if (tick >= 50 && tick % 2 == 0) {
                level.sendParticles(ParticleTypes.CRIT, pos.x, pos.y + 1, pos.z, 8, 1.5, 1.0, 1.5, 0.08);
            }

            // تأثيرات مخصصة لكل عنصر كل 3 تيكات
            if (tick % 3 == 0) {
                switch (baseType) {
                    case 1 -> { // الأرض — صخور تدور + غبار يتصاعد
                        spawnSpiralParticles(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y, pos.z, spiralR, 12, spiralOffset);
                        level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 0.5, pos.z, 5, spiralR * 0.5, 0.2, spiralR * 0.5, 0.02);
                        if (tick % 9 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4f, 0.2f + (float)progress);
                        BlockPos below = player.blockPosition().below();
                        BlockState ground = level.getBlockState(below);
                        if (!ground.isAir())
                            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), pos.x, pos.y + 0.3, pos.z, 10, spiralR * 0.4, 0.5, spiralR * 0.4, 0.1);
                    }
                    case 2 -> { // الماء — دوامة مائية + ثلج + صوت موج
                        spawnSpiralParticles(level, ParticleTypes.FALLING_WATER, pos.x, pos.y + 1, pos.z, spiralR, 14, -spiralOffset);
                        level.sendParticles(ParticleTypes.SNOWFLAKE, pos.x, pos.y + 2, pos.z, 6, spiralR * 0.5, 0.3, spiralR * 0.5, 0.01);
                        level.sendParticles(ParticleTypes.BUBBLE_POP, pos.x, pos.y, pos.z, 4, spiralR * 0.3, 0.1, spiralR * 0.3, 0.05);
                        if (tick % 12 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 0.6f, 0.7f + (float)progress * 0.5f);
                    }
                    case 3 -> { // النار — حلزون لهب + جمر + صوت إشعال
                        spawnSpiralParticles(level, ParticleTypes.FLAME, pos.x, pos.y, pos.z, spiralR, 16, spiralOffset * 1.5);
                        level.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 1, pos.z, 4, spiralR * 0.3, 0.5, spiralR * 0.3, 0.08);
                        level.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y + 2, pos.z, 5, spiralR * 0.4, 0.3, spiralR * 0.4, 0.01);
                        if (tick % 8 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_AMBIENT, SoundSource.PLAYERS, 0.7f, 0.5f + (float)progress);
                    }
                    case 4 -> { // الهواء — حلقات سحابية + صوت ريح + رفع
                        spawnSpiralParticles(level, ParticleTypes.CLOUD, pos.x, pos.y - 0.5, pos.z, spiralR, 10, -spiralOffset * 0.8);
                        spawnSpiralParticles(level, ParticleTypes.GUST,  pos.x, pos.y + 1,   pos.z, spiralR * 0.6, 6, spiralOffset * 2);
                        level.sendParticles(ParticleTypes.POOF, pos.x, pos.y + 1.5, pos.z, 4, spiralR * 0.5, 0.3, spiralR * 0.5, 0.04);
                        if (tick % 10 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.8f, 1.2f + (float)progress * 0.5f);
                        // رفع اللاعب تدريجياً
                        if (tick > 10 && tick < 50) player.setDeltaMovement(player.getDeltaMovement().add(0, 0.06, 0));
                    }
                    case 5 -> { // النور — أشعة + هالة ملائكية + end_rod
                        spawnSpiralParticles(level, ParticleTypes.END_ROD, pos.x, pos.y, pos.z, spiralR, 14, -spiralOffset * 0.7);
                        level.sendParticles(ParticleTypes.INSTANT_EFFECT, pos.x, pos.y + 2.5, pos.z, 6, spiralR * 0.4, 0.2, spiralR * 0.4, 0.02);
                        // عمود نور صاعد
                        if (tick % 6 == 0) {
                            for (int y = 0; y < 30; y++)
                                level.sendParticles(ParticleTypes.END_ROD, pos.x, pos.y + 3 + y * 0.6, pos.z, 1, 0.1, 0, 0.1, 0.001);
                        }
                        if (tick % 10 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 1.0f, 1.5f + (float)progress * 0.5f);
                    }
                    case 6 -> { // الظلام — ظلال تلتهم + أرواح تدور + صوت كهف
                        spawnSpiralParticles(level, ParticleTypes.REVERSE_PORTAL,  pos.x, pos.y, pos.z, spiralR, 14, spiralOffset * 1.2);
                        spawnSpiralParticles(level, ParticleTypes.SCULK_CHARGE_POP, pos.x, pos.y + 1, pos.z, spiralR * 0.5, 8, -spiralOffset);
                        level.sendParticles(ParticleTypes.SQUID_INK, pos.x, pos.y + 1, pos.z, 5, spiralR * 0.4, 0.4, spiralR * 0.4, 0.06);
                        if (tick % 15 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.PLAYERS, 0.8f, 0.4f);
                    }
                    default -> {
                        spawnSpiralParticles(level, ParticleTypes.PORTAL, pos.x, pos.y, pos.z, spiralR, 12, spiralOffset);
                    }
                }
            }
            return tick;

        } else if (tick == 60) {
            // تأثير اللحظة النهائية للانفجار
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y + 1, pos.z, 1, 0, 0, 0, 0);
            executeUltimateSpecialDomain(player, level, pos, baseType);
            return -1; // إنهاء القوة وتصفير الطاقة
        }
        return 0;
    }

    private static void executeUltimateSpecialDomain(ServerPlayer player, ServerLevel level, Vec3 pos, int baseType) {
        double radius = 50.0;

        // أصوات الانفجار مخصصة لكل عنصر
        switch (baseType) {
            case 1 -> { // أرض
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_BREAK_BLOCK,       SoundSource.PLAYERS, 2.0f, 0.3f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR,  SoundSource.PLAYERS, 2.0f, 0.2f);
            }
            case 2 -> { // ماء
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 2.0f, 0.4f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ELDER_GUARDIAN_CURSE,     SoundSource.PLAYERS, 1.5f, 0.7f);
            }
            case 3 -> { // نار
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRECHARGE_USE,           SoundSource.PLAYERS, 2.0f, 0.4f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_SHOOT,              SoundSource.PLAYERS, 2.0f, 0.3f);
            }
            case 4 -> { // هواء
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP,        SoundSource.PLAYERS, 2.0f, 0.5f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_THUNDER,   SoundSource.PLAYERS, 1.5f, 0.8f);
            }
            case 5 -> { // نور
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_ACTIVATE,          SoundSource.PLAYERS, 2.0f, 0.6f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.LIGHTNING_BOLT_IMPACT,    SoundSource.PLAYERS, 1.5f, 1.5f);
            }
            case 6 -> { // ظلام
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_SPAWN,             SoundSource.PLAYERS, 2.0f, 0.8f);
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMBIENT_CAVE.value(),     SoundSource.PLAYERS, 2.0f, 0.3f);
            }
            default -> level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 2.0f, 0.7f);
        }

        String title = switch (baseType) {
            case 1 -> "§6🌋 نطاق الأرض القصوى: غضب الطبيعة!";
            case 2 -> "§b🌊 نطاق الماء القصوى: طوفان المحيط!";
            case 3 -> "§c🔥 نطاق النار القصوى: جحيم الأبدية!";
            case 4 -> "§f🌪️ نطاق الهواء القصوى: إعصار السماء!";
            case 5 -> "§e☀ نطاق النور القصوى: إشراقة القدس!";
            case 6 -> "§8🌑 نطاق الظلام القصوى: كسوف الأرواح!";
            default -> "§d✨ إطلاق الطاقة القصوى!";
        };
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(title));

        // 1. تطبيق التأثيرات والضرر في النطاق الضخم (50 بلوكة)
        AABB domainBox = player.getBoundingBox().inflate(radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, domainBox)) {
            if (e == player || e.getTags().contains("avatar_ally")) continue;

            boolean isFriend = e instanceof net.minecraft.world.entity.player.Player;
            if (isFriend) {
                e.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 20, 1, false, false));
                e.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 20, 0, false, false));
                if (baseType == 5) { // النور: شفاء كامل ودعم إضافي
                    e.addEffect(new MobEffectInstance(MobEffects.HEAL, 1, 1, false, false));
                    e.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 20 * 50, 2, false, false));
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, 20 * 50, 0, false, false));
                }
            } else {
                float damage = baseType == 5 ? 25.0f : 20.0f;
                e.hurt(level.damageSources().playerAttack(player), damage);
                switch (baseType) {
                    case 1 -> {
                        e.setDeltaMovement(0, 1.2, 0);
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 10, 2));
                    }
                    case 2 -> e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 20 * 15, 1));
                    case 3 -> e.igniteForSeconds(15);
                    case 4 -> {
                        Vec3 push = e.position().subtract(pos).normalize().scale(2.5);
                        e.setDeltaMovement(push.x, 1.5, push.z);
                    }
                    case 5 -> {
                        e.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 20 * 8, 0));
                        e.igniteForSeconds(5);
                    }
                    case 6 -> e.addEffect(new MobEffectInstance(MobEffects.WITHER, 20 * 12, 1));
                    default -> {} // لا تأثير إضافي
                }
                e.hurtMarked = true;
            }
        }

        // 2. نشر جزيئات النطاق
        for (double r = 5.0; r <= radius; r += 8.0) {
            int count = (int)(r * 3);
            for (int i = 0; i < count; i++) {
                double angle = Math.random() * Math.PI * 2;
                double px = pos.x + r * Math.cos(angle);
                double pz = pos.z + r * Math.sin(angle);
                double py = pos.y + (Math.random() - 0.5) * 5;

                switch (baseType) {
                    case 1 -> level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py, pz, 2, 0.5, 0.5, 0.5, 0.02);
                    case 2 -> level.sendParticles(ParticleTypes.FALLING_WATER, px, py + 5, pz, 3, 0.5, 2.0, 0.5, 0.05);
                    case 3 -> level.sendParticles(ParticleTypes.FLAME, px, py, pz, 3, 0.5, 0.5, 0.5, 0.05);
                    case 4 -> level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 2, 1.0, 0.5, 1.0, 0.02);
                    case 5 -> {
                        level.sendParticles(ParticleTypes.END_ROD, px, py, pz, 2, 0.5, 0.5, 0.5, 0.02);
                        level.sendParticles(ParticleTypes.INSTANT_EFFECT, px, py + 2, pz, 1, 0, 0, 0, 0);
                    }
                    case 6 -> level.sendParticles(ParticleTypes.REVERSE_PORTAL, px, py, pz, 3, 0.5, 0.5, 0.5, 0.05);
                }
            }
        }

        long expireTime = System.currentTimeMillis() + 50000;
        ultimateDomains.put(player.getUUID(), new UltimateDomainData(baseType, pos, expireTime));

        // 2b. إضاءة خاصة بعنصر النور (Light Domain Illumination)
        if (baseType == 5) {
            applyLightDomain(level, pos, 50);
        }

        // 3. استدعاء الوحوش المساندة
        int summonCount = baseType == 1 ? 4 : 6; // زيادة عدد الوحوش
        for (int i = 0; i < summonCount; i++) {
            double angle = i * (Math.PI * 2 / summonCount);
            double sx = pos.x + 5.0 * Math.cos(angle);
            double sz = pos.z + 5.0 * Math.sin(angle);

            LivingEntity ally = null;
            switch (baseType) {
                case 1 -> {
                    ally = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(level);
                    if (ally != null) ally.setCustomName(net.minecraft.network.chat.Component.literal("§6🪨 حارس الأرض الملكي"));
                }
                case 2 -> {
                    ally = net.minecraft.world.entity.EntityType.DROWNED.create(level);
                    if (ally != null) {
                        ally.setCustomName(net.minecraft.network.chat.Component.literal("§b💧 حارس المحيط"));
                        ally.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TRIDENT));
                    }
                }
                case 3 -> {
                    ally = net.minecraft.world.entity.EntityType.BLAZE.create(level);
                    if (ally != null) ally.setCustomName(net.minecraft.network.chat.Component.literal("§c🔥 خادم اللهب"));
                }
                case 4 -> {
                    ally = net.minecraft.world.entity.EntityType.BREEZE.create(level);
                    if (ally != null) ally.setCustomName(net.minecraft.network.chat.Component.literal("§f🌪️ روح العاصفة"));
                }
                case 5 -> {
                    ally = net.minecraft.world.entity.EntityType.IRON_GOLEM.create(level);
                    if (ally != null) {
                        ally.setCustomName(net.minecraft.network.chat.Component.literal("§e☀ الفارس النوراني"));
                        ally.addEffect(new MobEffectInstance(MobEffects.GLOWING,        20 * 60, 0, false, false));
                        ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST,   20 * 60, 1, false, false));
                        ally.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 20 * 60, 1, false, false));
                    }
                }
                case 6 -> {
                    ally = net.minecraft.world.entity.EntityType.WITHER_SKELETON.create(level);
                    if (ally != null) {
                        ally.setCustomName(net.minecraft.network.chat.Component.literal("§8🌑 حاصد الأرواح"));
                        ally.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_SWORD));
                    }
                }
                default -> {} 
            }

            if (ally != null) {
                ally.setPos(sx, pos.y + 0.5, sz);
                ally.setCustomNameVisible(true);
                ally.addTag("avatar_ally");
                ally.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 60, 1, false, false));
                ally.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 20 * 60, 0, false, false));

                if (ally instanceof net.minecraft.world.entity.Mob mob) {
                    mob.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 0.0f);
                    // AI: يهاجم كل كائن حي غير اللاعب المالك وغير الحلفاء
                    final ServerPlayer owner = player;
                    final java.util.UUID ownerUUID = owner.getUUID();
                    
                    if (mob instanceof net.minecraft.world.entity.PathfinderMob pm) {
                        mob.goalSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(pm, 1.3, true));
                    }
                    
                    mob.targetSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                        mob, LivingEntity.class, true,
                        target -> !target.getUUID().equals(ownerUUID)
                            && !target.getTags().contains("avatar_ally")
                            && !(target instanceof net.minecraft.world.entity.player.Player fp
                                 && fp.getTeam() != null
                                 && owner.getTeam() != null
                                 && fp.getTeam().getName().equals(owner.getTeam().getName()))
                    ));
                }

                level.addFreshEntity(ally);
                summonedAllies.put(ally.getUUID(), expireTime);
                // جزيئات ظهور احترافية
                level.sendParticles(ParticleTypes.EXPLOSION, sx, pos.y + 1, sz, 1, 0, 0, 0, 0);
                level.sendParticles(ParticleTypes.PORTAL, sx, pos.y + 0.5, sz, 30, 0.5, 1.0, 0.5, 0.1);
                level.sendParticles(ParticleTypes.WITCH, sx, pos.y + 2, sz, 20, 0.5, 0.5, 0.5, 0.05);
                level.playSound(null, sx, pos.y, sz, SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.8f, 1.2f);
            }
        }
    }

    // =====================================================================
    //  القوى الخاصة - مُصلَحة ومُحسَّنة
    // =====================================================================
    private static int tickSpecialAbility(ServerPlayer player, String element, int type, int tick, int currentStamina) {
        ServerLevel level = player.serverLevel();
        Vec3 pos = player.position();

        if (type > 100) {
            return tickUltimateSpecial(player, level, pos, type - 100, tick);
        }

        return switch (type) {
            case 1 -> tickEarthUplift(player, level, pos, tick);
            case 2 -> tickIceCage(player, level, pos, tick);
            case 3 -> tickFireWall(player, level, pos, tick);
            case 4 -> tickAirFlight(player, level, pos, tick, currentStamina);
            case 5 -> tickLightBarrage(player, level, pos, tick);
            case 6 -> tickSoulDrain(player, level, pos, tick);
            default -> 0;
        };
    }

    // =====================================================================
    //  اقتلاع الأرض — إحداثيات مُثبَّتة منذ أول tick لمنع الانزياح
    // =====================================================================
    private static int tickEarthUplift(ServerPlayer player, ServerLevel level, Vec3 pos, int tick) {
        // ── تثبيت الإحداثيات عند أول tick فقط ──────────────────────────
        java.util.UUID uid = player.getUUID();
        if (tick == 1) {
            Vec3 lookFlat = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z).normalize();
            int cx = (int)Math.floor(pos.x + lookFlat.x * 8);
            int cz = (int)Math.floor(pos.z + lookFlat.z * 8);
            int by = player.blockPosition().getY();
            earthUpliftCache.put(uid, new int[]{ cx, cz, by });
        }

        int[] cache = earthUpliftCache.get(uid);
        if (cache == null) {
            // بيانات مفقودة — إعادة التهيئة
            Vec3 lookFlat = new Vec3(player.getLookAngle().x, 0, player.getLookAngle().z).normalize();
            cache = new int[]{
                (int)Math.floor(pos.x + lookFlat.x * 8),
                (int)Math.floor(pos.z + lookFlat.z * 8),
                player.blockPosition().getY()
            };
            earthUpliftCache.put(uid, cache);
        }

        int centerX = cache[0];
        int centerZ = cache[1];
        int baseY   = cache[2];

        int halfSize = 5; // 10x10 area (from -5 to +4)
        int depth = 4;    // 4 بلوكات عمق
        int liftHeight = 20;

        if (tick <= 25) {
            // === المرحلة 1: ارتجاج درامي ===
            if (tick == 2) {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 2.0f, 0.3f);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6⬆ الأرض تتصدع تحت قوتك!"));
            }
            if (tick % 3 == 0) {
                level.playSound(null, centerX, baseY, centerZ, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4f + tick * 0.02f, 0.3f);
            }
            // جزيئات تصدع
            for (int i = 0; i < 12; i++) {
                double ox = centerX + (Math.random() - 0.5) * halfSize * 2;
                double oz = centerZ + (Math.random() - 0.5) * halfSize * 2;
                BlockPos checkPos = BlockPos.containing(ox, baseY - 1, oz);
                BlockState state = level.getBlockState(checkPos);
                if (!state.isAir()) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), ox, baseY + 0.5, oz, 5, 0.1, 0.6, 0.1, 0.12);
                }
            }
            // غبار يتصاعد
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, centerX, baseY + 1, centerZ, 5, halfSize, 0.5, halfSize, 0.02);
        } else if (tick == 26) {
            // === المرحلة 2: رفع البلوكات فعلياً! ===
            level.playSound(null, centerX, baseY, centerZ, SoundEvents.WITHER_BREAK_BLOCK, SoundSource.PLAYERS, 2.0f, 0.5f);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 2.5f, 0.2f);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6🗻 الجزيرة ترتفع!"));

            // نسخ البلوكات من الأسفل إلى الأعلى
            for (int dx = -halfSize; dx < halfSize; dx++) {
                for (int dz = -halfSize; dz < halfSize; dz++) {
                    for (int dy = 0; dy < depth; dy++) {
                        BlockPos srcPos = new BlockPos(centerX + dx, baseY - depth + dy, centerZ + dz);
                        BlockPos dstPos = new BlockPos(centerX + dx, baseY - depth + dy + liftHeight, centerZ + dz);
                        BlockState state = level.getBlockState(srcPos);
                        if (!state.isAir() && state.getDestroySpeed(level, srcPos) >= 0) {
                            level.setBlock(dstPos, state, 3);
                            level.setBlock(srcPos, Blocks.AIR.defaultBlockState(), 3);
                        }
                    }
                }
            }

            // جزيئات ضخمة عند الرفع
            for (int i = 0; i < 100; i++) {
                double ox = centerX + (Math.random() - 0.5) * halfSize * 2;
                double oz = centerZ + (Math.random() - 0.5) * halfSize * 2;
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, ox, baseY, oz, 3, 0.2, 1.0, 0.2, 0.05);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, ox, baseY + Math.random()*3, oz, 2, 0.3, 0.5, 0.3, 0.03);
            }
        } else if (tick > 26 && tick <= 55) {
            // === المرحلة 3: جزيئات ترافق الرفع + غبار ===
            double progress = (tick - 26) / 29.0;
            for (int i = 0; i < 8; i++) {
                double ox = centerX + (Math.random() - 0.5) * halfSize * 2;
                double oz = centerZ + (Math.random() - 0.5) * halfSize * 2;
                double py = baseY + progress * liftHeight;
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, ox, py, oz, 2, 0.5, 0.3, 0.5, 0.02);
            }
            // حافة الجزيرة تتساقط منها صخور
            if (tick % 4 == 0) {
                double edgeX = centerX + (Math.random() > 0.5 ? halfSize : -halfSize);
                double edgeZ = centerZ + (Math.random() - 0.5) * halfSize * 2;
                level.sendParticles(ParticleTypes.LARGE_SMOKE, edgeX, baseY + progress * liftHeight - 2, edgeZ, 5, 0.3, 1.0, 0.3, 0.08);
            }
        } else if (tick == 56) {
            // === المرحلة 4: اختفاء اللاعب وظهوره في الأعلى بأسلوب احترافي ===
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.5f, 0.5f);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ZOMBIE_ATTACK_IRON_DOOR, SoundSource.PLAYERS, 2.0f, 0.5f);
            
            // جزيئات اختفاء من الأسفل
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 1, pos.z, 40, 1.0, 1.0, 1.0, 0.05);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 1, pos.z, 20, 0.5, 0.5, 0.5, 0.1);
            
            double targetY = baseY + liftHeight + 1; // فوق الجزيرة
            
            // النقل الفوري للاعب
            player.setDeltaMovement(0, 0, 0);
            player.teleportTo(centerX + 0.5, targetY, centerZ + 0.5);
            player.hurtMarked = true;
            
            // جزيئات ظهور في الأعلى (انفجار صخري)
            level.playSound(null, centerX + 0.5, targetY, centerZ + 0.5, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.5f, 0.8f);
            level.sendParticles(ParticleTypes.EXPLOSION, centerX + 0.5, targetY, centerZ + 0.5, 1, 0, 0, 0, 0);
            level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, centerX + 0.5, targetY, centerZ + 0.5, 50, 2.0, 1.0, 2.0, 0.1);
            
            // هجوم على الكائنات القريبة (باستثناء اللاعبين)
            double attackRadius = 8.0;
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(attackRadius))) {
                if (e != player && e.isAlive() && !(e instanceof net.minecraft.world.entity.player.Player)) {
                    e.hurt(level.damageSources().playerAttack(player), 15.0f);
                    Vec3 push = e.position().subtract(player.position()).normalize().scale(1.5);
                    e.setDeltaMovement(push.x, 0.8, push.z);
                    e.hurtMarked = true;
                }
            }
            
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§6🪨 انتقال صخري!"));
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 5, 0, false, false, false));
            earthUpliftCache.remove(player.getUUID()); // تنظيف الكاش
            return 0; // انتهى
        } else if (tick > 56) {
            return 0;
        }
        return tick;
    }

    // =====================================================================
    //  قفص الجليد - مُحسَّن
    // =====================================================================
    private static int tickIceCage(ServerPlayer player, ServerLevel level, Vec3 pos, int tick) {
        LivingEntity target = findNearestTarget(player, level, 15.0);
        if (target == null) {
            if (tick == 2) player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§cلا يوجد هدف قريب!"));
            return 0;
        }
        Vec3 targetPos = target.position();

        if (tick <= 20) {
            // ماء يتجه من اللاعب للهدف
            if (tick == 2) {
                level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.PLAYERS, 1.5f, 1.5f);
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§b❄ تجميد الهدف..."));
            }
            double progress = tick / 20.0;
            for (int i = 0; i < 8; i++) {
                double px = pos.x + (targetPos.x - pos.x) * progress + (Math.random() - 0.5) * (1 - progress) * 2;
                double py = pos.y + 1 + (targetPos.y - pos.y) * progress + Math.sin(tick * 0.5 + i) * 0.4;
                double pz = pos.z + (targetPos.z - pos.z) * progress + (Math.random() - 0.5) * (1 - progress) * 2;
                level.sendParticles(ParticleTypes.FALLING_WATER, px, py, pz, 1, 0.03, 0.03, 0.03, 0.005);
                level.sendParticles(ParticleTypes.SPLASH, px, py, pz, 1, 0.01, 0.01, 0.01, 0.005);
            }
        } else if (tick <= 40) {
            // رفع الهدف مع دوامة ماء
            target.setDeltaMovement(0, 0.25, 0);
            target.hurtMarked = true;
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 127, false, false, false));
            double t = tick * 0.3;
            for (int i = 0; i < 8; i++) {
                double a = t + i * (Math.PI / 4);
                double r = 1.2;
                targetPos = target.position();
                level.sendParticles(ParticleTypes.FALLING_WATER, targetPos.x + r * Math.cos(a), targetPos.y + 1, targetPos.z + r * Math.sin(a), 2, 0.03, 0.08, 0.03, 0.008);
            }
            // تأثير تجمد تدريجي
            level.sendParticles(ParticleTypes.SNOWFLAKE, targetPos.x, targetPos.y + 1, targetPos.z, 3, 0.5, 0.8, 0.5, 0.02);
        } else if (tick == 41) {
            // تجميد!
            level.playSound(null, targetPos.x, targetPos.y, targetPos.z, SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 1.5f, 0.5f);
            target.setDeltaMovement(0, 0, 0);
            target.hurtMarked = true;
            targetPos = target.position();
            BlockPos tPos = target.blockPosition();
            // قفص جليدي
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos icePos = tPos.offset(dx, dy, dz);
                        if (level.getBlockState(icePos).isAir() || !level.getFluidState(icePos).isEmpty()) {
                            if (Math.abs(dx) == 1 || Math.abs(dz) == 1 || dy == 0 || dy == 2) {
                                level.setBlock(icePos, Blocks.PACKED_ICE.defaultBlockState(), 3);
                            }
                        }
                    }
                }
            }
            target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 20 * 10, 127, false, false, false));
            target.hurt(level.damageSources().freeze(), 10.0f);
            level.sendParticles(ParticleTypes.SNOWFLAKE, targetPos.x, targetPos.y + 1, targetPos.z, 40, 1.5, 1.5, 1.5, 0.08);
            level.sendParticles(ParticleTypes.ITEM_SNOWBALL, targetPos.x, targetPos.y + 1, targetPos.z, 20, 1.0, 1.0, 1.0, 0.05);
        } else if (tick > 50) {
            return 0;
        }
        return tick;
    }

    // =====================================================================
    //  جدار النار - مُحسَّن
    // =====================================================================
    private static int tickFireWall(ServerPlayer player, ServerLevel level, Vec3 pos, int tick) {
        if (tick == 2) {
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 2.0f, 0.7f);
        }

        if (tick <= 70) {
            double radius = 3.5 + (tick / 70.0) * 3.5;
            double wallHeight = 4.0;

            for (int i = 0; i < 50; i++) {
                double angle = (i / 50.0) * Math.PI * 2 + tick * 0.12;
                double px = pos.x + radius * Math.cos(angle);
                double pz = pos.z + radius * Math.sin(angle);
                double py = pos.y + Math.random() * wallHeight;
                level.sendParticles(ParticleTypes.FLAME, px, py, pz, 1, 0.03, 0.08, 0.03, 0.008);
                if (Math.random() < 0.2) {
                    level.sendParticles(ParticleTypes.SMOKE, px, py + 0.3, pz, 1, 0.08, 0.08, 0.08, 0.003);
                }
                if (Math.random() < 0.1) {
                    level.sendParticles(ParticleTypes.LAVA, px, py, pz, 1, 0.03, 0.03, 0.03, 0.003);
                }
            }

            if (tick % 5 == 0) {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius + 1.5))) {
                    if (e != player && e.isAlive()) {
                        double dist = Math.sqrt(Math.pow(e.getX() - pos.x, 2) + Math.pow(e.getZ() - pos.z, 2));
                        if (Math.abs(dist - radius) < 2.0) {
                            e.igniteForSeconds(6);
                            e.hurt(level.damageSources().onFire(), 8.0f);
                        }
                    }
                }
            }
            if (tick % 20 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1.0f, 0.8f);
        } else if (tick <= 90) {
            double fadeR = 7.0 * (1.0 - (tick - 70) / 20.0);
            int particles = (int)(30 * (1.0 - (tick - 70) / 20.0));
            for (int i = 0; i < particles; i++) {
                double a = (i / (double)particles) * Math.PI * 2;
                level.sendParticles(ParticleTypes.FLAME, pos.x + fadeR * Math.cos(a), pos.y + Math.random() * 2.5, pos.z + fadeR * Math.sin(a), 1, 0.08, 0.08, 0.08, 0.015);
            }
        } else {
            return 0;
        }
        return tick;
    }

    // =====================================================================
    //  طيران الهواء - جديد كلياً!
    // =====================================================================
    @SuppressWarnings("deprecation")
    private static int tickAirFlight(ServerPlayer player, ServerLevel level, Vec3 pos, int tick, int currentStamina) {
        if (tick == 2) {
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 1.5f, 1.5f);
            // تفعيل الطيران
            if (player.gameMode.isSurvival()) {
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }
        }

        // جزيئات ريح مستمرة حول اللاعب أثناء الطيران
        if (tick % 2 == 0) {
            double time = tick * 0.2;
            for (int i = 0; i < 4; i++) {
                double a = time + i * (Math.PI / 2);
                double r = 1.2;
                level.sendParticles(ParticleTypes.CLOUD, pos.x + r * Math.cos(a), pos.y - 0.3, pos.z + r * Math.sin(a), 1, 0.05, 0.03, 0.05, 0.003);
            }
            // أثر وراء اللاعب
            level.sendParticles(ParticleTypes.GUST, pos.x, pos.y, pos.z, 1, 0.3, 0.1, 0.3, 0.01);
        }

        // صوت رياح متكرر
        if (tick % 40 == 0) {
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.ENDER_DRAGON_FLAP, SoundSource.PLAYERS, 0.3f, 2.0f);
        }

        // إنهاء الطيران عند نفاد المانا
        if (currentStamina <= 0) {
            if (player.gameMode.isSurvival()) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 8, 0, false, false, false));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§f🌪️ نفدت الطاقة! هبوط آمن..."));
            level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y, pos.z, 30, 2.0, 0.5, 2.0, 0.05);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WIND_CHARGE_BURST.value(), SoundSource.PLAYERS, 1.0f, 0.5f);
            return -1; // إشارة نفاد المانا
        }

        // الطيران يستمر ما دام اللاعب يمتلك مانا
        return tick;
    }

    // =====================================================================
    //  قصف نوراني - مُحسَّن
    // =====================================================================
    private static int tickLightBarrage(ServerPlayer player, ServerLevel level, Vec3 pos, int tick) {
        if (tick == 2) {
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0f, 1.5f);
        }

        if (tick <= 90) {
            // إطلاق شعاع كل 12 tick (7-8 ضربات)
            if (tick % 12 == 0) {
                java.util.List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(22));
                targets.removeIf(e -> e == player || !e.isAlive());

                Vec3 strikePos;
                if (!targets.isEmpty()) {
                    LivingEntity target = targets.get(level.random.nextInt(targets.size()));
                    strikePos = target.position();
                    target.hurt(level.damageSources().magic(), 16.0f);
                    target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false, false));
                    target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 1, false, false, false));
                } else {
                    strikePos = pos.add((Math.random() - 0.5) * 18, 0, (Math.random() - 0.5) * 18);
                }

                // عمود نور ضخم من السماء
                for (int y = 0; y < 35; y++) {
                    level.sendParticles(ParticleTypes.END_ROD, strikePos.x, strikePos.y + y, strikePos.z, 3, 0.15, 0.08, 0.15, 0.008);
                }
                // انفجار عند الأرض
                level.sendParticles(ParticleTypes.END_ROD, strikePos.x, strikePos.y + 1, strikePos.z, 25, 2.0, 0.5, 2.0, 0.08);
                level.sendParticles(ParticleTypes.FLASH, strikePos.x, strikePos.y + 1, strikePos.z, 1, 0, 0, 0, 0);
                level.playSound(null, strikePos.x, strikePos.y, strikePos.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.8f, 2.0f);
            }

            // هالة دوارة حول اللاعب
            if (tick % 2 == 0) {
                double t = tick * 0.25;
                for (int i = 0; i < 6; i++) {
                    double a = t + i * (Math.PI / 3);
                    level.sendParticles(ParticleTypes.END_ROD, pos.x + 1.5 * Math.cos(a), pos.y + 2.5, pos.z + 1.5 * Math.sin(a), 1, 0, 0, 0, 0);
                }
            }
        } else {
            return 0;
        }
        return tick;
    }

    // =====================================================================
    //  استنزاف الأرواح - مُحسَّن
    // =====================================================================
    private static int tickSoulDrain(ServerPlayer player, ServerLevel level, Vec3 pos, int tick) {
        if (tick == 2) {
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WITHER_AMBIENT, SoundSource.PLAYERS, 1.5f, 0.5f);
        }

        if (tick <= 90) {
            double radius = 14.0;
            double time = tick * 0.2;

            // دوامة ظلام حول اللاعب
            for (int i = 0; i < 10; i++) {
                double a = time + i * (Math.PI / 5);
                double r = 2.2 + Math.sin(time + i * 0.6) * 0.4;
                level.sendParticles(ParticleTypes.REVERSE_PORTAL, pos.x + r * Math.cos(a), pos.y + 1.5, pos.z + r * Math.sin(a), 2, 0.03, 0.08, 0.03, 0.015);
                level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, pos.x + r * Math.cos(a), pos.y + 1, pos.z + r * Math.sin(a), 1, 0.03, 0.03, 0.03, 0.008);
            }

            // استنزاف
            if (tick % 8 == 0) {
                float totalDrain = 0;
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius))) {
                    if (e != player && e.isAlive()) {
                        float drain = 5.0f;
                        e.hurt(level.damageSources().wither(), drain);
                        totalDrain += drain;

                        // خط جزيئات أرواح من العدو للاعب
                        Vec3 ePos = e.position();
                        for (double t = 0; t < 1.0; t += 0.08) {
                            double lx = ePos.x + (pos.x - ePos.x) * t;
                            double ly = ePos.y + 1 + (pos.y + 1 - ePos.y - 1) * t;
                            double lz = ePos.z + (pos.z - ePos.z) * t;
                            level.sendParticles(ParticleTypes.REVERSE_PORTAL, lx, ly, lz, 1, 0.03, 0.03, 0.03, 0.008);
                            level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, lx, ly, lz, 1, 0.02, 0.02, 0.02, 0.005);
                        }
                        e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, false, false, false));
                        e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 40, 1, false, false, false));
                    }
                }
                if (totalDrain > 0) {
                    player.heal(totalDrain * 0.5f);
                    level.sendParticles(ParticleTypes.HEART, pos.x, pos.y + 2.2, pos.z, 3, 0.3, 0.3, 0.3, 0.05);
                }
            }

            if (tick % 25 == 0) level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.PLAYERS, 0.6f, 0.3f);
        } else {
            return 0;
        }
        return tick;
    }

    // =====================================================================
    //  أدوات مساعدة
    // =====================================================================
    private static LivingEntity findNearestTarget(ServerPlayer player, ServerLevel level, double range) {
        LivingEntity nearest = null;
        double minDist = range;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range))) {
            if (e != player && e.isAlive()) {
                double d = e.distanceTo(player);
                if (d < minDist) { minDist = d; nearest = e; }
            }
        }
        return nearest;
    }

    private static void spawnChargeParticles(ServerPlayer player, String element, int ticks) {
        ServerLevel level = player.serverLevel();
        double px = player.getX();
        double py = player.getY() + 2.5 + Math.sin(ticks * 0.2) * 0.3;
        double pz = player.getZ();
        double time = ticks * 0.15;

        for (int i = 0; i < 3; i++) {
            double angle = time * 4 + i * (Math.PI * 2 / 3);
            double r = 0.5 + ticks * 0.015;
            double orbX = px + r * Math.cos(angle);
            double orbZ = pz + r * Math.sin(angle);

            switch (element) {
                case "water" -> {
                    level.sendParticles(ParticleTypes.FALLING_WATER, orbX, py, orbZ, 2, 0.05, 0.05, 0.05, 0.01);
                    level.sendParticles(ParticleTypes.SPLASH, orbX, py - 0.2, orbZ, 1, 0.02, 0.02, 0.02, 0.01);
                }
                case "fire" -> {
                    level.sendParticles(ParticleTypes.FLAME, orbX, py, orbZ, 2, 0.03, 0.03, 0.03, 0.01);
                    level.sendParticles(ParticleTypes.SMALL_FLAME, orbX, py + 0.1, orbZ, 1, 0.02, 0.02, 0.02, 0.005);
                }
                case "earth" -> {
                    level.sendParticles(ParticleTypes.WAX_OFF, orbX, py, orbZ, 2, 0.1, 0.1, 0.1, 0.01);
                    level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, orbX, py, orbZ, 1, 0.05, 0.05, 0.05, 0.005);
                }
                case "air" -> {
                    level.sendParticles(ParticleTypes.CLOUD, orbX, py, orbZ, 2, 0.05, 0.05, 0.05, 0.005);
                    level.sendParticles(ParticleTypes.GUST, orbX, py, orbZ, 1, 0, 0, 0, 0);
                }
                case "light" -> {
                    level.sendParticles(ParticleTypes.END_ROD, orbX, py, orbZ, 2, 0.03, 0.03, 0.03, 0.01);
                }
                case "dark" -> {
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, orbX, py, orbZ, 3, 0.05, 0.05, 0.05, 0.02);
                    level.sendParticles(ParticleTypes.SCULK_CHARGE_POP, orbX, py, orbZ, 1, 0.03, 0.03, 0.03, 0.01);
                }
            }
        }
    }

    private static void spawnSpiralParticles(ServerLevel level, net.minecraft.core.particles.ParticleOptions particle, double cx, double cy, double cz, double radius, int count, double offset) {
        for (int i = 0; i < count; i++) {
            double angle = (i / (double)count) * Math.PI * 2 + offset;
            double x = cx + radius * Math.cos(angle);
            double z = cz + radius * Math.sin(angle);
            level.sendParticles(particle, x, cy + Math.sin(angle * 3) * 0.2, z, 1, 0, 0.05, 0, 0.02);
        }
    }

    private static void damageNearby(ServerPlayer player, double radius, float damage, boolean knockback) {
        ServerLevel level = player.serverLevel();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius))) {
            if (entity != player && entity.isAlive()) {
                entity.hurt(level.damageSources().playerAttack(player), damage);
                if (knockback) {
                    Vec3 push = entity.position().subtract(player.position()).normalize().scale(2.0);
                    entity.setDeltaMovement(push.x, 0.7, push.z);
                    entity.hurtMarked = true;
                }
            }
        }
    }

    private static void damageInLine(ServerPlayer player, double length, float damage) {
        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        for (double i = 0; i < length; i += 0.5) {
            Vec3 p = start.add(look.scale(i));
            level.sendParticles(ParticleTypes.END_ROD, p.x, p.y, p.z, 2, 0.1, 0.1, 0.1, 0.01);
            AABB box = new AABB(p.x-1, p.y-1, p.z-1, p.x+1, p.y+1, p.z+1);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (e != player && e.isAlive()) e.hurt(level.damageSources().magic(), damage);
            }
        }
    }

    private static void spawnCircleParticles(ServerLevel level, net.minecraft.core.particles.ParticleOptions particle, Vec3 center, double radius, int count) {
        for (int i = 0; i < count; i++) {
            double angle = (i / (double)count) * Math.PI * 2;
            double x = center.x + radius * Math.cos(angle);
            double z = center.z + radius * Math.sin(angle);
            level.sendParticles(particle, x, center.y + 0.1, z, 1, 0, 0.1, 0, 0.05);
        }
    }

    @SuppressWarnings("deprecation")
    private static void handleFlight(ServerPlayer player, int ticks) {
        if (ticks > 10 && !player.getAbilities().mayfly && player.gameMode.isSurvival()) {
            player.getAbilities().mayfly = true;
            player.onUpdateAbilities();
        }
        if (ticks <= 0 && player.gameMode.isSurvival()) {
            player.getAbilities().mayfly = false;
            player.getAbilities().flying = false;
            player.onUpdateAbilities();
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 20 * 10, 0, false, false, false));
        }
    }

    private static void updatePlayerData(ServerPlayer player, PlayerElementData data) {
        player.setData(ModAttachmentTypes.ELEMENT_DATA, data);
        syncToClient(player, data);
    }

    private static void syncToClient(ServerPlayer player, PlayerElementData data) {
        PacketDistributor.sendToPlayer(player, new SyncElementPayload(
            data.currentElement(), data.stamina(), data.maxStamina(), data.abilityCooldown(),
            data.flightTicks(), data.isCharging(), data.chargeTicks(), data.idleTicks(),
            data.idlePhase(), data.specialAbilityTicks(), data.specialAbilityType(),
            data.activeAbility()
        ));
    }

    private static void handleLegacyAbilities(ServerPlayer player, PlayerElementData data) {
        if (shadowTargets.containsKey(player.getUUID())) {
            java.util.UUID targetId = shadowTargets.get(player.getUUID());
            ServerLevel level = player.serverLevel();
            Entity target = level.getEntity(targetId);
            if (target == null || !target.isAlive() || player.getCamera() == player) {
                player.setCamera(player);
                if (player.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR) {
                    player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                }
                if (target != null) {
                    Vec3 look = target.getLookAngle();
                    player.teleportTo(level, target.getX() - look.x, target.getY() + 1, target.getZ() - look.z, target.getYRot(), target.getXRot());
                }
                shadowTargets.remove(player.getUUID());
            } else {
                player.teleportTo(target.getX(), target.getY(), target.getZ());
            }
        }

        if (!abilityStates.containsKey(player.getUUID())) return;
        int state = abilityStates.get(player.getUUID());
        if (state < 0) { state++; }
        if (state == 0) abilityStates.remove(player.getUUID());
        else abilityStates.put(player.getUUID(), state);
    }

    // =====================================================================
    //  نظام القلاع الخيالية — النسخة الأسطورية النهائية
    // =====================================================================
    private static void buildGrandCastle(ServerPlayer player, String element) {
        ServerLevel level = player.serverLevel();
        BlockPos ground = player.blockPosition();
        
        // 1. تحديد مكان القاعدة حسب العنصر
        BlockPos base;
        switch (element) {
            case "earth" -> base = ground.below(25); // تحت الأرض بـ 25 بلوك
            case "light" -> base = ground.above(65); // في السماء بـ 65 بلوك
            case "air"   -> base = ground.above(35); // مرتفع بـ 35 بلوك
            case "dark"  -> base = ground.above(12); // فوق الأرض قليلاً (سيغطى بجبل)
            default      -> base = ground.above(10);
        }
        
        BlockPos safeSpawn = base.offset(0, 2, -8);
        activeCastles.put(player.getUUID(), new GrandCastleData(base, element, 100, System.currentTimeMillis()));
        player.teleportTo(safeSpawn.getX() + 0.5, safeSpawn.getY(), safeSpawn.getZ() + 0.5);
        
        level.playSound(null, ground.getX(), ground.getY(), ground.getZ(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 2f, 0.5f);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§l§6🏰 تشييد القلعة الأسطورية لـ " + element.toUpperCase() + "..."));

        // 2. تنظيف المنطقة المحيطة بالـ base ( Cave / Sky / Field )
        for (int x=-20; x<=20; x++)
            for (int z=-20; z<=20; z++)
                for (int y=-2; y<30; y++)
                    level.setBlock(base.offset(x,y,z), Blocks.AIR.defaultBlockState(), 3);

        // 3. بناء جبل تمويهي لقلعة الظلام
        if (element.equals("dark")) {
            for (int x=-22; x<=22; x++) for (int z=-22; z<=22; z++) {
                double dist = Math.sqrt(x*x + z*z);
                int h = (int)(28 - dist * 1.2);
                if (h > 0) {
                    for (int y=0; y<h; y++) {
                        level.setBlock(base.offset(x, y-5, z), y > h-3 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
            // إعادة تنظيف داخل الجبل للقلعة
            for (int x=-13; x<=13; x++) for (int z=-13; z<=13; z++) for (int y=0; y<25; y++)
                level.setBlock(base.offset(x,y,z), Blocks.AIR.defaultBlockState(), 3);
        }

        // 4. البناء الهيكلي المطور حسب العنصر
        switch (element) {
            case "water" -> {
                buildNorthernWaterCastle(level, base);
                transformTerrainForWaterCastle(level, base);
            }
            case "fire" -> {
                buildFireNationCastle(level, base);
                transformTerrainForFireCastle(level, base);
            }
            case "light" -> {
                buildCelestialLightCastle(level, base);
                transformTerrainForLightCastle(level, base);
            }
            case "air" -> {
                buildAirTempleCastle(level, base);
                transformTerrainForAirCastle(level, base);
            }
            case "earth" -> {
                buildEarthKingdomCastle(level, base);
                transformTerrainForEarthCastle(level, base);
            }
            case "dark" -> {
                buildShadowKingdomCastle(level, base);
                transformTerrainForDarkCastle(level, base);
            }
            default -> {
                buildPlatform(level, ground, base, element);
                buildFantasyCastle(level, base, element);
                buildMajesticEntrance(level, base, element);
            }
        }
        
        buildElementAura(level, base, element);
        spawnCastleGarrison(player, level, base, element);
    }

    private static void buildPlatform(ServerLevel level, BlockPos ground, BlockPos base, String e) {
        BlockState col = wallOf(e), slab = accOf(e);
        // لا نبني أعمدة للأرض (تحت الأرض) أو الظلام (داخل الجبل)
        if (!e.equals("earth") && !e.equals("dark")) {
            // أعمدة رئيسية في كل زاوية (3x3)
            for (int cx : new int[]{-11,11}) for (int cz : new int[]{-11,11}) {
                int startY = ground.getY();
                int endY = base.getY() - 1;
                for (int y = Math.min(startY, endY); y <= Math.max(startY, endY); y++) {
                    for (int dx=-1; dx<=1; dx++) for (int dz=-1; dz<=1; dz++) {
                        level.setBlock(new BlockPos(ground.getX()+cx+dx, y, ground.getZ()+cz+dz), (y-startY)%3==2?slab:col, 3);
                    }
                }
            }
        }
        // منصة القاعدة الكاملة
        for (int x=-12; x<=12; x++) for (int z=-12; z<=12; z++)
            level.setBlock(base.offset(x,-1,z), col, 3);
    }


    private static void buildFantasyCastle(ServerLevel level, BlockPos c, String e) {
        BlockState W=wallOf(e), A=accOf(e), F=floorOf(e), S=stairOf(e);
        int SZ=11, FH=7, FL=3; // قلعة 23x23، 3 طوابق كل طابق 7 بلوكات ارتفاع
        for (int f=0;f<FL;f++) {
            int yb=f*FH;
            // ── أرضية الطابق ──
            for (int x=-SZ+1;x<SZ;x++) for (int z=-SZ+1;z<SZ;z++) {
                // نمط متشابك للأرضية
                boolean checker = (Math.abs(x)+Math.abs(z))%2==0;
                level.setBlock(c.offset(x,yb,z), checker?F:W, 3);
            }
            // ── جدران خارجية مزخرفة ──
            for (int x=-SZ;x<=SZ;x++) for (int z=-SZ;z<=SZ;z++) {
                if (!(Math.abs(x)==SZ||Math.abs(z)==SZ)) continue;
                for (int y=1;y<=FH;y++) {
                    BlockPos wp = c.offset(x,yb+y,z);
                    // باب مدخل رئيسي (وجه -Z، عرض 3)
                    if (f==0&&z==-SZ&&Math.abs(x)<=1&&y<=4){level.setBlock(wp,Blocks.AIR.defaultBlockState(),3);continue;}
                    // فتحة سلم بين الطوابق (ركن +X/+Z)
                    if (f>0&&x==SZ&&Math.abs(z-SZ)<=1&&y<=3){level.setBlock(wp,Blocks.AIR.defaultBlockState(),3);continue;}
                    // نوافذ خيالية (مخروطية)
                    if ((y==3||y==4)&&(Math.abs(x)%3==0||Math.abs(z)%3==0))
                        {level.setBlock(wp,Blocks.GLASS_PANE.defaultBlockState(),3);continue;}
                    // تلاعب زخرفي: بلوكات بارزة في كل 4
                    if (y==2&&(Math.abs(x)%4==0||Math.abs(z)%4==0))
                        {level.setBlock(wp,A,3);continue;}
                    level.setBlock(wp, y==FH?A:W, 3);
                }
            }
            // ── مسننات فوق كل طابق ──
            for (int i=-SZ;i<=SZ;i++) {
                if (i%2==0) {
                    level.setBlock(c.offset(i,yb+FH+1,-SZ), A, 3);
                    level.setBlock(c.offset(i,yb+FH+1,SZ), A, 3);
                    level.setBlock(c.offset(-SZ,yb+FH+1,i), A, 3);
                    level.setBlock(c.offset(SZ,yb+FH+1,i), A, 3);
                }
            }
            // ── سلم داخلي مستقيم بعرض 3 (ركن +X,+Z) ──
            if (f < FL-1) {
                // موضع السلم: من (SZ-3, yb, SZ-3) إلى أعلى
                int sx=SZ-3, sz=SZ-3;
                // فتح مسار السلم أولاً (مسح الهواء)
                for (int h=0;h<=FH+2;h++) for (int dx=-1;dx<=1;dx++)
                    level.setBlock(c.offset(sx+dx, yb+h, sz+h<FH?sz+h:sz+FH-1), Blocks.AIR.defaultBlockState(), 3);
                // درجات السلم
                for (int h=0;h<FH;h++) for (int dx=-1;dx<=1;dx++)
                    level.setBlock(c.offset(sx+dx, yb+h, sz+h), S, 3);
                // فتحة 3x3 في سقف الطابق التالي
                for (int dx=-1;dx<=1;dx++) for (int dz=0;dz<=2;dz++)
                    level.setBlock(c.offset(sx+dx, yb+FH, sz+FH-1+dz), Blocks.AIR.defaultBlockState(), 3);
                // منارة عند السلم
                level.setBlock(c.offset(sx-2, yb+1, sz), Blocks.LANTERN.defaultBlockState(), 3);
            }
            // ── تفاصيل داخلية ──
            addFloorDetails(level, c, yb, F, A, W, e, f);
        }
        // ── أبراج الزوايا الضخمة ──
        for (int tx:new int[]{-SZ,SZ}) for (int tz:new int[]{-SZ,SZ}) {
            for (int y=-1;y<=FL*FH+8;y++) for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) {
                boolean isTop = y>=FL*FH+6;
                boolean isMid = y>=FL*FH&&y<FL*FH+6;
                level.setBlock(c.offset(tx+dx,y,tz+dz), isTop?Blocks.LANTERN.defaultBlockState():isMid?A:W, 3);
            }
            // شرفة البرج
            for (int dx=-2;dx<=2;dx++) for (int dz=-2;dz<=2;dz++)
                if (Math.abs(dx)==2||Math.abs(dz)==2)
                    level.setBlock(c.offset(tx+dx, FL*FH, tz+dz), A, 3);
            // مخروط البرج
            for (int y=1;y<=4;y++) for (int dx=-(2-y/2);dx<=(2-y/2);dx++) for (int dz=-(2-y/2);dz<=(2-y/2);dz++)
                level.setBlock(c.offset(tx+dx, FL*FH+6+y, tz+dz), A, 3);
        }
    }

    /** تفاصيل داخلية لكل طابق: عمود مركزي، فوانيس، صناديق */
    private static void addFloorDetails(ServerLevel level, BlockPos c, int yb,
            BlockState fl, BlockState acc, BlockState wall, String e, int floor) {
        // أعمدة داخلية في كل طابق
        for (int px:new int[]{-6,6}) for (int pz:new int[]{-6,6}) {
            for (int y=1;y<=5;y++) level.setBlock(c.offset(px,yb+y,pz), acc, 3);
            level.setBlock(c.offset(px,yb+6,pz), Blocks.LANTERN.defaultBlockState(), 3);
        }
        // سرير/عرش في آخر طابق
        if (floor==2) {
            level.setBlock(c.offset(0,yb+1,5), acc, 3); // عرش
            level.setBlock(c.offset(0,yb+2,5), acc, 3);
            level.setBlock(c.offset(-1,yb+1,5), wall, 3);
            level.setBlock(c.offset(1,yb+1,5), wall, 3);
            level.setBlock(c.offset(0,yb+3,5), Blocks.LANTERN.defaultBlockState(), 3);
        }
        // فوانيس على الجدران
        for (int i=-8;i<=8;i+=4) {
            level.setBlock(c.offset(i,yb+5,-10), Blocks.LANTERN.defaultBlockState(), 3);
            level.setBlock(c.offset(i,yb+5,10), Blocks.LANTERN.defaultBlockState(), 3);
            level.setBlock(c.offset(-10,yb+5,i), Blocks.LANTERN.defaultBlockState(), 3);
            level.setBlock(c.offset(10,yb+5,i), Blocks.LANTERN.defaultBlockState(), 3);
        }
    }

    private static void buildMajesticEntrance(ServerLevel level, BlockPos base, String e) {
        BlockState S=stairOf(e), W=wallOf(e), A=accOf(e);
        // 10 درجات عريضة (7 بلوكات) تنزل من القلعة للأرض
        for (int step=0;step<10;step++) {
            for (int dx=-3;dx<=3;dx++) {
                level.setBlock(base.offset(dx,-step,-12-step), S, 3);
                for (int dy=1;dy<=step;dy++) level.setBlock(base.offset(dx,-step-dy,-12-step), W, 3);
            }
            // حواجز جانبية
            level.setBlock(base.offset(-4,-step,-12-step), A, 3);
            level.setBlock(base.offset(4,-step,-12-step), A, 3);
        }
        // بوابة ضخمة فوق المدخل
        for (int y=5;y<=9;y++) {
            level.setBlock(base.offset(-2,y,-11), W, 3);
            level.setBlock(base.offset(2,y,-11), W, 3);
        }
        for (int dx=-2;dx<=2;dx++) level.setBlock(base.offset(dx,9,-11), A, 3);
        // تماثيل ضخمة على جانبَي الدرج
        buildElementStatue(level, base.offset(-5, 0, -13), e);
        buildElementStatue(level, base.offset(5, 0, -13), e);
    }

    private static void buildElementStatue(ServerLevel level, BlockPos b, String e) {
        BlockState body,glow,top;
        switch(e) {
            case "fire"  ->{body=Blocks.NETHER_BRICKS.defaultBlockState();glow=Blocks.FIRE.defaultBlockState();top=Blocks.SHROOMLIGHT.defaultBlockState();}
            case "water" ->{body=Blocks.PRISMARINE_BRICKS.defaultBlockState();glow=Blocks.BLUE_ICE.defaultBlockState();top=Blocks.SEA_LANTERN.defaultBlockState();}
            case "earth" ->{body=Blocks.DEEPSLATE_BRICKS.defaultBlockState();glow=Blocks.SHROOMLIGHT.defaultBlockState();top=Blocks.MOSSY_COBBLESTONE.defaultBlockState();}
            case "light" ->{body=Blocks.GOLD_BLOCK.defaultBlockState();glow=Blocks.SEA_LANTERN.defaultBlockState();top=Blocks.GLOWSTONE.defaultBlockState();}
            case "dark"  ->{body=Blocks.OBSIDIAN.defaultBlockState();glow=Blocks.SOUL_LANTERN.defaultBlockState();top=Blocks.CRYING_OBSIDIAN.defaultBlockState();}
            default      ->{body=Blocks.QUARTZ_BRICKS.defaultBlockState();glow=Blocks.SEA_LANTERN.defaultBlockState();top=Blocks.GLOWSTONE.defaultBlockState();}
        }
        // قاعدة 3x3
        for (int dx=-1;dx<=1;dx++) for (int dz=-1;dz<=1;dz++) level.setBlock(b.offset(dx,-1,dz),body,3);
        // جذع التمثال
        for (int y=0;y<6;y++) level.setBlock(b.above(y), y==5?top:body, 3);
        // ذراعان عريضتان
        level.setBlock(b.above(4).east(1), body, 3); level.setBlock(b.above(4).east(2), glow, 3);
        level.setBlock(b.above(4).west(1), body, 3); level.setBlock(b.above(4).west(2), glow, 3);
        // إضاءة أسفل التمثال
        level.setBlock(b.east(1), glow, 3); level.setBlock(b.west(1), glow, 3);
        level.setBlock(b.above(6), glow, 3);
    }

    private static void buildElementAura(ServerLevel level, BlockPos base, String e) {
        switch(e) {
            case "water" -> {
                for (int x=-14;x<=14;x++) for (int z=-14;z<=14;z++) {
                    double d=Math.sqrt(x*x+z*z);
                    if (d>12&&d<14) { level.setBlock(base.offset(x,-1,z),Blocks.WATER.defaultBlockState(),3); level.setBlock(base.offset(x,-2,z),Blocks.PRISMARINE.defaultBlockState(),3); }
                }
            }
            case "fire" -> {
                for (int i=-13;i<=13;i+=2) {
                    level.setBlock(base.offset(i,-1,-14),Blocks.LAVA.defaultBlockState(),3);
                    level.setBlock(base.offset(i,-1,14),Blocks.LAVA.defaultBlockState(),3);
                    level.setBlock(base.offset(-14,-1,i),Blocks.LAVA.defaultBlockState(),3);
                    level.setBlock(base.offset(14,-1,i),Blocks.LAVA.defaultBlockState(),3);
                }
            }
            case "earth" -> {
                var rng=new java.util.Random(base.hashCode());
                for (int i=0;i<40;i++) {
                    int ox=rng.nextInt(28)-14, oz=rng.nextInt(28)-14, oy=rng.nextInt(6)-2;
                    if (Math.abs(ox)>12||Math.abs(oz)>12) {
                        level.setBlock(base.offset(ox,oy,oz),Blocks.MOSSY_COBBLESTONE.defaultBlockState(),3);
                        level.setBlock(base.offset(ox,oy+1,oz),Blocks.GRASS_BLOCK.defaultBlockState(),3);
                    }
                }
            }
            case "light" -> {
                for (int ox:new int[]{-13,13}) for (int oz:new int[]{-13,13})
                    for (int y=0;y<12;y++) level.setBlock(base.offset(ox,y,oz), y%4==3?Blocks.SEA_LANTERN.defaultBlockState():Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            }
            case "dark" -> {
                for (int ox:new int[]{-13,13}) for (int oz:new int[]{-13,13})
                    for (int y=0;y<12;y++) level.setBlock(base.offset(ox,y,oz), y%5==4?Blocks.SOUL_LANTERN.defaultBlockState():Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
            case "air" -> {
                for (int i=-12;i<=12;i+=2) for (int y=0;y<5;y++) {
                    level.setBlock(base.offset(i,y,-14),Blocks.GLASS.defaultBlockState(),3);
                    level.setBlock(base.offset(i,y,14),Blocks.GLASS.defaultBlockState(),3);
                    level.setBlock(base.offset(-14,y,i),Blocks.GLASS.defaultBlockState(),3);
                    level.setBlock(base.offset(14,y,i),Blocks.GLASS.defaultBlockState(),3);
                }
            }
        }
    }

    private static void spawnCastleGarrison(ServerPlayer owner, ServerLevel level, BlockPos base, String e) {
        int count = switch (e) {
            case "water", "fire", "light", "air", "earth", "dark" -> 12; // زيادة عدد الحراس للقلاع الكبيرة
            default -> 8;
        };

        for (int i = 0; i < count; i++) {
            double angle = i * (Math.PI * 2 / count);
            int ox = (int) (22 * Math.cos(angle));
            int oz = (int) (22 * Math.sin(angle));
            spawnGuard(owner, level, base.offset(ox, 1, oz), i % 2 == 0, e);
        }

        // حراس داخليين للقصر
        spawnGuard(owner, level, base.offset(0, 5, 0), false, e);
        spawnGuard(owner, level, base.offset(3, 5, 3), true, e);
        spawnGuard(owner, level, base.offset(-3, 5, 3), true, e);

        // صناديق المعدات
        placeGearChest(level, base.offset(-5, 1, 5), 2);
    }

    private static void spawnGuard(ServerPlayer owner, ServerLevel level, BlockPos pos, boolean archer, String e) {
        net.minecraft.world.entity.EntityType<? extends LivingEntity> type;
        
        switch (e) {
            case "water" -> type = net.minecraft.world.entity.EntityType.DROWNED;
            case "fire"  -> type = net.minecraft.world.entity.EntityType.WITHER_SKELETON;
            case "light" -> type = net.minecraft.world.entity.EntityType.IRON_GOLEM;
            case "air"   -> type = net.minecraft.world.entity.EntityType.VILLAGER;
            case "earth" -> type = net.minecraft.world.entity.EntityType.IRON_GOLEM;
            case "dark"  -> type = net.minecraft.world.entity.EntityType.WITHER_SKELETON;
            default      -> type = archer ? net.minecraft.world.entity.EntityType.SKELETON : net.minecraft.world.entity.EntityType.WITHER_SKELETON;
        }

        Entity g = type.create(level);
        if (!(g instanceof LivingEntity le)) return;
        le.setPos(pos.getX()+0.5, pos.getY(), pos.getZ()+0.5);
        
        String name = switch (e) {
            case "water" -> "§b💧 حارس قبيلة الماء";
            case "fire"  -> "§c🔥 محارب أمة النار";
            case "light" -> "§e☀ فارس النور السماوي";
            case "air"   -> "§f🌪️ راهب معبد الهواء";
            case "earth" -> "§6🪨 جندي مملكة الأرض";
            case "dark"  -> "§8🌑 حاصد الأرواح";
            default      -> "§7حارس القلعة";
        };
        le.setCustomName(net.minecraft.network.chat.Component.literal(name));
        le.setCustomNameVisible(true);
        le.addTag("avatar_ally");

        // تخصيص الأسلحة والدروع
        if (e.equals("water")) {
            le.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TRIDENT));
        } else if (e.equals("air") && le instanceof net.minecraft.world.entity.npc.Villager) {
            // القرويون لا يهاجمون عادة، سنحولهم لكيان هجومي أو نكتفي بالمظهر
            le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, Integer.MAX_VALUE, 1));
        } else {
            le.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(archer?net.minecraft.world.item.Items.BOW:net.minecraft.world.item.Items.NETHERITE_SWORD));
        }

        if (le instanceof net.minecraft.world.entity.Mob mob) {
            mob.setPersistenceRequired();
            mob.targetSelector.getAvailableGoals().clear();
            mob.targetSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                mob, LivingEntity.class, true,
                t -> !t.getUUID().equals(owner.getUUID()) && !t.getTags().contains("avatar_ally")));
        }
        le.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Integer.MAX_VALUE, 1, false, false));
        level.addFreshEntity(le);
    }

    private static void placeGearChest(ServerLevel level, BlockPos pos, int tier) {
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity ch) {
            ch.setItem(0,new net.minecraft.world.item.ItemStack(tier==2?net.minecraft.world.item.Items.NETHERITE_SWORD:tier==1?net.minecraft.world.item.Items.DIAMOND_SWORD:net.minecraft.world.item.Items.IRON_SWORD));
            ch.setItem(1,new net.minecraft.world.item.ItemStack(tier==2?net.minecraft.world.item.Items.NETHERITE_CHESTPLATE:tier==1?net.minecraft.world.item.Items.DIAMOND_CHESTPLATE:net.minecraft.world.item.Items.IRON_CHESTPLATE));
            ch.setItem(2,new net.minecraft.world.item.ItemStack(tier==2?net.minecraft.world.item.Items.NETHERITE_HELMET:tier==1?net.minecraft.world.item.Items.DIAMOND_HELMET:net.minecraft.world.item.Items.IRON_HELMET));
            ch.setItem(3,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE, tier==2?8:tier==1?4:2));
            ch.setItem(4,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW, 64));
        }
    }

    // ── مساعدات الألوان ──────────────────────────────────────────────────
    // =====================================================================
    //  قلعة قبيلة الماء الشمالية (Northern Water Tribe) - تصميم شامل
    // =====================================================================
    private static void buildNorthernWaterCastle(ServerLevel level, BlockPos c) {
        BlockState ICE  = Blocks.PACKED_ICE.defaultBlockState();
        BlockState BLUE = Blocks.BLUE_ICE.defaultBlockState();
        BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState();
        BlockState SEA  = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState PRIM = Blocks.PRISMARINE_BRICKS.defaultBlockState();
        BlockState DARK = Blocks.DARK_PRISMARINE.defaultBlockState();
        BlockState GLASS= Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();

        // === 1. قاعدة جليدية واسعة (قطر 80 بلوكة) ===
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                double d = Math.sqrt(x*x + z*z);
                if (d <= 40) {
                    // أرضية متدرجة
                    level.setBlock(c.offset(x, -2, z), BLUE, 3);
                    level.setBlock(c.offset(x, -1, z), d > 38 ? SNOW : (d > 34 ? ICE : (d > 28 ? DARK : ICE)), 3);
                    // قنوات مائية دائرية (قناتان)
                    if (d > 21 && d < 23) level.setBlock(c.offset(x, -1, z), Blocks.WATER.defaultBlockState(), 3);
                    if (d > 31 && d < 33) level.setBlock(c.offset(x, -1, z), Blocks.WATER.defaultBlockState(), 3);
                    // جسور فوق القنوات
                    if ((d > 21 && d < 23) && (x == 0 || z == 0)) level.setBlock(c.offset(x, -1, z), ICE, 3);
                    if ((d > 31 && d < 33) && (x == 0 || z == 0)) level.setBlock(c.offset(x, -1, z), ICE, 3);
                }
            }
        }

        // === 2. السور الخارجي (دائرة R=37) مع أبراج ركنية ===
        for (int y = 0; y < 18; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(37 * Math.cos(rad));
                int z = (int)(37 * Math.sin(rad));
                // جدار السور - تناوب بلوكات
                BlockState wall = (y % 6 == 0) ? DARK : ICE;
                level.setBlock(c.offset(x, y, z), wall, 3);
                // الجانب الداخلي
                int xi = (int)(35 * Math.cos(rad));
                int zi = (int)(35 * Math.sin(rad));
                level.setBlock(c.offset(xi, y, zi), ICE, 3);
                // شرفات أعلى السور
                if (y == 17 && i % 4 == 0) level.setBlock(c.offset(x, 18, z), SNOW, 3);
            }
        }
        // فانوس على كل برج شرفات
        for (int i = 0; i < 360; i += 45) {
            double rad = Math.toRadians(i);
            int x = (int)(37 * Math.cos(rad));
            int z = (int)(37 * Math.sin(rad));
            for (int y = 0; y < 24; y++) level.setBlock(c.offset(x, y, z), ICE, 3);
            level.setBlock(c.offset(x, 24, z), SEA, 3);
        }

        // === 3. بوابة المدخل الرئيسية (جنوب) ===
        // قوس المدخل
        for (int y = 0; y < 12; y++) {
            level.setBlock(c.offset(-4, y, -37), ICE, 3);
            level.setBlock(c.offset(4, y, -37), ICE, 3);
        }
        for (int x = -4; x <= 4; x++) level.setBlock(c.offset(x, 12, -37), BLUE, 3);
        // تماثيل جانب البوابة
        buildWaterTribeStatue(level, c.offset(-7, 0, -36));
        buildWaterTribeStatue(level, c.offset(7, 0, -36));

        // === 4. الطريق المركزي (جسر يصل البوابة بالقصر) ===
        for (int z = -36; z < -12; z++) {
            for (int x = -2; x <= 2; x++) level.setBlock(c.offset(x, -1, z), PRIM, 3);
            // حواف الطريق
            level.setBlock(c.offset(-3, -1, z), DARK, 3);
            level.setBlock(c.offset(3, -1, z), DARK, 3);
            // فوانيس على جانبي الطريق كل 4 بلوكات
            if (z % 4 == 0) {
                buildLampPost(level, c.offset(-4, 0, z), ICE, SEA);
                buildLampPost(level, c.offset(4, 0, z), ICE, SEA);
            }
        }

        // === 5. السور الداخلي (دائرة R=20) ===
        for (int y = 0; y < 14; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(20 * Math.cos(rad));
                int z = (int)(20 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 5 == 0) ? BLUE : ICE, 3);
            }
        }
        // أبراج السور الداخلي (4 أبراج)
        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(i * 90 + 45);
            int bx = (int)(20 * Math.cos(rad));
            int bz = (int)(20 * Math.sin(rad));
            buildWaterTower(level, c.offset(bx, 0, bz), ICE, BLUE, SEA, 20);
        }

        // === 6. القصر المركزي ===
        // قاعدة القصر (مربع 16x16)
        for (int x = -8; x <= 8; x++)
            for (int z = -8; z <= 8; z++)
                level.setBlock(c.offset(x, -1, z), PRIM, 3);
        // جدران القصر
        for (int y = 0; y < 14; y++) {
            for (int x = -8; x <= 8; x++) {
                for (int z = -8; z <= 8; z++) {
                    boolean isWall = (x == -8 || x == 8 || z == -8 || z == 8);
                    if (isWall) {
                        level.setBlock(c.offset(x, y, z), (y % 7 == 0) ? DARK : ICE, 3);
                    } else if (y == 0) {
                        level.setBlock(c.offset(x, y, z), PRIM, 3);
                    }
                    // نوافذ
                    if (isWall && y == 5 && x % 3 == 0) level.setBlock(c.offset(x, y, z), GLASS, 3);
                    if (isWall && y == 5 && z % 3 == 0) level.setBlock(c.offset(x, y, z), GLASS, 3);
                }
            }
        }
        // مدخل القصر مع بوابات حقيقية
        placeDetailedDoor(level, c.offset(0, 0, -8), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(1, 0, -8), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(-1, 0, -8), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        level.setBlock(c.offset(0, 2, -8), GLASS, 3);
        level.setBlock(c.offset(1, 2, -8), GLASS, 3);
        level.setBlock(c.offset(-1, 2, -8), GLASS, 3);

        // === 7. القبة الزرقاء العملاقة فوق القصر ===
        buildSmoothDome(level, c.above(14), 11, GLASS, BLUE);
        // قمة القبة بفانوس
        level.setBlock(c.offset(0, 26, 0), SEA, 3);

        // إضافة جبال وأشواك جليدية عضوية حول السور لزيادة الواقعية
        for (int i = 0; i < 6; i++) {
            double rad = Math.toRadians(i * 60 + 15);
            buildJaggedSpire(level, c.offset((int)(40*Math.cos(rad)), 0, (int)(40*Math.sin(rad))), 30, 6, ICE, BLUE);
        }

        // === 8. أبراج القصر الأربعة ===
        int[][] tPos = {{-8,-8},{8,-8},{-8,8},{8,8}};
        for (int[] tp : tPos) buildWaterTower(level, c.offset(tp[0], 0, tp[1]), ICE, BLUE, SEA, 22);

        // === 9. إضاءة داخلية ===
        for (int x = -6; x <= 6; x += 3)
            for (int z = -6; z <= 6; z += 3)
                level.setBlock(c.offset(x, 0, z), SEA, 3);

        // === 10. المستوطنة الداخلية (بيوت سكان) ===
        // منازل دائرية داخل السور الخارجي
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45 + 22);
            int bx = (int)(26 * Math.cos(rad));
            int bz = (int)(26 * Math.sin(rad));
            buildWaterHouse(level, c.offset(bx, 0, bz), ICE, BLUE, SNOW);
        }
        // منازل في الحلقة الداخلية
        for (int i = 0; i < 6; i++) {
            double rad = Math.toRadians(i * 60);
            int bx = (int)(14 * Math.cos(rad));
            int bz = (int)(14 * Math.sin(rad));
            buildWaterHouse(level, c.offset(bx, 0, bz), PRIM, DARK, ICE);
        }

        // === 11. الميناء والسفن ===
        // ميناء شمالي (R=35-40, جهة Z-)
        for (int x = -8; x <= 8; x++) {
            level.setBlock(c.offset(x, -2, -38), Blocks.PACKED_ICE.defaultBlockState(), 3);
            level.setBlock(c.offset(x, -1, -38), Blocks.WATER.defaultBlockState(), 3);
        }
        // 3 سفن مائية خارج السور
        buildWaterShip(level, c.offset(-15, -2, -50), ICE, BLUE, PRIM);
        buildWaterShip(level, c.offset(0, -2, -55), ICE, BLUE, PRIM);
        buildWaterShip(level, c.offset(15, -2, -48), ICE, BLUE, PRIM);
        // سفينة صغيرة أمام الميناء
        buildWaterShip(level, c.offset(-5, -2, -42), ICE, DARK, PRIM);

        // === 12. السوق المائي (بجوار القناة) ===
        for (int x = -10; x <= 10; x++) {
            buildMarketStall(level, c.offset(x, 0, -24), ICE, SEA);
        }
    }

    /** يبني عمود فانوس جليدي */ 
    private static void buildLampPost(ServerLevel level, BlockPos p, BlockState pole, BlockState light) {
        for (int y = 0; y < 4; y++) level.setBlock(p.above(y), pole, 3);
        level.setBlock(p.above(4), light, 3);
    }

    /** يبني برجاً للقبيلة المائية */
    private static void buildWaterTower(ServerLevel level, BlockPos base, BlockState wall, BlockState accent, BlockState light, int height) {
        for (int y = 0; y < height; y++) {
            int r = (y < height - 6) ? 3 : Math.max(1, 3 - (y - (height-6)));
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    boolean isShell = (Math.abs(x)==r || Math.abs(z)==r);
                    if (isShell) level.setBlock(base.offset(x, y, z), (y % 6 == 0) ? accent : wall, 3);
                }
            }
        }
        level.setBlock(base.above(height), light, 3);
    }

    /** تمثال محارب قبيلة الماء */
    private static void buildWaterTribeStatue(ServerLevel level, BlockPos p) {
        for (int y = 0; y < 10; y++) level.setBlock(p.above(y), Blocks.PACKED_ICE.defaultBlockState(), 3);
        level.setBlock(p.above(10), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        level.setBlock(p.above(5).east(), Blocks.PACKED_ICE.defaultBlockState(), 3);
        level.setBlock(p.above(5).west(), Blocks.PACKED_ICE.defaultBlockState(), 3);
    }

    /** بيت بسيط للقبيلة المائية */
    private static void buildWaterHouse(ServerLevel level, BlockPos p, BlockState wall, BlockState roof, BlockState floor) {
        // قاعدة
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, -1, z), floor, 3);
        // جدران
        for (int y = 0; y < 4; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x)==2 || Math.abs(z)==2) level.setBlock(p.offset(x, y, z), wall, 3);
        // سقف
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setBlock(p.offset(x, 4, z), roof, 3);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, 5, z), roof, 3);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlock(p.offset(x, 6, z), roof, 3);
        // نافذة ومدخل حقيقي
        level.setBlock(p.offset(0, 1, 2), Blocks.BLUE_STAINED_GLASS_PANE.defaultBlockState(), 3);
        placeDetailedDoor(level, p.offset(0, 0, -2), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    /** سفينة مائية */
    private static void buildWaterShip(ServerLevel level, BlockPos p, BlockState hull, BlockState deck, BlockState mast) {
        // هيكل السفينة (14x5)
        for (int z = 0; z < 14; z++) {
            int w = (z < 3 || z > 10) ? 2 : 3;
            for (int x = -w; x <= w; x++) {
                level.setBlock(p.offset(x, 0, z), hull, 3);
                level.setBlock(p.offset(x, -1, z), hull, 3);
            }
        }
        // ظهر السفينة
        for (int z = 2; z < 12; z++) for (int x = -2; x <= 2; x++) level.setBlock(p.offset(x, 1, z), deck, 3);
        // صاري
        for (int y = 2; y < 10; y++) level.setBlock(p.offset(0, y, 5), mast, 3);
        // ذراع الشراع (horizontal)
        for (int x = -3; x <= 3; x++) level.setBlock(p.offset(x, 8, 5), mast, 3);
        for (int x = -2; x <= 2; x++) level.setBlock(p.offset(x, 6, 5), hull, 3);
        // مقدمة مدببة
        level.setBlock(p.offset(0, 1, 0), hull, 3);
        level.setBlock(p.offset(0, 1, 13), hull, 3);
    }

    /** كشك سوق */
    private static void buildMarketStall(ServerLevel level, BlockPos p, BlockState base, BlockState top) {
        if (Math.abs(p.getX() - p.getX()) % 4 != 0) return; // كشك كل 4 بلوكات
        for (int y = 0; y < 3; y++) level.setBlock(p.above(y), base, 3);
        level.setBlock(p.above(3), top, 3);
        level.setBlock(p.above(3).east(), base, 3);
        level.setBlock(p.above(3).west(), base, 3);
        level.setBlock(p.above(4).east(), top, 3);
        level.setBlock(p.above(4).west(), top, 3);
        level.setBlock(p.above(4), top, 3);
    }


    private static void transformTerrainForWaterCastle(ServerLevel level, BlockPos c) {
        for (int x = -100; x <= 100; x += 2) {
            for (int z = -100; z <= 100; z += 2) {
                BlockPos target = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, c.offset(x, 0, z)).below();
                if (level.getBlockState(target).is(Blocks.WATER)) {
                    level.setBlock(target, Blocks.ICE.defaultBlockState(), 3);
                } else {
                    level.setBlock(target.above(), Blocks.SNOW.defaultBlockState(), 3);
                    if (Math.random() > 0.8) level.setBlock(target, Blocks.PACKED_ICE.defaultBlockState(), 3);
                }
            }
        }
    }

    // =====================================================================
    //  قلعة أمة النار (Fire Nation Capital) - تصميم شامل
    // =====================================================================
    private static void buildFireNationCastle(ServerLevel level, BlockPos c) {
        BlockState DARK  = Blocks.BLACKSTONE.defaultBlockState();
        BlockState BRICK = Blocks.NETHER_BRICKS.defaultBlockState();
        BlockState POLB  = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState MAGMA = Blocks.MAGMA_BLOCK.defaultBlockState();
        BlockState LAVA  = Blocks.LAVA.defaultBlockState();
        BlockState CHIS  = Blocks.CHISELED_NETHER_BRICKS.defaultBlockState();
        BlockState AIR   = Blocks.AIR.defaultBlockState();

        // === 1. البركان خلف القصر (R_base=28, ارتفاع 50) ===
        BlockPos vc = c.offset(0, 0, 55);
        for (int y = 0; y <= 50; y++) {
            int r = 28 - (y * 28 / 50);
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x*x + z*z);
                    if (d <= r) {
                        BlockState b = (y < 5) ? MAGMA : (y < 30) ? DARK : POLB;
                        level.setBlock(vc.offset(x, y, z), b, 3);
                        // بحيرة الحمم في القمة
                        if (d <= 5 && y >= 44) level.setBlock(vc.offset(x, y, z), LAVA, 3);
                        // سيالات جانبية
                        if (d > r - 3 && d <= r && (int)(Math.atan2(z,x) * 6 / Math.PI) % 2 == 0)
                            level.setBlock(vc.offset(x, y, z), MAGMA, 3);
                    }
                }
            }
        }
        // سيل حمم ينزل إلى القلعة (7 قنوات)
        for (int z = 10; z <= 50; z++) {
            level.setBlock(vc.offset(0, Math.max(0, (int)(50 - z * 0.4f)), z - 55), LAVA, 3);
            level.setBlock(c.offset(3, 0, z - 5), MAGMA, 3);
            level.setBlock(c.offset(-3, 0, z - 5), MAGMA, 3);
        }

        // === 2. الأرض الداكنة (قاعدة ضخمة) ===
        for (int x = -45; x <= 45; x++)
            for (int z2 = -45; z2 <= 45; z2++) {
                double d = Math.sqrt(x*x + z2*z2);
                if (d <= 45) {
                    level.setBlock(c.offset(x, -2, z2), DARK, 3);
                    level.setBlock(c.offset(x, -1, z2), d > 42 ? MAGMA : POLB, 3);
                }
            }

        // === 3. خندق الحمم (حلقة دائرية) ===
        for (int x = -40; x <= 40; x++)
            for (int z2 = -40; z2 <= 40; z2++) {
                double d = Math.sqrt(x*x + z2*z2);
                if (d > 36 && d < 40)
                    level.setBlock(c.offset(x, -1, z2), LAVA, 3);
            }

        // === 4. السور الخارجي (R=34) بأبراج سوداء ===
        for (int y = 0; y < 16; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(34 * Math.cos(rad));
                int z2 = (int)(34 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z2), (y % 4 == 0) ? CHIS : BRICK, 3);
                int xi = (int)(32 * Math.cos(rad));
                int zi = (int)(32 * Math.sin(rad));
                level.setBlock(c.offset(xi, y, zi), DARK, 3);
                // شرفات
                if (y == 15 && i % 6 == 0) level.setBlock(c.offset(x, 16, z2), POLB, 3);
            }
        }
        // 8 أبراج ضخمة على السور
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45);
            int bx = (int)(34 * Math.cos(rad));
            int bz = (int)(34 * Math.sin(rad));
            buildFireTower(level, c.offset(bx, 0, bz), BRICK, CHIS, MAGMA, 24);
        }

        // === 5. بوابة مهيبة (جنوب) ===
        for (int y = 0; y < 14; y++) {
            level.setBlock(c.offset(-5, y, -34), BRICK, 3);
            level.setBlock(c.offset(5, y, -34), BRICK, 3);
        }
        for (int x = -5; x <= 5; x++) {
            for (int y = 14; y < 20; y++) {
                level.setBlock(c.offset(x, y, -34), DARK, 3);
            }
        }
        // رموز النار على البوابة
        level.setBlock(c.offset(-3, 8, -33), MAGMA, 3);
        level.setBlock(c.offset(3, 8, -33), MAGMA, 3);
        level.setBlock(c.offset(0, 16, -34), MAGMA, 3);

        // === 6. السور الداخلي (R=22) ===
        for (int y = 0; y < 12; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(22 * Math.cos(rad));
                int z2 = (int)(22 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z2), (y % 3 == 0) ? CHIS : DARK, 3);
            }
        }
        // براجين على جانبي البوابة الداخلية
        buildFireTower(level, c.offset(-22, 0, 0), DARK, CHIS, MAGMA, 18);
        buildFireTower(level, c.offset(22, 0, 0), DARK, CHIS, MAGMA, 18);

        // === 7. القصر المركزي (مستطيل 20x15) ===
        for (int y = 0; y < 18; y++) {
            for (int x = -10; x <= 10; x++) {
                for (int z2 = -8; z2 <= 8; z2++) {
                    boolean isWall = (x == -10 || x == 10 || z2 == -8 || z2 == 8);
                    if (isWall) {
                        level.setBlock(c.offset(x, y, z2), (y % 6 == 0) ? CHIS : (y < 8 ? BRICK : DARK), 3);
                    } else if (y == 0) {
                        level.setBlock(c.offset(x, y, z2), POLB, 3);
                    } else {
                        level.setBlock(c.offset(x, y, z2), AIR, 3);
                    }
                    // نوافذ
                    if (isWall && (y == 5 || y == 10) && x % 4 == 0)
                        level.setBlock(c.offset(x, y, z2), Blocks.RED_STAINED_GLASS.defaultBlockState(), 3);
                }
            }
        }
        // مدخل القصر مع بوابات حقيقية ثقيلة
        placeDetailedDoor(level, c.offset(0, 0, -8), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(1, 0, -8), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(-1, 0, -8), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        level.setBlock(c.offset(0, 2, -8), Blocks.RED_STAINED_GLASS.defaultBlockState(), 3);
        level.setBlock(c.offset(1, 2, -8), Blocks.RED_STAINED_GLASS.defaultBlockState(), 3);
        level.setBlock(c.offset(-1, 2, -8), Blocks.RED_STAINED_GLASS.defaultBlockState(), 3);

        // === 8. غرفة العرش (throne room) ===
        for (int x = -5; x <= 5; x++)
            for (int z2 = 0; z2 <= 6; z2++)
                level.setBlock(c.offset(x, 0, z2), (x % 2 == z2 % 2) ? POLB : DARK, 3);
        // منصة العرش
        for (int x = -2; x <= 2; x++)
            for (int z2 = 3; z2 <= 6; z2++)
                level.setBlock(c.offset(x, 1, z2), POLB, 3);
        // خلفية مضيئة
        for (int x = -2; x <= 2; x++)
            level.setBlock(c.offset(x, 2, 8), MAGMA, 3);

        // === 9. أبراج زوايا القصر ===
        buildFireTower(level, c.offset(-10, 0, -8), BRICK, CHIS, MAGMA, 26);
        buildFireTower(level, c.offset(10, 0, -8), BRICK, CHIS, MAGMA, 26);
        buildFireTower(level, c.offset(-10, 0, 8), BRICK, CHIS, MAGMA, 26);
        buildFireTower(level, c.offset(10, 0, 8), BRICK, CHIS, MAGMA, 26);

        // === 10. طريق حممي مضيء إلى البوابة ===
        for (int z2 = -32; z2 < -8; z2++) {
            level.setBlock(c.offset(-2, -1, z2), POLB, 3);
            level.setBlock(c.offset(2, -1, z2), POLB, 3);
            level.setBlock(c.offset(-1, -1, z2), MAGMA, 3);
            level.setBlock(c.offset(0, -1, z2), MAGMA, 3);
            level.setBlock(c.offset(1, -1, z2), MAGMA, 3);
            if (z2 % 5 == 0) {
                buildLampPost(level, c.offset(-3, 0, z2), DARK, MAGMA);
                buildLampPost(level, c.offset(3, 0, z2), DARK, MAGMA);
            }
        }

        // === 11. المستوطنة العسكرية (ثكنة داخل السور الخارجي) ===
        for (int i = 0; i < 10; i++) {
            double rad = Math.toRadians(i * 36 + 18);
            int bx = (int)(28 * Math.cos(rad));
            int bz = (int)(28 * Math.sin(rad));
            buildFireBarracks(level, c.offset(bx, 0, bz), DARK, BRICK, MAGMA);
        }
        // منازل المدنيين
        for (int i = 0; i < 6; i++) {
            double rad = Math.toRadians(i * 60 + 10);
            int bx = (int)(16 * Math.cos(rad));
            int bz = (int)(16 * Math.sin(rad));
            buildFireHouse(level, c.offset(bx, 0, bz), DARK, CHIS, POLB);
        }

        // === 12. ميناء حربي + سفن أمة النار ===
        // سفن حربية ضخمة (Fire Nation Battleships)
        buildFireShip(level, c.offset(-20, -2, -55), DARK, BRICK, MAGMA);
        buildFireShip(level, c.offset(0, -2, -62), DARK, BRICK, MAGMA);
        buildFireShip(level, c.offset(20, -2, -55), DARK, BRICK, MAGMA);
        // سفينة تجسس صغيرة
        buildFireShip(level, c.offset(-35, -2, -48), POLB, CHIS, MAGMA);
        buildFireShip(level, c.offset(35, -2, -48), POLB, CHIS, MAGMA);

        // === 13. رايات أمة النار على البراج ===
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45);
            int bx = (int)(34 * Math.cos(rad));
            int bz = (int)(34 * Math.sin(rad));
            level.setBlock(c.offset(bx, 25, bz), MAGMA, 3);
            level.setBlock(c.offset(bx, 26, bz), Blocks.FIRE.defaultBlockState(), 3);
        }

        // === 14. أبراج بركانية حادة (Jagged Spires) حول القصر للواقعية ===
        buildJaggedSpire(level, c.offset(-15, 0, 15), 35, 5, DARK, MAGMA);
        buildJaggedSpire(level, c.offset(15, 0, 15), 35, 5, DARK, MAGMA);
        buildJaggedSpire(level, c.offset(0, 0, 18), 45, 6, DARK, MAGMA);
    }

    /** يبني برجاً لأمة النار */
    private static void buildFireTower(ServerLevel level, BlockPos base, BlockState wall, BlockState accent, BlockState top, int height) {
        for (int y = 0; y < height; y++) {
            int r = (y < height - 5) ? 3 : Math.max(1, 3 - (y - (height - 5)));
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) == r || Math.abs(z) == r)
                        level.setBlock(base.offset(x, y, z), (y % 5 == 0) ? accent : wall, 3);
                }
            }
        }
        level.setBlock(base.above(height), top, 3);
        level.setBlock(base.above(height + 1), Blocks.FIRE.defaultBlockState(), 3);
    }

    /** سفينة حربية لأمة النار */
    private static void buildFireShip(ServerLevel level, BlockPos p, BlockState hull, BlockState deck, BlockState cannon) {
        // هيكل السفينة الحربية (20x7)
        for (int z = 0; z < 20; z++) {
            int w = (z < 4 || z > 15) ? 3 : 5;
            for (int x = -w; x <= w; x++) {
                level.setBlock(p.offset(x, 0, z), hull, 3);
                level.setBlock(p.offset(x, 1, z), hull, 3);
                if (z > 3 && z < 16) level.setBlock(p.offset(x, 2, z), deck, 3);
            }
        }
        // مدخنة البخار
        for (int y = 3; y < 9; y++) level.setBlock(p.offset(-2, y, 10), hull, 3);
        for (int y = 3; y < 9; y++) level.setBlock(p.offset(2, y, 10), hull, 3);
        level.setBlock(p.offset(-2, 9, 10), Blocks.FIRE.defaultBlockState(), 3);
        level.setBlock(p.offset(2, 9, 10), Blocks.FIRE.defaultBlockState(), 3);
        // مدافع
        level.setBlock(p.offset(-4, 2, 5), cannon, 3);
        level.setBlock(p.offset(4, 2, 5), cannon, 3);
        level.setBlock(p.offset(-4, 2, 14), cannon, 3);
        level.setBlock(p.offset(4, 2, 14), cannon, 3);
        // جسر مكشوف
        level.setBlock(p.offset(0, 2, 5), hull, 3);
        level.setBlock(p.offset(0, 3, 5), hull, 3);
        level.setBlock(p.offset(0, 4, 5), hull, 3);
    }

    /** ثكنة عسكرية لأمة النار */
    private static void buildFireBarracks(ServerLevel level, BlockPos p, BlockState wall, BlockState roof, BlockState light) {
        for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, -1, z), wall, 3);
        for (int y = 0; y < 4; y++)
            for (int x = -3; x <= 3; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x)==3 || Math.abs(z)==2) level.setBlock(p.offset(x, y, z), wall, 3);
        for (int x = -4; x <= 4; x++) for (int z = -3; z <= 3; z++) level.setBlock(p.offset(x, 4, z), roof, 3);
        for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, 5, z), roof, 3);
        level.setBlock(p.offset(0, 4, -3), light, 3);
        level.setBlock(p.offset(0, 1, 2), Blocks.RED_STAINED_GLASS_PANE.defaultBlockState(), 3);
        placeDetailedDoor(level, p.offset(0, 0, -2), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    /** بيت لأمة النار */
    private static void buildFireHouse(ServerLevel level, BlockPos p, BlockState wall, BlockState roof, BlockState floor) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, -1, z), floor, 3);
        for (int y = 0; y < 4; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x)==2 || Math.abs(z)==2) level.setBlock(p.offset(x, y, z), wall, 3);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setBlock(p.offset(x, 4, z), roof, 3);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, 5, z), roof, 3);
        level.setBlock(p.offset(0, 1, 2), Blocks.RED_STAINED_GLASS_PANE.defaultBlockState(), 3);
        placeDetailedDoor(level, p.offset(0, 0, -2), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    private static void transformTerrainForFireCastle(ServerLevel level, BlockPos c) {
        for (int x = -100; x <= 100; x += 3) {
            for (int z = -100; z <= 100; z += 3) {
                BlockPos target = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, c.offset(x, 0, z)).below();
                level.setBlock(target, Blocks.BASALT.defaultBlockState(), 3);
                if (Math.random() > 0.9) level.setBlock(target.above(), Blocks.FIRE.defaultBlockState(), 3);
            }
        }
    }

    // =====================================================================
    //  المدينة السماوية (Celestial Light Castle) - تصميم شامل
    // =====================================================================
    private static void buildCelestialLightCastle(ServerLevel level, BlockPos c) {
        BlockState Q    = Blocks.QUARTZ_BLOCK.defaultBlockState();
        BlockState QP   = Blocks.QUARTZ_PILLAR.defaultBlockState();
        BlockState QB   = Blocks.QUARTZ_BRICKS.defaultBlockState();
        BlockState G    = Blocks.GOLD_BLOCK.defaultBlockState();
        BlockState GLOW = Blocks.GLOWSTONE.defaultBlockState();
        BlockState SEA  = Blocks.SEA_LANTERN.defaultBlockState();
        BlockState WGLASS = Blocks.WHITE_STAINED_GLASS.defaultBlockState();
        BlockState AIR  = Blocks.AIR.defaultBlockState();

        // === 1. منصة عائمة عضوية (دائرة R=40 مع جذور حجرية من الأسفل) ===
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                double d = Math.sqrt(x*x + z*z);
                if (d <= 40) {
                    level.setBlock(c.offset(x, -1, z), d > 38 ? QB : (d > 32 ? Q : (d > 26 ? QB : Q)), 3);
                    level.setBlock(c.offset(x, -2, z), G, 3);
                    if (d <= 40 && d > 38) level.setBlock(c.offset(x, 0, z), Blocks.WHITE_CONCRETE.defaultBlockState(), 3);
                    // نقش ذهبي دائري
                    if (Math.abs(x) % 8 == 0 && Math.abs(z) % 8 == 0 && d < 38) level.setBlock(c.offset(x, -1, z), G, 3);
                    
                    // جذور متدلية (Stalactites) للجزر الطافية لتبدو طبيعية
                    if (d < 38 && Math.random() > 0.8) {
                        int depth = (int)((40 - d) / 3 * Math.random());
                        for (int y = 0; y < depth; y++) {
                            level.setBlock(c.offset(x, -3 - y, z), Q, 3);
                        }
                    }
                }
            }

        // === 2. أعمدة الضوء (24 عمود) ===
        for (int i = 0; i < 24; i++) {
            double rad = Math.toRadians(i * 15);
            int cx = (int)(30 * Math.cos(rad));
            int cz = (int)(30 * Math.sin(rad));
            for (int y = 0; y < 16; y++) level.setBlock(c.offset(cx, y, cz), QP, 3);
            level.setBlock(c.offset(cx, 16, cz), GLOW, 3);
        }

        // === 3. السور الخارجي (R=35) ===
        for (int y = 0; y < 14; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(35 * Math.cos(rad));
                int z = (int)(35 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 5 == 0) ? G : Q, 3);
                if (y == 13 && i % 5 == 0) level.setBlock(c.offset(x, 14, z), GLOW, 3);
            }
        }
        // 8 أبراج سماوية
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45);
            buildSkyTower(level, c.offset((int)(35*Math.cos(rad)), 0, (int)(35*Math.sin(rad))), Q, G, GLOW, 24);
        }

        // === 4. السور الداخلي (R=20) ===
        for (int y = 0; y < 10; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(20 * Math.cos(rad));
                int z = (int)(20 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 4 == 0) ? G : QB, 3);
            }
        }
        buildSkyTower(level, c.offset(-20, 0, 0), Q, G, GLOW, 20);
        buildSkyTower(level, c.offset(20, 0, 0), Q, G, GLOW, 20);
        buildSkyTower(level, c.offset(0, 0, -20), Q, G, GLOW, 20);
        buildSkyTower(level, c.offset(0, 0, 20), Q, G, GLOW, 20);

        // === 5. قصر الشمس المركزي ===
        for (int y = 0; y < 16; y++) {
            for (int x = -9; x <= 9; x++) {
                for (int z = -9; z <= 9; z++) {
                    boolean wall = (x == -9 || x == 9 || z == -9 || z == 9);
                    if (wall) level.setBlock(c.offset(x, y, z), (y % 8 == 0) ? G : Q, 3);
                    else if (y == 0) level.setBlock(c.offset(x, y, z), QB, 3);
                    else level.setBlock(c.offset(x, y, z), AIR, 3);
                    if (wall && y == 7 && x % 4 == 0) level.setBlock(c.offset(x, y, z), WGLASS, 3);
                    if (wall && y == 7 && z % 4 == 0) level.setBlock(c.offset(x, y, z), WGLASS, 3);
                }
            }
        }
        // مدخل القصر بأبواب خشبية بيضاء (Birch)
        placeDetailedDoor(level, c.offset(0, 0, -9), Blocks.BIRCH_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(1, 0, -9), Blocks.BIRCH_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(-1, 0, -9), Blocks.BIRCH_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        level.setBlock(c.offset(0, 2, -9), WGLASS, 3);
        level.setBlock(c.offset(1, 2, -9), WGLASS, 3);
        level.setBlock(c.offset(-1, 2, -9), WGLASS, 3);

        // === 6. القبة الذهبية العملاقة باستخدام الخوارزمية المتقدمة ===
        buildSmoothDome(level, c.above(16), 11, G, Q);
        level.setBlock(c.offset(0, 16 + 11 + 1, 0), GLOW, 3);
        level.setBlock(c.offset(0, 16 + 11 + 2, 0), G, 3);

        // === 7. أبراج زوايا القصر ===
        buildSkyTower(level, c.offset(-9, 0, -9), Q, G, GLOW, 28);
        buildSkyTower(level, c.offset(9, 0, -9), Q, G, GLOW, 28);
        buildSkyTower(level, c.offset(-9, 0, 9), Q, G, GLOW, 28);
        buildSkyTower(level, c.offset(9, 0, 9), Q, G, GLOW, 28);

        // === 8. طريق مضيء إلى البوابة ===
        for (int z = -34; z < -9; z++) {
            level.setBlock(c.offset(-2, -1, z), QB, 3);
            level.setBlock(c.offset(2, -1, z), QB, 3);
            level.setBlock(c.offset(-1, -1, z), Q, 3);
            level.setBlock(c.offset(0, -1, z), G, 3);
            level.setBlock(c.offset(1, -1, z), Q, 3);
            if (z % 5 == 0) {
                buildLampPost(level, c.offset(-3, 0, z), Q, GLOW);
                buildLampPost(level, c.offset(3, 0, z), Q, GLOW);
            }
        }

        // === 9. إضاءة داخلية ===
        for (int x = -7; x <= 7; x += 3)
            for (int z = -7; z <= 7; z += 3)
                level.setBlock(c.offset(x, 0, z), SEA, 3);

        // === 10. المستوطنة السماوية ===
        // معابد صغيرة محيطة بالسور الخارجي
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45 + 22);
            int bx = (int)(27 * Math.cos(rad));
            int bz = (int)(27 * Math.sin(rad));
            buildLightShrine(level, c.offset(bx, 0, bz), Q, G, GLOW);
        }
        // بيوت سكان سماوية
        for (int i = 0; i < 6; i++) {
            double rad = Math.toRadians(i * 60);
            int bx = (int)(14 * Math.cos(rad));
            int bz = (int)(14 * Math.sin(rad));
            buildLightHouse(level, c.offset(bx, 0, bz), Q, G, GLOW);
        }

        // === 11. حدائق التأمل (حول القصر) ===
        for (int x = -18; x <= 18; x += 6)
            for (int z = -18; z <= 18; z += 6)
                if (Math.sqrt(x*x+z*z) < 19 && Math.sqrt(x*x+z*z) > 10)
                    level.setBlock(c.offset(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 3);

        // === 12. جسر مضيء بين السور والقصر (4 جسور) ===
        buildBridge(level, c.offset(0, 0, -9), c.offset(0, 0, -20), Q, 3);
        buildBridge(level, c.offset(0, 0, 9), c.offset(0, 0, 20), Q, 3);
        buildBridge(level, c.offset(-9, 0, 0), c.offset(-20, 0, 0), Q, 3);
        buildBridge(level, c.offset(9, 0, 0), c.offset(20, 0, 0), Q, 3);
    }

    /** ضريح صغير في المدينة السماوية */
    private static void buildLightShrine(ServerLevel level, BlockPos p, BlockState wall, BlockState accent, BlockState light) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, -1, z), wall, 3);
        for (int y = 0; y < 5; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x)==2 || Math.abs(z)==2) level.setBlock(p.offset(x, y, z), (y % 3==0) ? accent : wall, 3);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setBlock(p.offset(x, 5, z), accent, 3);
        level.setBlock(p.above(6), light, 3);
        placeDetailedDoor(level, p.offset(0, 0, -2), Blocks.BIRCH_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    /** منزل مواطن في المدينة السماوية */
    private static void buildLightHouse(ServerLevel level, BlockPos p, BlockState wall, BlockState accent, BlockState light) {
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setBlock(p.offset(x, -1, z), wall, 3);
        for (int y = 0; y < 3; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++)
                    if (Math.abs(x)==1 || Math.abs(z)==1) level.setBlock(p.offset(x, y, z), wall, 3);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, 3, z), accent, 3);
        level.setBlock(p.offset(0, 4, 0), light, 3);
        placeDetailedDoor(level, p.offset(0, 0, -1), Blocks.BIRCH_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    private static void buildSkyTower(ServerLevel level, BlockPos base, BlockState wall, BlockState accent, BlockState light, int height) {
        for (int y = 0; y < height; y++) {
            int r = (y < height - 6) ? 3 : Math.max(1, 3 - (y - (height - 6)));
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) == r || Math.abs(z) == r)
                        level.setBlock(base.offset(x, y, z), (y % 7 == 0) ? accent : wall, 3);
                }
            }
        }
        level.setBlock(base.above(height), light, 3);
        level.setBlock(base.above(height + 1), accent, 3);
    }

    private static void transformTerrainForLightCastle(ServerLevel level, BlockPos c) {
        // جسيمات ضوء مكثفة
        level.playSound(null, c, SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 2.0f, 1.0f);
    }

    // =====================================================================
    //  معابد الهواء الجبلية (Air Temple Castle) - تصميم شامل
    // =====================================================================
    private static void buildAirTempleCastle(ServerLevel level, BlockPos c) {
        BlockState Q    = Blocks.QUARTZ_BLOCK.defaultBlockState();
        BlockState BC   = Blocks.BLUE_CONCRETE.defaultBlockState();
        BlockState STONE= Blocks.STONE_BRICKS.defaultBlockState();
        BlockState MSTONE = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState AIR  = Blocks.AIR.defaultBlockState();
        BlockState SEA  = Blocks.SEA_LANTERN.defaultBlockState();

        // === 1. الجبل المركزي الضخم ===
        for (int y = -15; y < 30; y++) {
            int r = 22 - (y + 15) / 3;
            if (r < 3) r = 3;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double d = Math.sqrt(x*x + z*z);
                    if (d <= r) level.setBlock(c.offset(x, y, z), (y > 22) ? STONE : Blocks.STONE.defaultBlockState(), 3);
                }
            }
        }

        // === 2. المعبد الرئيسي فوق الجبل ===
        // قاعدة مستطيلة
        for (int x = -10; x <= 10; x++)
            for (int z2 = -8; z2 <= 8; z2++)
                level.setBlock(c.offset(x, 30, z2), Q, 3);
        // جدران
        for (int y = 31; y < 46; y++) {
            for (int x = -10; x <= 10; x++) {
                for (int z2 = -8; z2 <= 8; z2++) {
                    boolean wall = (x == -10 || x == 10 || z2 == -8 || z2 == 8);
                    if (wall) level.setBlock(c.offset(x, y, z2), (y % 7 == 0) ? BC : Q, 3);
                    else level.setBlock(c.offset(x, y, z2), AIR, 3);
                    if (wall && y == 36 && x % 3 == 0) level.setBlock(c.offset(x, y, z2), BC, 3);
                    if (wall && y == 36 && z2 % 3 == 0) level.setBlock(c.offset(x, y, z2), BC, 3);
                }
            }
        }
        // سقف باغودا (طبقة مائلة) باستخدام الخوارزمية المتقدمة
        buildPagodaRoof(level, c.above(46), 13, 11, BC);
        // طابق ثاني
        for (int x = -7; x <= 7; x++) {
            for (int z2 = -5; z2 <= 5; z2++) {
                boolean wall = (x == -7 || x == 7 || z2 == -5 || z2 == 5);
                if (wall) level.setBlock(c.offset(x, 48 + (y_iter(x, z2, 7, 5)), z2), Q, 3);
            }
        }
        // سقف باغودا للطابق الثاني
        buildPagodaRoof(level, c.above(55), 9, 7, BC);

        // === 3. مدخل المعبد مع تمثالين وبوابات حقيقية ===
        placeDetailedDoor(level, c.offset(0, 31, -8), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(1, 31, -8), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(-1, 31, -8), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        level.setBlock(c.offset(0, 33, -8), Blocks.WHITE_STAINED_GLASS.defaultBlockState(), 3);
        level.setBlock(c.offset(1, 33, -8), Blocks.WHITE_STAINED_GLASS.defaultBlockState(), 3);
        level.setBlock(c.offset(-1, 33, -8), Blocks.WHITE_STAINED_GLASS.defaultBlockState(), 3);
        // تماثيل الأبا (تماثيل ضخمة عند المدخل)
        buildAppaStatue(level, c.offset(-14, 29, -6));
        buildAppaStatue(level, c.offset(14, 29, -6));

        // === 4. القمم الجبلية المحيطة (4 قمم جانبية) ===
        int[][] peaks = {{-50, 0}, {50, 0}, {0, -50}, {0, 50}};
        for (int[] pk : peaks) {
            BlockPos pm = c.offset(pk[0], -5, pk[1]);
            // شكل الجبل
            for (int y = 0; y < 35; y++) {
                int r = 20 - y / 2;
                if (r < 2) r = 2;
                for (int x = -r; x <= r; x++) {
                    for (int z2 = -r; z2 <= r; z2++) {
                        if (Math.sqrt(x*x + z2*z2) <= r)
                            level.setBlock(pm.offset(x, y, z2), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
            // معبد صغير فوق كل قمة
            for (int y = 35; y < 44; y++) {
                for (int x = -4; x <= 4; x++) {
                    for (int z2 = -4; z2 <= 4; z2++) {
                        boolean wall = (Math.abs(x) == 4 || Math.abs(z2) == 4);
                        if (wall) level.setBlock(pm.offset(x, y, z2), (y % 5 == 0) ? BC : Q, 3);
                    }
                }
            }
            level.setBlock(pm.above(44), SEA, 3);
        }

        // === 5. جسور تربط القمم بالمعبد الرئيسي ===
        buildBridge(level, c.offset(0, 30, 0), c.offset(-50, 28, 0), Q, 4);
        buildBridge(level, c.offset(0, 30, 0), c.offset(50, 28, 0), Q, 4);
        buildBridge(level, c.offset(0, 30, 0), c.offset(0, 28, -50), Q, 4);
        buildBridge(level, c.offset(0, 30, 0), c.offset(0, 28, 50), Q, 4);

        // === 6. قاعة التأمل (قبة دائرية) ===
        for (int x = -6; x <= 6; x++)
            for (int z2 = -6; z2 <= 6; z2++) {
                level.setBlock(c.offset(x, 31, z2), MSTONE, 3);
                // رسمة شعار الهواء
                if (x == 0 && z2 == 0) level.setBlock(c.offset(x, 31, z2), BC, 3);
            }

        // === 7. إضاءة ===
        for (int x = -8; x <= 8; x += 4)
            for (int z2 = -6; z2 <= 6; z2 += 4)
                level.setBlock(c.offset(x, 31, z2), SEA, 3);

        // === 8. حجرات الرهبان (حول المعبد الرئيسي) ===
        int[][] monkCells = {{-13, 30, -5},{-13, 30, 5},{13, 30, -5},{13, 30, 5},
                              {-5, 30, -11},{5, 30, -11},{-5, 30, 11},{5, 30, 11}};
        for (int[] mc : monkCells) {
            BlockPos cp = c.offset(mc[0], mc[1], mc[2]);
            // جدران الحجرة
            for (int y = 0; y < 3; y++)
                for (int x = -1; x <= 1; x++)
                    for (int z2 = -1; z2 <= 1; z2++)
                        if (Math.abs(x)==1 || Math.abs(z2)==1) level.setBlock(cp.offset(x, y, z2), Q, 3);
            // سقف
            buildPagodaRoof(level, cp.above(3), 2, 2, BC);
            level.setBlock(cp.offset(0, 4, 0), SEA, 3);
            level.setBlock(cp.offset(0, 1, 1), Blocks.WHITE_STAINED_GLASS_PANE.defaultBlockState(), 3);
            placeDetailedDoor(level, cp.offset(0, 0, -1), Blocks.SPRUCE_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        }

        // === 9. ساحة التدريب (حلبة دائرية) ===
        for (int i = 0; i < 360; i++) {
            double rad = Math.toRadians(i);
            int rx = (int)(7 * Math.cos(rad));
            int rz = (int)(7 * Math.sin(rad));
            level.setBlock(c.offset(rx, 30, rz), Q, 3);
        }

        // === 10. منصات إقلاع الطائر ===
        for (int[] dir : new int[][]{{0,30,-15},{0,30,15},{-15,30,0},{15,30,0}}) {
            BlockPos lp = c.offset(dir[0], dir[1], dir[2]);
            for (int x = -2; x <= 2; x++) for (int z2 = -2; z2 <= 2; z2++) level.setBlock(lp.offset(x, 0, z2), Q, 3);
            level.setBlock(lp.above(1), SEA, 3);
        }
    }

    /** y لحساب ارتفاع طبقة السقف */
    private static int y_iter(int x, int z, int rx, int rz) { return (Math.abs(x) + Math.abs(z)) / 3; }

    /** يبني جسراً بين نقطتين */
    private static void buildBridge(ServerLevel level, BlockPos from, BlockPos to, BlockState mat, int width) {
        int dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return;
        for (int s = 0; s <= steps; s++) {
            int x = from.getX() + dx * s / steps;
            int y = from.getY() + dy * s / steps;
            int z = from.getZ() + dz * s / steps;
            for (int w = -width/2; w <= width/2; w++) {
                boolean zAx = Math.abs(dx) > Math.abs(dz);
                level.setBlock(new BlockPos(x, y, zAx ? z + w : z), mat, 3);
                level.setBlock(new BlockPos(zAx ? x : x + w, y, z), mat, 3);
                // حواف الجسر
                level.setBlock(new BlockPos(zAx ? x : x + width/2 + 1, y + 1, z), mat, 3);
                level.setBlock(new BlockPos(zAx ? x : x - width/2 - 1, y + 1, z), mat, 3);
            }
        }
    }

    /** تمثال الأبا (تمثال ضخم بشكل دب جبلي) */
    private static void buildAppaStatue(ServerLevel level, BlockPos p) {
        BlockState W = Blocks.WHITE_CONCRETE.defaultBlockState();
        // جسم
        for (int y = 0; y < 5; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -3; z <= 3; z++)
                    level.setBlock(p.offset(x, y, z), W, 3);
        // رأس
        for (int y = 3; y < 7; y++)
            for (int x = -1; x <= 1; x++)
                for (int z = -5; z <= -3; z++)
                    level.setBlock(p.offset(x, y, z), W, 3);
        level.setBlock(p.offset(0, 6, -5), Blocks.BLACK_CONCRETE.defaultBlockState(), 3);
        level.setBlock(p.offset(0, 6, -4), Blocks.BLACK_CONCRETE.defaultBlockState(), 3);
    }

    private static void transformTerrainForAirCastle(ServerLevel level, BlockPos c) {
        // سحب كثيفة تحت القلعة
        for (int x = -50; x <= 50; x += 5) {
            for (int z = -50; z <= 50; z += 5) {
                level.sendParticles(ParticleTypes.CLOUD, c.getX() + x, c.getY() - 5, c.getZ() + z, 20, 2, 1, 2, 0.02);
            }
        }
    }

    // =====================================================================
    //  مملكة الأرض (Earth Kingdom Castle) - تصميم شامل
    // =====================================================================
    private static void buildEarthKingdomCastle(ServerLevel level, BlockPos c) {
        BlockState S  = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState MS = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
        BlockState CS = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
        BlockState G  = Blocks.GREEN_CONCRETE.defaultBlockState();
        BlockState GT = Blocks.GREEN_TERRACOTTA.defaultBlockState();
        BlockState EM = Blocks.EMERALD_BLOCK.defaultBlockState();
        BlockState AIR= Blocks.AIR.defaultBlockState();
        BlockState SEA= Blocks.SEA_LANTERN.defaultBlockState();

        // === 1. الأرض الصخرية القاعدية ===
        for (int x = -45; x <= 45; x++)
            for (int z = -45; z <= 45; z++) {
                double d = Math.sqrt(x*x + z*z);
                if (d <= 45) {
                    level.setBlock(c.offset(x, -2, z), Blocks.STONE.defaultBlockState(), 3);
                    level.setBlock(c.offset(x, -1, z), d > 42 ? MS : (d > 36 ? S : MS), 3);
                }
            }

        // === 2. الجبال المحيطة (تضاريس) ===
        int[][] mts = {{-50, -50},{50,-50},{-50,50},{50,50},{0,-55},{0,55}};
        for (int[] m : mts) {
            BlockPos mp = c.offset(m[0], -5, m[1]);
            for (int y = 0; y < 30; y++) {
                int r = 18 - y / 2;
                if (r < 2) r = 2;
                for (int x = -r; x <= r; x++)
                    for (int z = -r; z <= r; z++)
                        if (Math.sqrt(x*x + z*z) <= r)
                            level.setBlock(mp.offset(x, y, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }

        // === 3. خندق (حلقة R=38) ===
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                double d = Math.sqrt(x*x + z*z);
                if (d > 36 && d < 40)
                    level.setBlock(c.offset(x, -2, z), Blocks.WATER.defaultBlockState(), 3);
            }

        // === 4. سور خارجي (R=34) بأبراج ===
        for (int y = 0; y < 18; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(34 * Math.cos(rad));
                int z = (int)(34 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 5 == 0) ? CS : S, 3);
                if (y == 17 && i % 5 == 0) level.setBlock(c.offset(x, 18, z), GT, 3);
            }
        }
        // 8 أبراج
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45);
            buildEarthTower(level, c.offset((int)(34*Math.cos(rad)), 0, (int)(34*Math.sin(rad))), S, G, SEA, 26);
        }

        // === 5. بوابة ضخمة مع تماثيل الدببة ===
        for (int y = 0; y < 16; y++) {
            level.setBlock(c.offset(-6, y, -34), S, 3);
            level.setBlock(c.offset(6, y, -34), S, 3);
        }
        for (int x = -6; x <= 6; x++)
            for (int y = 16; y < 22; y++)
                level.setBlock(c.offset(x, y, -34), G, 3);
        // تماثيل الدببة (المصنوع من الحجارة)
        buildBearStatue(level, c.offset(-9, 0, -32));
        buildBearStatue(level, c.offset(9, 0, -32));

        // === 6. سور داخلي (R=22) ===
        for (int y = 0; y < 12; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(22 * Math.cos(rad));
                int z = (int)(22 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 4 == 0) ? CS : MS, 3);
            }
        }
        buildEarthTower(level, c.offset(-22, 0, 0), S, G, SEA, 18);
        buildEarthTower(level, c.offset(22, 0, 0), S, G, SEA, 18);

        // === 7. القصر المركزي (20x16) ===
        for (int y = 0; y < 20; y++) {
            for (int x = -10; x <= 10; x++) {
                for (int z = -8; z <= 8; z++) {
                    boolean wall = (x == -10 || x == 10 || z == -8 || z == 8);
                    if (wall) level.setBlock(c.offset(x, y, z), (y % 6 == 0) ? CS : (y < 10 ? S : MS), 3);
                    else if (y == 0) level.setBlock(c.offset(x, y, z), MS, 3);
                    else level.setBlock(c.offset(x, y, z), AIR, 3);
                    if (wall && y == 8 && x % 4 == 0)
                        level.setBlock(c.offset(x, y, z), Blocks.GREEN_STAINED_GLASS.defaultBlockState(), 3);
                    if (wall && y == 8 && z % 3 == 0)
                        level.setBlock(c.offset(x, y, z), Blocks.GREEN_STAINED_GLASS.defaultBlockState(), 3);
                }
            }
        }
        // سقف القصر
        buildPagodaRoof(level, c.above(20), 11, 9, G);

        // مدخل القصر ببوابات خشبية ثقيلة
        placeDetailedDoor(level, c.offset(0, 0, -8), Blocks.DARK_OAK_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(1, 0, -8), Blocks.DARK_OAK_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(-1, 0, -8), Blocks.DARK_OAK_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        level.setBlock(c.offset(0, 2, -8), Blocks.GREEN_STAINED_GLASS.defaultBlockState(), 3);
        level.setBlock(c.offset(1, 2, -8), Blocks.GREEN_STAINED_GLASS.defaultBlockState(), 3);
        level.setBlock(c.offset(-1, 2, -8), Blocks.GREEN_STAINED_GLASS.defaultBlockState(), 3);

        // === 8. غرفة العرش بالكريستال الأخضر ===
        for (int x = -4; x <= 4; x++)
            for (int z = 0; z <= 5; z++)
                level.setBlock(c.offset(x, 0, z), MS, 3);
        // كريستالات عمودية
        for (int x = -3; x <= 3; x += 3)
            for (int y = 1; y < 5; y++)
                level.setBlock(c.offset(x, y, 5), EM, 3);

        // === 9. أبراج زوايا القصر ===
        buildEarthTower(level, c.offset(-10, 0, -8), S, G, SEA, 28);
        buildEarthTower(level, c.offset(10, 0, -8), S, G, SEA, 28);
        buildEarthTower(level, c.offset(-10, 0, 8), S, G, SEA, 28);
        buildEarthTower(level, c.offset(10, 0, 8), S, G, SEA, 28);

        // === 10. طريق مضيء ===
        for (int z = -32; z < -8; z++) {
            level.setBlock(c.offset(-2, -1, z), CS, 3);
            level.setBlock(c.offset(2, -1, z), CS, 3);
            level.setBlock(c.offset(-1, -1, z), MS, 3);
            level.setBlock(c.offset(0, -1, z), S, 3);
            level.setBlock(c.offset(1, -1, z), MS, 3);
            if (z % 6 == 0) {
                buildLampPost(level, c.offset(-3, 0, z), S, SEA);
                buildLampPost(level, c.offset(3, 0, z), S, SEA);
            }
        }

        // === 11. المستوطنة ===
        for (int i = 0; i < 10; i++) {
            double rad = Math.toRadians(i * 36 + 8);
            buildEarthHouse(level, c.offset((int)(27*Math.cos(rad)), 0, (int)(27*Math.sin(rad))), S, G, MS);
        }
        for (int i = 0; i < 6; i++) {
            double rad = Math.toRadians(i * 60 + 15);
            buildEarthHouse(level, c.offset((int)(15*Math.cos(rad)), 0, (int)(15*Math.sin(rad))), MS, G, S);
        }
        // السوق المركزي
        for (int x = -14; x <= 14; x += 5) buildMarketStall(level, c.offset(x, 0, -18), S, SEA);
        level.setBlock(c.offset(-14, 4, -18), EM, 3);
        level.setBlock(c.offset(14, 4, -18), EM, 3);
    }

    private static void buildEarthTower(ServerLevel level, BlockPos base, BlockState wall, BlockState roof, BlockState light, int height) {
        for (int y = 0; y < height; y++) {
            int r = (y < height - 5) ? 3 : Math.max(1, 3 - (y - (height - 5)));
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) == r || Math.abs(z) == r)
                        level.setBlock(base.offset(x, y, z), (y % 6 == 0) ? roof : wall, 3);
                }
            }
        }
        // سقف أخضر منحني (باغودا)
        buildPagodaRoof(level, base.above(height), 4, 4, roof);
        level.setBlock(base.above(height + 3), light, 3);
    }

    /** بيت لمواطني مملكة الأرض */
    private static void buildEarthHouse(ServerLevel level, BlockPos p, BlockState wall, BlockState roof, BlockState floor) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, -1, z), floor, 3);
        for (int y = 0; y < 4; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x)==2 || Math.abs(z)==2) level.setBlock(p.offset(x, y, z), wall, 3);
        // سقف المنزل
        buildPagodaRoof(level, p.above(4), 3, 3, roof);
        level.setBlock(p.offset(0, 1, 2), Blocks.GREEN_STAINED_GLASS_PANE.defaultBlockState(), 3);
        placeDetailedDoor(level, p.offset(0, 0, -2), Blocks.DARK_OAK_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    private static void buildBearStatue(ServerLevel level, BlockPos p) {
        BlockState ST = Blocks.STONE.defaultBlockState();
        // جسم
        for (int y = 0; y < 5; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    level.setBlock(p.offset(x, y, z), ST, 3);
        // رأس
        for (int y = 4; y < 8; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -4; z <= -2; z++)
                    level.setBlock(p.offset(x, y, z), ST, 3);
        // عينان
        level.setBlock(p.offset(-1, 6, -5), Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        level.setBlock(p.offset(1, 6, -5), Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
    }

    private static void transformTerrainForEarthCastle(ServerLevel level, BlockPos c) {
        for (int x = -80; x <= 80; x += 4) {
            for (int z = -80; z <= 80; z += 4) {
                BlockPos target = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, c.offset(x, 0, z)).below();
                level.setBlock(target, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), 3);
            }
        }
    }

    // =====================================================================
    //  إمبراطورية الظلال (Shadow Kingdom Castle) - تصميم شامل
    // =====================================================================
    private static void buildShadowKingdomCastle(ServerLevel level, BlockPos c) {
        BlockState O  = Blocks.OBSIDIAN.defaultBlockState();
        BlockState P  = Blocks.CRYING_OBSIDIAN.defaultBlockState();
        BlockState PB = Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
        BlockState BK = Blocks.BLACKSTONE.defaultBlockState();
        BlockState PG = Blocks.PURPLE_STAINED_GLASS.defaultBlockState();
        BlockState AM = Blocks.AMETHYST_BLOCK.defaultBlockState();
        BlockState AIR= Blocks.AIR.defaultBlockState();
        BlockState SS = Blocks.SOUL_SAND.defaultBlockState();

        // === 1. الأرض السوداء ===
        for (int x = -45; x <= 45; x++)
            for (int z = -45; z <= 45; z++) {
                double d = Math.sqrt(x*x + z*z);
                if (d <= 45) {
                    level.setBlock(c.offset(x, -2, z), O, 3);
                    level.setBlock(c.offset(x, -1, z), d > 42 ? SS : (d > 36 ? BK : PB), 3);
                    // الشقوق البركانية (Lava veins)
                    if (d < 35 && Math.abs(x) % 12 == 0 && Math.abs(z) % 12 == 0)
                        level.setBlock(c.offset(x, -2, z), Blocks.LAVA.defaultBlockState(), 3);
                }
            }

        // === 2. الجبال المظلمة (تضاريس ضخمة) ===
        int[][] mts2 = {{-50,-50},{50,-50},{-50,50},{50,50},{-60,0},{60,0}};
        for (int[] m : mts2) {
            BlockPos mp = c.offset(m[0], -5, m[1]);
            for (int y = 0; y < 40; y++) {
                int r = 22 - y / 2;
                if (r < 2) r = 2;
                for (int x = -r; x <= r; x++)
                    for (int z = -r; z <= r; z++)
                        if (Math.sqrt(x*x + z*z) <= r)
                            level.setBlock(mp.offset(x, y, z), BK, 3);
            }
        }

        // === 3. خندق الظلام (نار) ===
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++) {
                double d = Math.sqrt(x*x + z*z);
                if (d > 36 && d < 40)
                    level.setBlock(c.offset(x, -2, z), Blocks.LAVA.defaultBlockState(), 3);
            }

        // === 4. سور خارجي (R=34) بأبراج جمجمة ===
        for (int y = 0; y < 20; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(34 * Math.cos(rad));
                int z = (int)(34 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 4 == 0) ? P : BK, 3);
                if (y == 19 && i % 5 == 0) level.setBlock(c.offset(x, 20, z), P, 3);
            }
        }
        // 8 أبراج بفرش الجماجم
        for (int i = 0; i < 8; i++) {
            double rad = Math.toRadians(i * 45);
            buildDarkSpire(level, c.offset((int)(34*Math.cos(rad)), 0, (int)(34*Math.sin(rad))), O, P, 30);
        }

        // === 5. بوابة رهيبة ===
        for (int y = 0; y < 18; y++) {
            level.setBlock(c.offset(-6, y, -34), O, 3);
            level.setBlock(c.offset(6, y, -34), O, 3);
        }
        for (int x = -6; x <= 6; x++)
            for (int y = 18; y < 26; y++)
                level.setBlock(c.offset(x, y, -34), P, 3);
        level.setBlock(c.offset(-4, 10, -33), PG, 3);
        level.setBlock(c.offset(4, 10, -33), PG, 3);
        level.setBlock(c.offset(0, 22, -34), AM, 3);

        // === 6. سور داخلي (R=22) ===
        for (int y = 0; y < 14; y++) {
            for (int i = 0; i < 360; i++) {
                double rad = Math.toRadians(i);
                int x = (int)(22 * Math.cos(rad));
                int z = (int)(22 * Math.sin(rad));
                level.setBlock(c.offset(x, y, z), (y % 3 == 0) ? P : BK, 3);
            }
        }
        buildDarkSpire(level, c.offset(-22, 0, 0), O, P, 22);
        buildDarkSpire(level, c.offset(22, 0, 0), O, P, 22);

        // === 7. القصر المركزي (برج عملاق أسود) ===
        for (int y = 0; y < 35; y++) {
            for (int x = -10; x <= 10; x++) {
                for (int z = -8; z <= 8; z++) {
                    boolean wall = (x == -10 || x == 10 || z == -8 || z == 8);
                    if (wall) level.setBlock(c.offset(x, y, z), (y % 7 == 0) ? P : (y < 18 ? BK : O), 3);
                    else if (y == 0) level.setBlock(c.offset(x, y, z), PB, 3);
                    else level.setBlock(c.offset(x, y, z), AIR, 3);
                    if (wall && (y == 8 || y == 16) && x % 4 == 0) level.setBlock(c.offset(x, y, z), PG, 3);
                    if (wall && (y == 8 || y == 16) && z % 3 == 0) level.setBlock(c.offset(x, y, z), PG, 3);
                }
            }
        }
        // مدخل القصر بأبواب حديدية ثقيلة
        placeDetailedDoor(level, c.offset(0, 0, -8), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(1, 0, -8), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        placeDetailedDoor(level, c.offset(-1, 0, -8), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
        level.setBlock(c.offset(0, 2, -8), PG, 3);
        level.setBlock(c.offset(1, 2, -8), PG, 3);
        level.setBlock(c.offset(-1, 2, -8), PG, 3);

        // === 8. عرش الظلام بكريستال أرجواني ===
        for (int x = -4; x <= 4; x++)
            for (int z = 0; z <= 5; z++)
                level.setBlock(c.offset(x, 0, z), PB, 3);
        for (int y = 1; y < 6; y++) level.setBlock(c.offset(-3, y, 5), AM, 3);
        for (int y = 1; y < 6; y++) level.setBlock(c.offset(3, y, 5), AM, 3);
        for (int y = 1; y < 8; y++) level.setBlock(c.offset(0, y, 5), AM, 3);

        // === 9. أبراج شيطانية ملتوية حول القصر ===
        buildJaggedSpire(level, c.offset(-10, 0, -8), 42, 6, O, P);
        buildJaggedSpire(level, c.offset(10, 0, -8), 42, 6, O, P);
        buildJaggedSpire(level, c.offset(-10, 0, 8), 38, 5, O, P);
        buildJaggedSpire(level, c.offset(10, 0, 8), 38, 5, O, P);

        // === 10. طريق الظلام ===
        for (int z = -32; z < -8; z++) {
            level.setBlock(c.offset(-2, -1, z), BK, 3);
            level.setBlock(c.offset(2, -1, z), BK, 3);
            level.setBlock(c.offset(-1, -1, z), PB, 3);
            level.setBlock(c.offset(0, -1, z), P, 3);
            level.setBlock(c.offset(1, -1, z), PB, 3);
            if (z % 5 == 0) {
                buildLampPost(level, c.offset(-3, 0, z), O, P);
                buildLampPost(level, c.offset(3, 0, z), O, P);
            }
        }

        // === 11. مستوطنة الظلام (مذابح وأبراج مراقبة) ===
        for (int i = 0; i < 10; i++) {
            double rad = Math.toRadians(i * 36 + 10);
            buildDarkShrine(level, c.offset((int)(27*Math.cos(rad)), 0, (int)(27*Math.sin(rad))), O, P, AM);
        }
        for (int i = 0; i < 6; i++) {
            double rad = Math.toRadians(i * 60 + 5);
            buildDarkShrine(level, c.offset((int)(16*Math.cos(rad)), 0, (int)(16*Math.sin(rad))), BK, P, AM);
        }

        // === 12. حفرة الأرواح (تحت القصر) ===
        for (int x = -5; x <= 5; x++)
            for (int z = -5; z <= 5; z++)
                for (int y = -3; y < 0; y++)
                    level.setBlock(c.offset(x, y, z), SS, 3);
        level.setBlock(c.offset(0, -1, 0), Blocks.SOUL_FIRE.defaultBlockState(), 3);
        level.setBlock(c.offset(2, -1, 2), Blocks.SOUL_FIRE.defaultBlockState(), 3);
        level.setBlock(c.offset(-2, -1, -2), Blocks.SOUL_FIRE.defaultBlockState(), 3);

        // === 13. أبراج المراقبة الأربعة (حول السور الخارجي) ===
        for (int i = 0; i < 4; i++) {
            double rad = Math.toRadians(i * 90 + 0);
            int wx = (int)(34 * Math.cos(rad));
            int wz = (int)(34 * Math.sin(rad));
            for (int y = 0; y < 3; y++) level.setBlock(c.offset(wx, y, wz), AM, 3);
            level.setBlock(c.offset(wx, 3, wz), P, 3);
        }
    }

    /** مذبح صغير في مستوطنة الظلام */
    private static void buildDarkShrine(ServerLevel level, BlockPos p, BlockState wall, BlockState accent, BlockState crystal) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setBlock(p.offset(x, -1, z), wall, 3);
        for (int y = 0; y < 4; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++)
                    if (Math.abs(x)==2 || Math.abs(z)==2) level.setBlock(p.offset(x, y, z), (y%2==0)?accent:wall, 3);
        // بلورة مركزية
        for (int y = 0; y < 4; y++) level.setBlock(p.above(y), crystal, 3);
        level.setBlock(p.offset(0, 1, 2), Blocks.PURPLE_STAINED_GLASS_PANE.defaultBlockState(), 3);
        placeDetailedDoor(level, p.offset(0, 0, -2), Blocks.IRON_DOOR.defaultBlockState(), net.minecraft.core.Direction.NORTH);
    }

    private static void buildDarkSpire(ServerLevel level, BlockPos base, BlockState wall, BlockState accent, int height) {
        for (int y = 0; y < height; y++) {
            int r = Math.max(1, 4 - y / 5);
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) == r || Math.abs(z) == r)
                        level.setBlock(base.offset(x, y, z), (y % 5 == 0) ? accent : wall, 3);
                }
            }
        }
        level.setBlock(base.above(height), accent, 3);
    }

    private static void transformTerrainForDarkCastle(ServerLevel level, BlockPos c) {
        for (int x = -100; x <= 100; x += 5) {
            for (int z = -100; z <= 100; z += 5) {
                BlockPos target = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, c.offset(x, 0, z)).below();
                level.setBlock(target, Blocks.SOUL_SAND.defaultBlockState(), 3);
            }
        }
    }

    // =====================================================================
    //  Advanced Architectural Helpers (المولدات المعمارية المعقدة)
    // =====================================================================

    /** رسم قبة ناعمة دقيقة */
    private static void buildSmoothDome(ServerLevel level, BlockPos c, int radius, BlockState mat, BlockState accent) {
        for (int y = 0; y <= radius; y++) {
            double r = Math.sqrt(radius * radius - y * y);
            for (int x = -(int)r; x <= (int)r; x++) {
                for (int z = -(int)r; z <= (int)r; z++) {
                    double dist = Math.sqrt(x*x + z*z);
                    if (dist <= r && dist > r - 1.5) {
                        level.setBlock(c.offset(x, y, z), (y % 4 == 0) ? accent : mat, 3);
                    }
                }
            }
        }
    }

    /** رسم سقف باغودا منحني (لقلعة الهواء) */
    private static void buildPagodaRoof(ServerLevel level, BlockPos c, int width, int length, BlockState mat) {
        int height = Math.max(width, length) / 2;
        for (int y = 0; y < height; y++) {
            int curW = width - y * 2 + (int)(Math.pow(y, 1.2) * 0.3); // Curve effect
            int curL = length - y * 2 + (int)(Math.pow(y, 1.2) * 0.3);
            if (curW < 1) curW = 1;
            if (curL < 1) curL = 1;
            for (int x = -curW; x <= curW; x++) {
                for (int z = -curL; z <= curL; z++) {
                    if (Math.abs(x) >= curW - 1 || Math.abs(z) >= curL - 1) {
                        level.setBlock(c.offset(x, y, z), mat, 3);
                        // أطراف بارزة للأعلى (Curved eaves)
                        if (y < height - 1 && Math.abs(x) == curW && Math.abs(z) == curL) {
                            level.setBlock(c.offset(x, y + 1, z), mat, 3);
                        }
                    }
                }
            }
        }
    }

    /** رسم برج متعرج حاد (للنار والظلام) */
    private static void buildJaggedSpire(ServerLevel level, BlockPos c, int height, int baseRadius, BlockState mat, BlockState spike) {
        for (int y = 0; y < height; y++) {
            int r = baseRadius - (y * baseRadius / height);
            if (r < 1) r = 1;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    double dist = Math.sqrt(x*x + z*z);
                    // تذبذب عشوائي يعطي شكل مدبب وحاد
                    if (dist <= r + Math.sin(y * 0.5) * 1.5) {
                        level.setBlock(c.offset(x, y, z), mat, 3);
                    }
                    if (y % 6 == 0 && dist == r) { // أشواك جانبية
                        level.setBlock(c.offset(x * 2, y, z * 2), spike, 3);
                    }
                }
            }
        }
    }

    /** وضع باب حقيقي قابل للفتح */
    private static void placeDetailedDoor(ServerLevel level, BlockPos p, BlockState doorState, net.minecraft.core.Direction facing) {
        BlockState lower = doorState.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER).setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing);
        BlockState upper = doorState.setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER).setValue(net.minecraft.world.level.block.DoorBlock.FACING, facing);
        level.setBlock(p, lower, 3);
        level.setBlock(p.above(), upper, 3);
    }

    private static BlockState wallOf(String e) { return switch(e){case "fire"->Blocks.NETHER_BRICKS.defaultBlockState();case "water"->Blocks.PRISMARINE_BRICKS.defaultBlockState();case "earth"->Blocks.DEEPSLATE_BRICKS.defaultBlockState();case "light"->Blocks.QUARTZ_BRICKS.defaultBlockState();case "dark"->Blocks.BLACKSTONE.defaultBlockState();case "air"->Blocks.WHITE_CONCRETE.defaultBlockState();default->Blocks.QUARTZ_BRICKS.defaultBlockState();}; }
    private static BlockState accOf(String e)  { return switch(e){case "fire"->Blocks.CHISELED_NETHER_BRICKS.defaultBlockState();case "water"->Blocks.DARK_PRISMARINE.defaultBlockState();case "earth"->Blocks.CHISELED_DEEPSLATE.defaultBlockState();case "light"->Blocks.GOLD_BLOCK.defaultBlockState();case "dark"->Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();case "air"->Blocks.WHITE_STAINED_GLASS.defaultBlockState();default->Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState();}; }
    private static BlockState floorOf(String e){ return switch(e){case "fire"->Blocks.MAGMA_BLOCK.defaultBlockState();case "water"->Blocks.LAPIS_BLOCK.defaultBlockState();case "earth"->Blocks.MUD_BRICKS.defaultBlockState();case "light"->Blocks.GLOWSTONE.defaultBlockState();case "dark"->Blocks.CRYING_OBSIDIAN.defaultBlockState();case "air"->Blocks.SMOOTH_QUARTZ.defaultBlockState();default->Blocks.SMOOTH_QUARTZ.defaultBlockState();}; }
    private static BlockState stairOf(String e){ return switch(e){case "fire"->Blocks.NETHER_BRICK_STAIRS.defaultBlockState();case "water"->Blocks.PRISMARINE_BRICK_STAIRS.defaultBlockState();case "earth"->Blocks.DEEPSLATE_BRICK_STAIRS.defaultBlockState();case "light"->Blocks.QUARTZ_STAIRS.defaultBlockState();case "dark"->Blocks.BLACKSTONE_STAIRS.defaultBlockState();case "air"->Blocks.QUARTZ_STAIRS.defaultBlockState();default->Blocks.QUARTZ_STAIRS.defaultBlockState();}; }
}
