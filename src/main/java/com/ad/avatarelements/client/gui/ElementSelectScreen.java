package com.ad.avatarelements.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;
import com.ad.avatarelements.network.SelectElementPayload;
import com.ad.avatarelements.attachment.ModAttachmentTypes;
import com.ad.avatarelements.attachment.PlayerElementData;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.client.Minecraft;

/**
 * شاشة اختيار العنصر - تصميم دائري احترافي مع رسوم متحركة
 */
public class ElementSelectScreen extends Screen {

    // ==================== بيانات العناصر الستة ====================
    private static final String[] ELEMENT_IDS = {"fire","water","earth","air","light","dark"};
    private static final String[] NAMES_AR    = {"النار","الماء","الأرض","الهواء","النور","الظلام"};
    private static final String[] NAMES_EN    = {"Fire","Water","Earth","Air","Light","Dark"};
    private static final String[] DESCS       = {
        "جوهر الدفء والتدمير",
        "قوة الحياة والتجديد",
        "صلابة الجبال والحكمة",
        "حرية الريح والسرعة",
        "نقاء الضوء والحماية",
        "غموض الظلام والسلطة"
    };
    // اللون الأساسي لكل عنصر
    private static final int[] COL_PRIMARY = {
        0xFFFF6200, 0xFF0088FF, 0xFF8B6914,
        0xFF88AACC, 0xFFFFDD00, 0xFF9900CC
    };
    // لون النص لكل عنصر
    private static final int[] COL_TEXT = {
        0xFFFFAA44, 0xFF66CCFF, 0xFFD4A843,
        0xFFCCEEFF, 0xFFFFFF88, 0xFFCC88FF
    };

    // ==================== تخطيط دائري ====================
    // زوايا توزيع الأزرار الستة حول الكرة (بالدرجات)
    private static final float[] ANGLES = {270f, 90f, 180f, 0f, 315f, 225f};
    private static final int ORBIT_R = 95; // نصف قطر المدار بالبكسل
    private static final int BTN_W   = 90;
    private static final int BTN_H   = 52;

    // ==================== توقيت الرسوم المتحركة ====================
    private long openTime;

    // ============================================================
    public ElementSelectScreen() {
        super(Component.translatable("gui.avatarelements.select_element"));
    }

    @Override
    protected void init() {
        super.init();
        openTime = System.currentTimeMillis();

        int cx = this.width  / 2;
        int cy = this.height / 2;

        for (int i = 0; i < ELEMENT_IDS.length; i++) {
            final int idx = i;
            double rad = Math.toRadians(ANGLES[i]);
            int bx = cx + (int)(ORBIT_R * Math.cos(rad)) - BTN_W / 2;
            int by = cy + (int)(ORBIT_R * Math.sin(rad)) - BTN_H / 2;

            this.addRenderableWidget(
                new ElementButton(bx, by, BTN_W, BTN_H, idx,
                    btn -> doSelectElement(ELEMENT_IDS[idx]))
            );
        }
    }

    private void doSelectElement(String element) {
        PacketDistributor.sendToServer(new SelectElementPayload(element));
        this.onClose();
    }

