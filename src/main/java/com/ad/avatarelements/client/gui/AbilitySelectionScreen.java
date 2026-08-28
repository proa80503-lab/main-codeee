package com.ad.avatarelements.client.gui;

import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import com.ad.avatarelements.network.SetActiveAbilityPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * شاشة اختيار المهارة النشطة — تُفتح بالضغط المطول على Shift.
 *
 * الميزات:
 * - تجميد الزمن (isPauseScreen = true في Single Player)
 * - تصميم سينمائي مع تأثير ماسك + scanlines
 * - 4 بطاقات مهارات تُختار بالأرقام 1-2-3-4
 * - كل عنصر له اسم + وصف + تكلفة طاقة خاصة به
 */
public class AbilitySelectionScreen extends Screen {

    // ===== تعريف المهارات لكل عنصر =====
    private static final String[][] ABILITY_NAMES = {
        // fire
        { "💥 الانفجار الناري",  "🌋 جدار النار",       "🛡 درع اللهب",       "⚡ اندفاع الصاعقة"  },
        // water
        { "🌊 تسونامي",          "❄ قفص الجليد",        "💧 الشفاء المائي",    "🛡 درع الأمواج"      },
        // earth
        { "☄ موجة الزلزال",     "🏔 اقتلاع الأرض",     "🧱 الجدار الصخري",   "🪨 رمي الصخرة"       },
        // air
        { "🌪 دوامة الريح",      "🕊 الطيران الحر",      "💨 اندفاع البرق",    "🌀 العاصفة الكبرى"   },
        // light
        { "☀ شعاع النور",        "✨ القصف النوراني",   "🛡 درع النور",       "⚡ الصاعقة المقدسة"  },
        // dark
        { "🌑 نوفا الظلام",      "👻 سرقة الأرواح",     "🌫 خطوة الظل",       "😱 رعب الظلام"       }
    };

    private static final String[][] ABILITY_DESCS = {
        { "انفجار ناري مشحون ضخم يدمر كل ما حوله",
          "جدار نار دائري يحرق المتسللين",
          "درع لهب يحيط بك ويحرق المهاجمين",
          "اندفاع ناري سريع للأمام يشعل طريقه" },
        { "موجة مائية عملاقة تطيح بالأعداء",
          "يرفع الهدف ويجمده في قفص جليدي",
          "شفاء فوري بقوة الماء يجدد الحياة",
          "أوربات مائية تحيط بك وتصرف الطلقات" },
        { "صدمة أرضية تدمر وتطيح بالأعداء",
          "يرفع قطعة أرض كاملة للأعلى",
          "يبني جداراً صخرياً واقياً في لحظة",
          "يقذف صخرة ضخمة باتجاه الأعداء" },
        { "دوامة رياح هائلة تطيح بالجميع",
          "يمنحك القدرة على الطيران الحر",
          "اندفاع برقي فوري لمسافة 15 بلوكة",
          "عاصفة هوجاء تبعد الأعداء بعيداً" },
        { "شعاع نور يخترق كل شيء أمامه",
          "قصف نوراني متتالي من السماء",
          "درع يمتص الضربة القادمة ويعكسها",
          "صاعقة مقدسة فورية على أقرب عدو" },
        { "موجة ظلام تستنزف أرواح الأعداء",
          "يسرق الأرواح لإعادة الصحة إليك",
          "تختفي فوراً وتقفز عبر الظلام",
          "يملأ الأعداء القريبين برعب شديد" }
    };

    private static final int[] STAMINA_COSTS = { 20, 30, 25, 40 };

    private static final String[] ELEMENT_IDS = { "fire","water","earth","air","light","dark" };
    private static final String[] ELEMENT_NAMES_AR = { "النار","الماء","الأرض","الهواء","النور","الظلام" };
    private static final int[] ELEMENT_COLORS = {
        0xFFFF4500, 0xFF00AAFF, 0xFFAA8844,
        0xFF88CCEE, 0xFFFFDD00, 0xFF9900CC
    };
    private static final int[] ELEMENT_DARK = {
        0xFF3A0A00, 0xFF001A3A, 0xFF1A1000,
        0xFF0A1420, 0xFF2A2200, 0xFF1A0030
    };

    // ===== حالة الشاشة =====
    private long openTime;
    private int currentElement = -1;
    private int currentActive  = 1;

    public AbilitySelectionScreen() {
        super(Component.literal("ability_selection"));
    }

