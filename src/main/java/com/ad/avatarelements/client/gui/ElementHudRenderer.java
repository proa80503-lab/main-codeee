package com.ad.avatarelements.client.gui;

import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import com.ad.avatarelements.client.ClientGameEvents;
import com.ad.avatarelements.AvatarElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;

/**
 * HUD النهائي لـ AvatarElements:
 * - لوحة عنصر + شريط طاقة + جزيئات دوارة
 * - مؤشر المهارة النشطة (Slot 1-4 + الاسم)
 * - عداد Combo متحرك في وسط الشاشة مع Fade-Out
 */
@EventBusSubscriber(modid = AvatarElements.MODID, value = Dist.CLIENT)
public class ElementHudRenderer {

    private static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(AvatarElements.MODID, "element_hud");

    // أسماء العناصر وألوانها
    private static final String[] IDS      = {"fire","water","earth","air","light","dark"};
    private static final String[] NAMES_AR = {"النار","الماء","الأرض","الهواء","النور","الظلام"};
    private static final int[]    COLORS   = {
        0xFFFF6200, 0xFF0088FF, 0xFF8B6914,
        0xFF88AACC, 0xFFFFDD00, 0xFF9900CC
    };

    // أسماء المهارات المختصرة لكل عنصر × 4 فتحات
    private static final String[][] SLOT_NAMES = {
        { "انفجار ناري", "جدار النار",  "درع اللهب",   "اندفاع صاعقة" },
        { "تسونامي",     "قفص الجليد",  "شفاء مائي",   "درع أمواج"    },
        { "موجة زلزال", "اقتلاع أرض",  "جدار صخري",   "رمي صخرة"     },
        { "دوامة ريح",  "طيران حر",    "اندفاع برق",  "عاصفة كبرى"   },
        { "شعاع نور",   "قصف نوراني",  "درع النور",   "صاعقة مقدسة"  },
        { "نوفا ظلام",  "سرقة أرواح",  "خطوة الظل",   "رعب الظلام"   },
    };

    // نصوص Combo
    private static final String[] COMBO_LABELS = {
        "", "×1 COMBO", "×2 COMBO!!", "×3 COMBO!!!", "★ FINISHER! ★"
    };
    private static final int[] COMBO_COLORS = {
        0xFFFFFFFF, 0xFFFFCC00, 0xFFFF8800, 0xFFFF4400, 0xFFFF0066
    };