    // ==================== الحالة الحالية للاعب ====================
    private boolean isAlreadyChosen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        try {
            PlayerElementData d = mc.player.getData(ModAttachmentTypes.ELEMENT_DATA);
            return d != null && !d.currentElement().equals("none");
        } catch (Exception e) {
            return false;
        }
    }

    private String getChosenElement() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "none";
        try {
            return mc.player.getData(ModAttachmentTypes.ELEMENT_DATA).currentElement();
        } catch (Exception e) {
            return "none";
        }
    }

    private int getPrimaryColor(String el) {
        for (int i = 0; i < ELEMENT_IDS.length; i++)
            if (ELEMENT_IDS[i].equals(el)) return COL_PRIMARY[i];
        return 0xFFFFFFFF;
    }

    private String getNameAr(String el) {
        for (int i = 0; i < ELEMENT_IDS.length; i++)
            if (ELEMENT_IDS[i].equals(el)) return NAMES_AR[i];
        return el;
    }

    // ==================== Render ====================
    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, this.width, this.height, 0xEA060C18);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);

        long elapsed = System.currentTimeMillis() - openTime;
        int cx = this.width  / 2;
        int cy = this.height / 2;

        renderDecorLines(g, cx, cy, elapsed);
        renderCentralOrb(g, cx, cy, elapsed);
        renderTitles(g, cx, cy, elapsed);
        renderStatusText(g, cx, cy);

        // رندر الأزرار (يستدعيها super)
        super.render(g, mx, my, pt);
    }

    /** خطوط نجمية دوارة في الخلفية */
    private void renderDecorLines(GuiGraphics g, int cx, int cy, long elapsed) {
        float rot = (float)(elapsed * 0.012);
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(rot + i * 30.0);
            int x1 = cx + (int)(35  * Math.cos(angle));
            int y1 = cy + (int)(35  * Math.sin(angle));
            int x2 = cx + (int)(165 * Math.cos(angle));
            int y2 = cy + (int)(165 * Math.sin(angle));
            int alpha = 8 + (i % 4) * 4;
            drawLine(g, x1, y1, x2, y2, (alpha << 24) | 0x004466AA);
        }
    }

    /** رسم خط رفيع بين نقطتين */
    private void drawLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1);
        int steps = Math.max(dx, dy);
        if (steps == 0) return;
        float sx = (float)(x2 - x1) / steps;
        float sy = (float)(y2 - y1) / steps;
        for (int s = 0; s <= steps; s++) {
            int px = x1 + Math.round(sx * s);
            int py = y1 + Math.round(sy * s);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    /** الكرة المركزية المتوهجة */
    private void renderCentralOrb(GuiGraphics g, int cx, int cy, long elapsed) {
        float pulse = (float)(Math.sin(elapsed * 0.004) * 0.5 + 0.5);

        renderFilledCircle(g, cx, cy, 40, ((int)(25 + pulse * 35) << 24) | 0x000088FF);
        renderFilledCircle(g, cx, cy, 30, 0x440088FF);
        renderFilledCircle(g, cx, cy, 22, 0x770099EE);
        renderFilledCircle(g, cx, cy, 14, 0xAA00BBFF);
        renderFilledCircle(g, cx, cy, 8,  0xCC00EEFF);
        renderFilledCircle(g, cx, cy, 4,  0xFFFFFFFF);

        // جسيمات ساطعة دوارة
        for (int i = 0; i < 6; i++) {
            double angle = Math.toRadians(elapsed * 0.18 + i * 60.0);
            int px = cx + (int)(28 * Math.cos(angle));
            int py = cy + (int)(28 * Math.sin(angle));
            int alpha = (int)(100 + pulse * 120);
            renderFilledCircle(g, px, py, 2, (alpha << 24) | 0x0055DDFF);
        }
    }

    /** دائرة مملوءة */
    private void renderFilledCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int halfW = (int) Math.sqrt((double)(r * r - dy * dy));
            if (halfW > 0)
                g.fill(cx - halfW, cy + dy, cx + halfW + 1, cy + dy + 1, color);
        }
    }

    /** العناوين في الأعلى */
    private void renderTitles(GuiGraphics g, int cx, int cy, long elapsed) {
        float titlePulse = (float)(Math.sin(elapsed * 0.003) * 0.5 + 0.5);
        int titleAlpha   = (int)(200 + titlePulse * 55);
        int titleColor   = (titleAlpha << 24) | 0x00D4A843;

        g.drawCenteredString(this.font,
            Component.literal("✦  اختر قدرتك  ✦"),
            cx, cy - 150, titleColor);
        g.drawCenteredString(this.font,
            Component.literal("Choose Your Element"),
            cx, cy - 136, 0xFF2E4A68);
    }

    /** نص الحالة في الأسفل */
    private void renderStatusText(GuiGraphics g, int cx, int cy) {
        if (isAlreadyChosen()) {
            String chosen   = getChosenElement();
            int col         = getPrimaryColor(chosen);
            String nameAr   = getNameAr(chosen);
            g.drawCenteredString(this.font,
                Component.literal("عنصرك الحالي: " + nameAr),
                cx, cy + 142, col);
            g.drawCenteredString(this.font,
                Component.literal("[ اضغط ESC للخروج ]"),
                cx, cy + 156, 0xFF223344);
        } else {
            g.drawCenteredString(this.font,
                Component.literal("[ انقر على عنصر لاختياره ]"),
                cx, cy + 156, 0xFF1E3348);
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // =============================== زر العنصر ==================================
    private class ElementButton extends Button {
        private final int idx;

        ElementButton(int x, int y, int w, int h, int idx, OnPress press) {
            super(x, y, w, h, Component.empty(), press, DEFAULT_NARRATION);
            this.idx = idx;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            boolean hovered = this.isHoveredOrFocused();
            boolean locked  = isAlreadyChosen();
            boolean isMe    = ELEMENT_IDS[idx].equals(getChosenElement());

            long elapsed = System.currentTimeMillis() - openTime;
            float pulse  = (float)(Math.sin(elapsed * 0.007 + idx) * 0.5 + 0.5);

            int primary = COL_PRIMARY[idx];
            int bx = this.getX(), by = this.getY();
            int bw = this.getWidth(), bh = this.getHeight();

            // ─── هالة خارجية ───
            if (isMe) {
                // توهج نابض على العنصر المختار
                int gAlpha = (int)(50 + pulse * 80);
                for (int s = 4; s >= 2; s--)
                    g.fill(bx - s, by - s, bx + bw + s, by + bh + s,
                           ((gAlpha / s) << 24) | (primary & 0x00FFFFFF));
            } else if (hovered && !locked) {
                g.fill(bx - 3, by - 3, bx + bw + 3, by + bh + 3,
                       0x33000000 | (primary & 0x00FFFFFF));
                g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1,
                       0x55000000 | (primary & 0x00FFFFFF));
            }

            // ─── الإطار ───
            int frameCol = isMe ? primary : (hovered && !locked ? primary : 0xFF162030);
            g.fill(bx,          by,          bx + bw,     by + 1,      frameCol);
            g.fill(bx,          by + bh - 1, bx + bw,     by + bh,     frameCol);
            g.fill(bx,          by,          bx + 1,      by + bh,     frameCol);
            g.fill(bx + bw - 1, by,          bx + bw,     by + bh,     frameCol);

            // ─── الخلفية ───
            int bgColor;
            if (isMe)            bgColor = 0xAA000000 | (primary & 0x00FFFFFF);
            else if (hovered && !locked) bgColor = 0x55000000 | (primary & 0x00FFFFFF);
            else                 bgColor = 0x880A1222;
            g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, bgColor);

            // ─── نصوص داخل الزر ───
            int nameColor = isMe ? COL_TEXT[idx] : (hovered && !locked ? COL_TEXT[idx] : 0xFF445566);
            int engColor  = isMe ? 0xFF445566 : 0xFF22323F;

            g.drawCenteredString(font, NAMES_AR[idx], bx + bw / 2, by + 7,  nameColor);
            g.drawCenteredString(font, NAMES_EN[idx], bx + bw / 2, by + 19, engColor);

            if (isMe) {
                int wa = (int)(170 + pulse * 85);
                g.drawCenteredString(font, "✔ اخترت هذا",
                    bx + bw / 2, by + 34,
                    (wa << 24) | (primary & 0x00FFFFFF));
            } else if (hovered && !locked) {
                int wa = (int)(150 + pulse * 105);
                g.drawCenteredString(font, "▶ اختر ◀",
                    bx + bw / 2, by + 34,
                    (wa << 24) | (COL_TEXT[idx] & 0x00FFFFFF));
            } else {
                g.drawCenteredString(font, DESCS[idx],
                    bx + bw / 2, by + 34, locked ? 0xFF162030 : 0xFF1E3040);
            }
        }

        /** لا تسمح بالنقر إذا كان اللاعب اختار مسبقاً */
        @Override
        public void onPress() {
            if (!isAlreadyChosen()) super.onPress();
        }
    }
}