    @Override
    protected void init() {
        super.init();
        openTime = System.currentTimeMillis();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            try {
                PlayerElementData d = mc.player.getData(ModAttachmentTypes.ELEMENT_DATA);
                currentElement = getElementIndex(d.currentElement());
                currentActive  = d.activeAbility();
            } catch (Exception ignored) {}
        }
    }

    // ───────────────────────────── تجميد الزمن ──────────────────────────────
    @Override
    public boolean isPauseScreen() {
        return true; // يُجمِّد اللعبة في Single Player!
    }

    // ───────────────────────────── Keyboard ──────────────────────────────────
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // GLFW key codes for 1-4: 49, 50, 51, 52
        int slot = -1;
        if (keyCode == 49) slot = 1;
        else if (keyCode == 50) slot = 2;
        else if (keyCode == 51) slot = 3;
        else if (keyCode == 52) slot = 4;

        if (slot != -1 && currentElement >= 0) {
            selectAbility(slot);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void selectAbility(int slot) {
        PacketDistributor.sendToServer(new SetActiveAbilityPayload(slot));
        this.onClose();
    }

    // ─────────────────────────── Render ─────────────────────────────────────
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // خلفية سوداء شفافة
        g.fill(0, 0, width, height, 0xD8050510);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);

        long elapsed = System.currentTimeMillis() - openTime;
        int cx = width / 2;
        int cy = height / 2;

        renderScanlines(g, elapsed);
        renderVignette(g);
        renderTitle(g, cx, elapsed);
        renderAbilityCards(g, cx, cy, elapsed, mx, my);
        renderFooterHint(g, cx, cy);

        // لا نستدعي super.render() لأنه لا يوجد أزرار مسجلة
    }

    /** تأثير خطوط المسح (scanlines) السينمائي */
    private void renderScanlines(GuiGraphics g, long elapsed) {
        for (int y = 0; y < height; y += 4) {
            int alpha = 18 + (int)(Math.sin(y * 0.08 + elapsed * 0.003) * 6);
            g.fill(0, y, width, y + 1, (alpha << 24));
        }
    }

    /** ظلال الحواف */
    private void renderVignette(GuiGraphics g) {
        int vigW = width / 3;
        int vigH = height / 3;
        // يسار
        for (int i = 0; i < vigW; i++) {
            int alpha = (int)(80 * (1.0 - (double)i / vigW));
            g.fill(i, 0, i + 1, height, (alpha << 24));
        }
        // يمين
        for (int i = 0; i < vigW; i++) {
            int alpha = (int)(80 * (1.0 - (double)i / vigW));
            g.fill(width - i - 1, 0, width - i, height, (alpha << 24));
        }
        // أعلى
        for (int i = 0; i < vigH; i++) {
            int alpha = (int)(60 * (1.0 - (double)i / vigH));
            g.fill(0, i, width, i + 1, (alpha << 24));
        }
        // أسفل
        for (int i = 0; i < vigH; i++) {
            int alpha = (int)(60 * (1.0 - (double)i / vigH));
            g.fill(0, height - i - 1, width, height - i, (alpha << 24));
        }
    }

    /** عنوان الشاشة مع اسم العنصر */
    private void renderTitle(GuiGraphics g, int cx, long elapsed) {
        float pulse = (float)(Math.sin(elapsed * 0.005) * 0.5 + 0.5);
        int titleColor = 0xFFFFFFFF;
        String elementName = "غير محدد";

        if (currentElement >= 0) {
            titleColor = ELEMENT_COLORS[currentElement];
            elementName = ELEMENT_NAMES_AR[currentElement];
        }

        int titleAlpha = (int)(180 + pulse * 75);
        int glowColor = (titleAlpha << 24) | (titleColor & 0x00FFFFFF);

        // خط أفقي علوي
        int lineY = height / 2 - 125;
        if (currentElement >= 0) {
            g.fill(cx - 200, lineY, cx + 200, lineY + 1, 0x55000000 | (titleColor & 0x00FFFFFF));
            g.fill(cx - 150, lineY + 2, cx + 150, lineY + 3, 0x33000000 | (titleColor & 0x00FFFFFF));
        }

        // النص الرئيسي
        String mainTitle = "✦ اختر مهارتك النشطة ✦";
        g.drawCenteredString(font, Component.literal(mainTitle), cx, lineY - 16, glowColor);

        // اسم العنصر
        String subTitle = "[ " + elementName + " ]";
        int subColor = currentElement >= 0
            ? (0xCC000000 | (ELEMENT_COLORS[currentElement] & 0x00FFFFFF))
            : 0xCC888888;
        g.drawCenteredString(font, Component.literal(subTitle), cx, lineY - 4, subColor);
    }

    /** بطاقات المهارات الأربع في شبكة 2×2 */
    private void renderAbilityCards(GuiGraphics g, int cx, int cy, long elapsed, int mx, int my) {
        if (currentElement < 0) {
            g.drawCenteredString(font, Component.literal("§c لم تختر عنصراً بعد! اضغط K أولاً"), cx, cy, 0xFFFF4444);
            return;
        }

        int darkBg = ELEMENT_DARK[currentElement];

        // كل البطاقات الأربع
        // الترتيب: 2 في الصف الأول، 2 في الثاني
        int cardW = 180;
        int cardH = 75;
        int gapX  = 18;
        int gapY  = 14;

        int startX = cx - cardW - gapX / 2;
        int startY = cy - cardH - gapY / 2;

        String[] names = ABILITY_NAMES[currentElement];
        String[] descs = ABILITY_DESCS[currentElement];
        int elColor    = ELEMENT_COLORS[currentElement];

        for (int i = 0; i < 4; i++) {
            int col = i % 2;
            int row = i / 2;
            int bx  = startX + col * (cardW + gapX);
            int by  = startY + row * (cardH + gapY);

            boolean isActive  = (currentActive == i + 1);
            boolean isHovered = (mx >= bx && mx < bx + cardW && my >= by && my < by + cardH);

            renderCard(g, bx, by, cardW, cardH, i + 1, names[i], descs[i],
                       STAMINA_COSTS[i], elColor, darkBg, isActive, isHovered, elapsed);
        }
    }

    /** رندر بطاقة مهارة واحدة */
    private void renderCard(GuiGraphics g, int bx, int by, int w, int h,
                             int slot, String name, String desc, int cost,
                             int elColor, int darkBg, boolean isActive, boolean isHovered, long elapsed) {

        float pulse = (float)(Math.sin(elapsed * 0.007 + slot) * 0.5 + 0.5);

        // ── توهج خارجي عند التحديد ──
        if (isActive) {
            int ga = (int)(40 + pulse * 60);
            for (int s = 5; s >= 2; s--) {
                g.fill(bx - s, by - s, bx + w + s, by + h + s,
                       ((ga / s) << 24) | (elColor & 0x00FFFFFF));
            }
        } else if (isHovered) {
            g.fill(bx - 2, by - 2, bx + w + 2, by + h + 2,
                   0x22000000 | (elColor & 0x00FFFFFF));
        }

        // ── الخلفية ──
        int bgColor = isActive
            ? (0xCC000000 | ((darkBg + 0x101010) & 0x00FFFFFF))
            : 0xBB050810;
        g.fill(bx + 1, by + 1, bx + w - 1, by + h - 1, bgColor);

        // ── الإطار ──
        int frameColor = isActive ? elColor : (isHovered ? (0xAA000000 | (elColor & 0x00FFFFFF)) : 0xFF1A2030);
        g.fill(bx,         by,         bx + w,     by + 1,     frameColor);
        g.fill(bx,         by + h - 1, bx + w,     by + h,     frameColor);
        g.fill(bx,         by,         bx + 1,     by + h,     frameColor);
        g.fill(bx + w - 1, by,         bx + w,     by + h,     frameColor);

        // ── شريط علوي ملون ──
        int barAlpha = isActive ? (int)(120 + pulse * 80) : 60;
        g.fill(bx + 1, by + 1, bx + w - 1, by + 4, (barAlpha << 24) | (elColor & 0x00FFFFFF));

        // ── رقم الفتحة (كبير على اليسار) ──
        int numColor = isActive
            ? elColor
            : (isHovered ? (0xCC000000 | (elColor & 0x00FFFFFF)) : 0xFF334455);
        g.drawString(font, "[" + slot + "]", bx + 6, by + 8, numColor, false);

        // ── اسم المهارة ──
        int nameColor = isActive
            ? (0xFF000000 | (elColor & 0x00FFFFFF))
            : (isHovered ? (0xDD000000 | (elColor & 0x00FFFFFF)) : 0xFF667788);
        g.drawString(font, name, bx + 26, by + 8, nameColor, false);

        // ── فاصل ──
        g.fill(bx + 5, by + 22, bx + w - 5, by + 23,
               isActive ? (0x66000000 | (elColor & 0x00FFFFFF)) : 0x33445566);

        // ── الوصف ──
        int descColor = isActive ? 0xFFAABBCC : 0xFF556677;
        // تقطيع النص إذا كان طويلاً
        String displayDesc = desc.length() > 34 ? desc.substring(0, 31) + "..." : desc;
        g.drawString(font, displayDesc, bx + 6, by + 28, descColor, false);

        // ── تكلفة الطاقة ──
        String costStr = "⚡ " + cost + " طاقة";
        int costColor = isActive
            ? (0xEE000000 | (elColor & 0x00FFFFFF))
            : 0xFF334455;
        g.drawString(font, costStr, bx + 6, by + h - 16, costColor, false);

        // ── مؤشر "نشط" ──
        if (isActive) {
            int activeAlpha = (int)(180 + pulse * 75);
            g.drawString(font, "✔ نشطة",
                bx + w - font.width("✔ نشطة") - 6, by + h - 16,
                (activeAlpha << 24) | (elColor & 0x00FFFFFF), false);
        }
    }

    /** تلميح الأزرار في الأسفل */
    private void renderFooterHint(GuiGraphics g, int cx, int cy) {
        int footY = cy + 80 + 14;
        g.drawCenteredString(font,
            Component.literal("§7اضغط §f[1]  [2]  [3]  [4]§7 لاختيار المهارة  •  §7[ESC] §fإلغاء"),
            cx, footY, 0xFF334455);
    }

    // ─────────────────────────── Helpers ─────────────────────────────────────
    private static int getElementIndex(String el) {
        for (int i = 0; i < ELEMENT_IDS.length; i++)
            if (ELEMENT_IDS[i].equals(el)) return i;
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // لا نريد نقر الفأرة يكون فعالاً — الاختيار بالأرقام فقط
        return false;
    }
}
