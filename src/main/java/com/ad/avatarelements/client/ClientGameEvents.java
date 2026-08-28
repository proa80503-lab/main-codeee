package com.ad.avatarelements.client;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import com.ad.avatarelements.client.gui.ElementSelectScreen;
import com.ad.avatarelements.client.gui.AbilitySelectionScreen;
import com.ad.avatarelements.AvatarElements;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = AvatarElements.MODID, value = Dist.CLIENT)
public class ClientGameEvents {

    // ─── Shift long-press detection ───────────────────────────────────────────
    private static int     shiftHeldTicks    = 0;
    private static boolean shiftScreenOpened = false;
    /** عدد الـ ticks للضغط المطول قبل فتح الشاشة (15 = 0.75 ثانية) */
    private static final int SHIFT_HOLD_THRESHOLD = 15;

    // ─── Bare-hand combo (left click) ─────────────────────────────────────────
    private static boolean wasMouseLeftDown  = false;
    private static int     lastComboSentTick = -100;
    /** الحد الأدنى بين كل ضربة Combo بالـ ticks */
    private static final int COMBO_SEND_COOLDOWN = 8;

    /** عداد Combo محلي للـ HUD (يُحدَّث من هنا ويُقرأ من ElementHudRenderer) */
    public static int  clientComboCount   = 0;
    public static long lastComboDisplayMs = 0L;
    private static int lastComboClientTick    = -100;
    private static final int COMBO_DISPLAY_EXPIRE = 55; // ~2.75 ثانية
    
    // ─── Quick ability switch (Shift + 1-4) ──────────────────────────────────
    private static final boolean[] wasNumberDown = new boolean[4];

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        // ── تتبع حالة الماوس دائماً (حتى عند فتح شاشات أخرى) ──────────────
        long windowHandle = mc.getWindow().getWindow();
        boolean isMouseLeftNow = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                                    == GLFW.GLFW_PRESS;
        boolean mouseLClick = isMouseLeftNow && !wasMouseLeftDown; // حافة صاعدة فقط
        wasMouseLeftDown = isMouseLeftNow;

        if (mc.player == null) {
            shiftHeldTicks    = 0;
            shiftScreenOpened = false;
            return;
        }

        // إنهاء عداد Combo في HUD بعد انتهاء المدة
        if (mc.player.tickCount - lastComboClientTick > COMBO_DISPLAY_EXPIRE) {
            clientComboCount = 0;
        }

        // تجاهل منطق الأزرار عند فتح أي شاشة
        if (mc.screen != null) {
            shiftHeldTicks    = 0;
            shiftScreenOpened = false;
            return;
        }

        // ── 1. فتح شاشة اختيار العنصر (K) ───────────────────────────────
        if (ClientSetup.OPEN_SELECTION_KEY.consumeClick()) {
            mc.setScreen(new ElementSelectScreen());
        }

        // ── 2. القدرة النشطة (]) ─────────────────────────────────────────
        while (ClientSetup.USE_ABILITY_KEY.consumeClick()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.ad.avatarelements.network.UseAbilityPayload(true));
        }

        // ── 3. القوة الخاصة ([) ──────────────────────────────────────────
        while (ClientSetup.USE_SPECIAL_ABILITY_KEY.consumeClick()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.ad.avatarelements.network.UseSpecialAbilityPayload(true));
        }

        // ── 4. Shift مطول → شاشة اختيار المهارة ──────────────────────────
        boolean leftShiftDown  = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS;
        boolean rightShiftDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean shiftDown = leftShiftDown || rightShiftDown;

        if (shiftDown) {
            shiftHeldTicks++;
            
            // التبديل السريع باستخدام Shift + 1/2/3/4
            for (int i = 0; i < 4; i++) {
                int key = GLFW.GLFW_KEY_1 + i;
                boolean isDown = GLFW.glfwGetKey(windowHandle, key) == GLFW.GLFW_PRESS;
                if (isDown && !wasNumberDown[i]) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.ad.avatarelements.network.SetActiveAbilityPayload(i + 1)
                    );
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal("§eتم اختيار المهارة " + (i + 1)), true);
                    shiftHeldTicks = 0; // إعادة التعيين لمنع فتح الشاشة فجأة
                }
                wasNumberDown[i] = isDown;
            }

            if (shiftHeldTicks >= SHIFT_HOLD_THRESHOLD && !shiftScreenOpened) {
                try {
                    String el = mc.player.getData(
                        com.ad.avatarelements.attachment.ModAttachmentTypes.ELEMENT_DATA
                    ).currentElement();
                    if (!el.equals("none")) {
                        mc.setScreen(new AbilitySelectionScreen());
                        shiftScreenOpened = true;
                    }
                } catch (Exception ignored) {}
            }
        } else {
            shiftHeldTicks    = 0;
            shiftScreenOpened = false;
            // تحديث حالة الأزرار حتى لا تتفعل فور الضغط على Shift لاحقاً
            for (int i = 0; i < 4; i++) {
                wasNumberDown[i] = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_1 + i) == GLFW.GLFW_PRESS;
            }
        }

        // ── 5. Left-click باليد العارية → Elemental Combo ────────────────
        if (mouseLClick
                && !shiftDown                                   // لا تنشط التوقيت أثناء فتح الشاشة
                && mc.player.getMainHandItem().isEmpty()        // يد عارية فقط
                && (mc.player.tickCount - lastComboSentTick >= COMBO_SEND_COOLDOWN)) {
            try {
                String el = mc.player.getData(
                    com.ad.avatarelements.attachment.ModAttachmentTypes.ELEMENT_DATA
                ).currentElement();
                if (!el.equals("none")) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.ad.avatarelements.network.UseComboHitPayload());
                    lastComboSentTick = mc.player.tickCount;

                    // تحديث عداد Combo المحلي للـ HUD
                    if (mc.player.tickCount - lastComboClientTick > COMBO_DISPLAY_EXPIRE) {
                        clientComboCount = 1;
                    } else {
                        clientComboCount = Math.min(clientComboCount + 1, 4);
                    }
                    lastComboClientTick = mc.player.tickCount;
                    lastComboDisplayMs  = System.currentTimeMillis();
                }
            } catch (Exception ignored) {}
        }
    }
}