    /** مدة ظهور / تلاشي الـ Combo counter بالميللي ثانية */
    private static final long COMBO_FADE_MS = 1800L;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(LAYER_ID, ElementHudRenderer::renderHud);
    }

    // ─────────────────────── دالة الرندر الرئيسية ──────────────────────────
    private static void renderHud(GuiGraphics g, net.minecraft.client.DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) return;

        PlayerElementData data;
        try {
            data = player.getData(ModAttachmentTypes.ELEMENT_DATA);
        } catch (Exception e) { return; }

        String element = data.currentElement();
        if (element == null || element.equals("none")) return;

        int idx = getIndex(element);
        if (idx < 0) return;

        int    primary = COLORS[idx];
        String nameAr  = NAMES_AR[idx];
        int    stam    = data.stamina();
        int    maxStam = data.maxStamina();
        float  ratio   = maxStam > 0 ? (float)stam / maxStam : 0f;
        int    slot    = Math.max(1, Math.min(4, data.activeAbility()));

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        long now = System.currentTimeMillis();
        float pulse = (float)(Math.sin(now * 0.004) * 0.5 + 0.5);

        // ── 1. اللوحة الرئيسية ─────────────────────────────────────────
        int panelW = 120;
        int panelH = 60;   // أوسع من السابق لاستيعاب مؤشر الفتحة
        int px = 8;
        int py = sh - panelH - 42;

        renderPanel(g, mc, px, py, panelW, panelH, primary, nameAr, stam, maxStam, ratio, pulse);

        // ── 2. مؤشر المهارة النشطة (تحت شريط الطاقة) ──────────────────
        renderSlotIndicator(g, mc, px, py, panelW, panelH, primary, idx, slot, now);

        // ── 3. عداد Combo في وسط الشاشة ────────────────────────────────
        renderComboCounter(g, mc, sw, sh, now);
    }

    // ─────────────────────────── لوحة العنصر ────────────────────────────────
    private static void renderPanel(GuiGraphics g, Minecraft mc,
                                     int px, int py, int panelW, int panelH,
                                     int primary, String nameAr,
                                     int stam, int maxStam, float ratio, float pulse) {

        // ظل خارجي
        g.fill(px - 2, py - 2, px + panelW + 2, py + panelH + 2, 0x44000000);

        // إطار ملون
        g.fill(px,             py,              px + panelW, py + 1,          primary);
        g.fill(px,             py + panelH - 1, px + panelW, py + panelH,     primary);
        g.fill(px,             py,              px + 1,      py + panelH,     primary);
        g.fill(px + panelW-1, py,              px + panelW, py + panelH,     primary);
        // خلفية
        g.fill(px + 1, py + 1, px + panelW - 1, py + panelH - 1, 0xCC080F1C);

        // وميض الحافة العليا
        int pA = (int)(20 + pulse * 50);
        g.fill(px + 1, py + 1, px + panelW - 1, py + 2, (pA << 24) | (primary & 0x00FFFFFF));

        // اسم العنصر
        if (mc.font != null)
            g.drawString(mc.font, nameAr, px + 6, py + 6, primary, true);

        // شريط الطاقة
        int barX = px + 6;
        int barY = py + 21;
        int barW = panelW - 12;
        int barH = 7;
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF0D141F);
        int fillW = (int)(barW * ratio);
        if (fillW > 0) {
            g.fill(barX, barY, barX + fillW, barY + barH, primary);
            g.fill(barX + 1, barY + 1, barX + fillW - 1, barY + 3, 0x33FFFFFF);
            int spA = (int)(60 + pulse * 150);
            g.fill(barX + fillW - 1, barY, barX + fillW, barY + barH, (spA << 24) | 0x00FFFFFF);
        }
        // رقم الطاقة
        if (mc.font != null)
            g.drawString(mc.font, stam + " / " + maxStam, barX + 1, barY + barH + 3, 0xFF445566, false);

        // جزيئات دوارة
        long now = System.currentTimeMillis();
        int cx = px + panelW / 2;
        int cy = py + panelH / 2 - 2; // مرفوع قليلاً بسبب التوسيع
        double rx = panelW / 2.0 + 4;
        double ry = 26;
        for (int i = 0; i < 4; i++) {
            double angle = Math.toRadians(now * 0.14 + i * 90.0);
            int ppx = cx + (int)(rx * Math.cos(angle));
            int ppy = cy + (int)(ry * Math.sin(angle));
            int alpha = (int)(60 + 160 * Math.abs(Math.sin(angle)));
            g.fill(ppx, ppy, ppx + 2, ppy + 2, (alpha << 24) | (primary & 0x00FFFFFF));
        }
    }

    // ───────────────────── مؤشر المهارة النشطة ──────────────────────────────
    private static void renderSlotIndicator(GuiGraphics g, Minecraft mc,
                                             int px, int py, int panelW, int panelH,
                                             int primary, int elementIdx, int slot, long now) {
        if (mc.font == null) return;

        // الفاصل
        int sepY = py + panelH - 19;
        g.fill(px + 4, sepY, px + panelW - 4, sepY + 1, 0x33000000 | (primary & 0x00FFFFFF));

        // النص: "[1] اسم المهارة"
        String slotLabel = "[" + slot + "] " + SLOT_NAMES[elementIdx][slot - 1];
        // اقتطاع إذا كان طويلاً جداً
        while (mc.font.width(slotLabel) > panelW - 12 && slotLabel.length() > 6) {
            slotLabel = slotLabel.substring(0, slotLabel.length() - 4) + "..";
        }

        float pulse = (float)(Math.sin(now * 0.005) * 0.5 + 0.5);
        int txtAlpha = (int)(160 + pulse * 95);
        int txtColor = (txtAlpha << 24) | (primary & 0x00FFFFFF);

        g.drawString(mc.font, slotLabel, px + 6, sepY + 4, txtColor, false);
    }

    // ─────────────────────── عداد Combo في الوسط ────────────────────────────
    private static void renderComboCounter(GuiGraphics g, Minecraft mc, int sw, int sh, long now) {
        if (mc.font == null) return;

        int combo = ClientGameEvents.clientComboCount;
        if (combo <= 0) return;

        long elapsed = now - ClientGameEvents.lastComboDisplayMs;
        if (elapsed > COMBO_FADE_MS) return;

        // حساب الشفافية (fade-out في آخر 400ms)
        float fadeStart = COMBO_FADE_MS - 400L;
        float alpha = elapsed < fadeStart
            ? 1.0f
            : 1.0f - (elapsed - fadeStart) / 400f;
        alpha = Math.max(0, Math.min(1, alpha));

        // تكبير النص مع Combo أعلى
        float scale = combo >= 4 ? 2.5f : (1.4f + combo * 0.25f);
        int col = COMBO_COLORS[Math.min(combo, COMBO_COLORS.length - 1)];
        String label = COMBO_LABELS[Math.min(combo, COMBO_LABELS.length - 1)];

        int baseA = (int)(alpha * 255);
        int textColor = (baseA << 24) | (col & 0x00FFFFFF);
        int shadowColor = (int)(alpha * 100) << 24;

        // رسم النص في وسط الشاشة (أسفل قليلاً من المنتصف لعدم إعاقة الرؤية)
        int cx = sw / 2;
        int cy = (int)(sh * 0.62f);

        // ظل خلف النص للوضوح
        int textW = (int)(mc.font.width(label) * scale);
        int textH = (int)(mc.font.lineHeight * scale);
        g.fill(cx - textW/2 - 6, cy - 3, cx + textW/2 + 6, cy + textH + 3, shadowColor);

        // النص المكبّر عبر PoseStack
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 200.0);
        pose.scale(scale, scale, 1.0f);
        g.drawCenteredString(mc.font,
            net.minecraft.network.chat.Component.literal(label),
            0, 0, textColor);
        pose.popPose();
    }

    // ─────────────────────────── Helper ─────────────────────────────────────
    private static int getIndex(String element) {
        for (int i = 0; i < IDS.length; i++)
            if (IDS[i].equals(element)) return i;
        return -1;
    }
}
