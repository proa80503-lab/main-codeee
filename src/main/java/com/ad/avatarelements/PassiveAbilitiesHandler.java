package com.ad.avatarelements;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.DamageTypeTags;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;

@EventBusSubscriber(modid = AvatarElements.MODID)
public class PassiveAbilitiesHandler {

    /** إدارة الحصانات (Immunity) باستخدام حدث دمج الكائنات (Damage Event) */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof Player player) {
            String element = getElement(player);
            if (element.equals("none")) return;

            DamageSource source = event.getSource();

            // مناعة ضد ضرر السقوط لجميع العناصر (بسبب النزول الآمن التلقائي)
            if (source.is(net.minecraft.world.damagesource.DamageTypes.FALL)) {
                event.setNewDamage(0f); // إلغاء الضرر
            }

            // 2. النار: مناعة ضد النار والردع الحراري (Thorns of Fire)
            else if (element.equals("fire")) {
                if (source.is(DamageTypeTags.IS_FIRE)) {
                    event.setNewDamage(0f);
                    player.clearFire(); // إطفاء الحريق عن اللاعب
                } else if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker) {
                    attacker.igniteForSeconds(4); // إشعال المهاجم
                }
            }
        }

        // --- هجوم اللاعب: زيادة الضرر (Avatar Elemental Buffs) ---
        if (event.getSource().getEntity() instanceof Player attacker) {
            String element = getElement(attacker);
            if (element.equals("none")) return;
            
            float damage = event.getOriginalDamage();
            float buff = 1.0f; 
            
            if (element.equals("water")) {
                // قوة القمر والمطر
                if (attacker.level().isNight() || attacker.level().isRaining()) buff = 2.0f;
            } 
            else if (element.equals("earth")) {
                if (attacker.onGround()) buff = 1.5f;
            }
            else if (element.equals("dark")) {
                // قوة الظلام تتضاعف عند الاختفاء
                if (attacker.level().isNight() || attacker.hasEffect(MobEffects.INVISIBILITY)) buff = 1.8f;
            }
            else if (element.equals("light")) {
                if (attacker.level().isDay()) buff = 1.8f;
            }
            else if (element.equals("fire")) {
                if (attacker.level().isDay()) buff = 1.8f; // قوة الشمس
                // القبضة المشتعلة (Flaming Fists)
                if (event.getEntity() instanceof net.minecraft.world.entity.LivingEntity target) {
                    target.igniteForSeconds(5);
                }
            }

            if (buff > 1.0f) event.setNewDamage(damage * buff);
        }
    }

    /** الجبل الثابت: منع الدفع كליاً لمسخر الأرض */
    @SubscribeEvent
    public static void onKnockback(net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (getElement(player).equals("earth") && player.onGround()) {
                event.setCanceled(true); 
            }
        }
    }

    /** إدارة سرعة التكسير لعنصر الأرض */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (getElement(player).equals("earth")) {
            // سرعة تكسير هائلة جداً (مضروبة في 5)
            event.setNewSpeed(event.getOriginalSpeed() * 8.0f);
        }
    }

    /** إدارة قدرة حصاد (جمع) الموارد بفرع الأرض باليد العارية */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        Player player = event.getEntity();
        if (getElement(player).equals("earth")) {
            event.setCanHarvest(true); // يمكنه تكسير وإسقاط أي بلوكة بيده!
        }
    }

    /** إدارة التأثيرات المستمرة (Passive Ticks) مثل التنفس تحت الماء، والرؤية الليلية */
    @SubscribeEvent
    public static void onPlayerTickPassive(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        String element = getElement(player);
        if (element.equals("none")) return;

        // --- النزول الآمن (Safe Fall) التلقائي في الهواء ---
        if (!player.onGround() && player.getDeltaMovement().y < -0.2 && player.fallDistance > 3.0f) {
            player.setDeltaMovement(player.getDeltaMovement().x, -0.25, player.getDeltaMovement().z);
            player.hurtMarked = true;

            if (player.tickCount % 2 == 0 && player.level() instanceof net.minecraft.server.level.ServerLevel sLevel) {
                double px = player.getX();
                double py = player.getY() - 0.5;
                double pz = player.getZ();
                
                switch (element) {
                    case "earth":
                        sLevel.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()), px + (Math.random()-0.5)*0.8, py, pz + (Math.random()-0.5)*0.8, 4, 0.1, 0.1, 0.1, 0.05);
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, px, py, pz, 1, 0.2, 0.1, 0.2, 0.01);
                        break;
                    case "fire":
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, px + (Math.random()-0.5)*0.8, py, pz + (Math.random()-0.5)*0.8, 5, 0.1, 0.1, 0.1, 0.05);
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, px, py, pz, 2, 0.1, 0.1, 0.1, 0.01);
                        break;
                    case "water":
                        sLevel.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, net.minecraft.world.level.block.Blocks.ICE.defaultBlockState()), px + (Math.random()-0.5)*0.8, py, pz + (Math.random()-0.5)*0.8, 4, 0.1, 0.1, 0.1, 0.05);
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SNOWFLAKE, px, py, pz, 2, 0.2, 0.1, 0.2, 0.01);
                        break;
                    case "air":
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, px, py, pz, 4, 0.3, 0.1, 0.3, 0.02);
                        break;
                    case "light":
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD, px + (Math.random()-0.5)*0.8, py, pz + (Math.random()-0.5)*0.8, 3, 0.1, 0.1, 0.1, 0.01);
                        break;
                    case "dark":
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK, px + (Math.random()-0.5)*0.8, py, pz + (Math.random()-0.5)*0.8, 4, 0.1, 0.1, 0.1, 0.02);
                        sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL, px, py, pz, 2, 0.2, 0.1, 0.2, 0.01);
                        break;
                }
            }
        }

        // Premium Passive Aesthetic Trails
        if (player.tickCount % 2 == 0) {
            net.minecraft.server.level.ServerLevel sLevel = player.level() instanceof net.minecraft.server.level.ServerLevel ? (net.minecraft.server.level.ServerLevel)player.level() : null;
            if (sLevel != null) {
                if (element.equals("fire")) {
                    double ang = (player.tickCount * 0.2) % (Math.PI * 2);
                    double px = player.getX() + 0.8 * Math.cos(ang);
                    double pz = player.getZ() + 0.8 * Math.sin(ang);
                    sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, px, player.getY() + 0.1 + Math.abs(Math.cos(player.tickCount * 0.1)), pz, 1, 0.0, 0.0, 0.0, 0.02);
                }
                else if (element.equals("water")) {
                    if (Math.random() < 0.3) sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.FISHING, player.getX() + (Math.random()-0.5)*1.5, player.getY() + Math.random()*2, player.getZ() + (Math.random()-0.5)*1.5, 1, 0, 0, 0, 0);
                }
                else if (element.equals("dark")) {
                    if (Math.random() < 0.2) sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.SQUID_INK, player.getX() + (Math.random()-0.5)*1.5, player.getY() + Math.random()*2, player.getZ() + (Math.random()-0.5)*1.5, 1, 0, 0, 0, 0);
                }
                else if (element.equals("air")) {
                    sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, player.getX(), player.getY() + 0.1, player.getZ(), 1, 0.3, 0.1, 0.3, 0.01);
                }
            }
        }

        // خصائص الماء (Water Healing & Frost Walker)
        if (element.equals("water")) {
            if (player.getAirSupply() < player.getMaxAirSupply()) player.setAirSupply(player.getMaxAirSupply());
            if (player.isInWater()) {
                player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, 40, 0, false, false, false));
            }
            // الشفاء المائي
            if ((player.isInWater() || player.level().isRainingAt(player.blockPosition())) && player.tickCount % 40 == 0) {
                if (player.getHealth() < player.getMaxHealth()) player.heal(1.0f);
            }
            // المشي على الجليد
            if (player.isSprinting() && !player.isInWater() && player.level().getBlockState(player.blockPosition().below()).is(net.minecraft.world.level.block.Blocks.WATER)) {
                player.level().setBlock(player.blockPosition().below(), net.minecraft.world.level.block.Blocks.FROSTED_ICE.defaultBlockState(), 3);
            }
        }
        // خصائص النور (Auto Cleanse & Sun Blinding)
        else if (element.equals("light")) {
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20*15, 0, false, false, false));
            player.removeEffect(MobEffects.BLINDNESS);
            player.removeEffect(MobEffects.POISON);
            player.removeEffect(MobEffects.WITHER);
            if (player.level().isDay()) {
                player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 40, 0, false, false, false));
                if (player.tickCount % 20 == 0) {
                    for (net.minecraft.world.entity.Mob mob : player.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class, player.getBoundingBox().inflate(10.0))) {
                        if (mob.getTarget() == player) {
                            mob.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
                            mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 60, 1));
                        }
                    }
                }
            }
        }
        // خصائص الظلام (Auto-Invisibility in Shadows)
        else if (element.equals("dark")) {
            player.removeEffect(MobEffects.POISON);
            player.removeEffect(MobEffects.WITHER);
            net.minecraft.core.BlockPos pos = player.blockPosition();
            int skyLight = player.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
            int blockLight = player.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
            // الاختفاء إذا كان الظل قوياً (ليلاً أو تحت شجرة)
            if ((!player.level().isDay() || skyLight < 12) && blockLight < 8) {
                player.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 10, 0, false, false, false));
            }
        }
        // خصائص الأرض (Tremorsense)
        else if (element.equals("earth")) {
            if (player.onGround() && player.tickCount % 20 == 0) {
                for (net.minecraft.world.entity.LivingEntity entity : player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, player.getBoundingBox().inflate(15.0))) {
                    if (entity != player && entity.onGround() && entity.getDeltaMovement().lengthSqr() > 0.001) {
                        entity.addEffect(new MobEffectInstance(MobEffects.GLOWING, 35, 0, false, false, false));
                    }
                }
            }
        }
        // خصائص الهواء (Projectile Deflection Shield)
        else if (element.equals("air")) {
            if (player.tickCount % 2 == 0) {
                for (net.minecraft.world.entity.projectile.Projectile proj : player.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class, player.getBoundingBox().inflate(4.0))) {
                    if (proj.getOwner() != player) {
                        net.minecraft.world.phys.Vec3 push = proj.position().subtract(player.position()).normalize().scale(1.5);
                        proj.setDeltaMovement(push);
                        proj.hasImpulse = true;
                        
                        // إضافة تأثير رياح عند إبعاد السهم
                        if (player.level() instanceof net.minecraft.server.level.ServerLevel sLevel) {
                            sLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD, proj.getX(), proj.getY(), proj.getZ(), 5, 0.1, 0.1, 0.1, 0.1);
                        }
                    }
                }
            }
        }
    }

    private static String getElement(Player player) {
        try {
            PlayerElementData data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
            return data != null && data.currentElement() != null ? data.currentElement() : "none";
        } catch (Exception e) {
            return "none";
        }
    }
}
