package com.tv.remotemouse.v2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * 遥控器示意图（Vidda CN3V75 布局）。
 *
 * 键内始终画原始图标，不随模式变化；只有键下方的注释会在
 * 电视遥控模式（原始键名）与鼠标模式（鼠标动作）之间切换。
 * 鼠标模式下已映射的键会高亮。
 */
public class RemoteView extends View {

    private static final int C_BODY = 0xFF1B2530;
    private static final int C_BODY_STROKE = 0xFF2F3F4E;
    private static final int C_KEY = 0xFF243040;
    private static final int C_KEY_STROKE = 0xFF35485A;
    private static final int C_TEXT = 0xFFB9C7D6;
    private static final int C_TEXT_DIM = 0xFF7E90A3;
    private static final int C_ACTIVE = 0xFF7FE3A1;
    private static final int C_ACTIVE_BG = 0xFF1C3A2C;

    private static final int I_POWER = 0, I_TV = 1, I_SOURCE = 2, I_PLAY = 3,
            I_GEAR = 4, I_BACK = 5, I_HOME = 6, I_MENU = 7;

    private boolean mouseMode = false;

    private final Map<Integer, String> mouseLabels = new HashMap<>();

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();

    /** 名称资源、相对 x/y、键码（-1 表示未知）、图标 */
    private final Object[][] keys = {
            {R.string.key_power, 0.20f, 0.100f, 116, I_POWER},
            {R.string.key_live, 0.80f, 0.100f, 660, I_TV},
            {R.string.key_source, 0.20f, 0.215f, -1, I_SOURCE},
            {R.string.key_app, 0.50f, 0.215f, 687, I_PLAY},
            {R.string.key_settings, 0.80f, 0.215f, 467, I_GEAR},
            {R.string.key_back, 0.22f, 0.695f, 158, I_BACK},
            {R.string.key_home, 0.50f, 0.695f, 102, I_HOME},
            {R.string.key_menu, 0.78f, 0.695f, 139, I_MENU},
    };

    private final RectF body = new RectF();

    public RemoteView(Context c) { super(c); init(); }
    public RemoteView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
    }

    public void setState(boolean mouseMode, Map<Integer, String> labels) {
        this.mouseMode = mouseMode;
        mouseLabels.clear();
        if (labels != null) mouseLabels.putAll(labels);

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float bh = Math.min(h * 0.98f, w * 2.15f);
        float bw = bh / 2.15f;
        float left = (w - bw) / 2f, top = (h - bh) / 2f;
        body.set(left, top, left + bw, top + bh);

        float radius = bw * 0.075f;
        fill.setColor(C_BODY);
        canvas.drawRoundRect(body, radius, radius, fill);
        stroke.setColor(C_BODY_STROKE);
        stroke.setStrokeWidth(Math.max(1f, bw * 0.006f));
        canvas.drawRoundRect(body, radius, radius, stroke);

        float kr = bw * 0.070f;
        float labelSize = bw * 0.058f;

        for (Object[] k : keys) {
            String name = getContext().getString((Integer) k[0]);
            float cx = body.left + bw * (Float) k[1];
            float cy = body.top + bh * (Float) k[2];
            int code = (Integer) k[3];
            int icon = (Integer) k[4];
            String mouse = mouseMode && code > 0 ? mouseLabels.get(code) : null;
            boolean active = mouse != null;

            fill.setColor(active ? C_ACTIVE_BG : C_KEY);
            canvas.drawCircle(cx, cy, kr, fill);
            stroke.setColor(active ? C_ACTIVE : C_KEY_STROKE);
            stroke.setStrokeWidth(Math.max(1f, bw * 0.006f));
            canvas.drawCircle(cx, cy, kr, stroke);

            drawIcon(canvas, icon, cx, cy, kr * 0.60f, active ? C_ACTIVE : C_TEXT);

            text.setTextSize(labelSize);
            text.setColor(active ? C_ACTIVE : C_TEXT);
            canvas.drawText(active ? mouse : name, cx, cy + kr + labelSize * 1.15f, text);
        }

        // 方向键 + OK
        float dcx = body.left + bw * 0.5f;
        float dcy = body.top + bh * 0.445f;
        float dr = bw * 0.30f;
        fill.setColor(C_KEY);
        canvas.drawCircle(dcx, dcy, dr, fill);
        stroke.setColor(C_KEY_STROKE);
        stroke.setStrokeWidth(Math.max(1f, bw * 0.006f));
        canvas.drawCircle(dcx, dcy, dr, stroke);

        boolean okActive = mouseMode && mouseLabels.containsKey(28);
        fill.setColor(okActive ? C_ACTIVE_BG : C_KEY);
        canvas.drawCircle(dcx, dcy, dr * 0.42f, fill);
        stroke.setColor(okActive ? C_ACTIVE : C_KEY_STROKE);
        canvas.drawCircle(dcx, dcy, dr * 0.42f, stroke);
        text.setColor(okActive ? C_ACTIVE : C_TEXT);
        text.setTextSize(labelSize);
        canvas.drawText("OK", dcx, dcy + labelSize * 0.35f, text);

        text.setColor(mouseMode ? C_ACTIVE : C_TEXT_DIM);
        float ar = dr * 0.72f;
        canvas.drawText("▲", dcx, dcy - ar + labelSize * 0.4f, text);
        canvas.drawText("▼", dcx, dcy + ar + labelSize * 0.4f, text);
        canvas.drawText("◀", dcx - ar, dcy + labelSize * 0.35f, text);
        canvas.drawText("▶", dcx + ar, dcy + labelSize * 0.35f, text);

        text.setTextSize(labelSize);
        text.setColor(mouseMode ? C_ACTIVE : C_TEXT);
        canvas.drawText(getContext().getString(mouseMode ? R.string.mouse_move : R.string.key_dpad),
                dcx, dcy + dr + labelSize * 1.15f, text);

        // 音量条
        float vw = bw * 0.56f, vh = bh * 0.055f;
        float vl = body.left + bw * 0.5f - vw / 2f;
        float vt = body.top + bh * 0.828f;
        tmp.set(vl, vt, vl + vw, vt + vh);
        fill.setColor(C_KEY);
        canvas.drawRoundRect(tmp, vh / 2f, vh / 2f, fill);
        stroke.setColor(C_KEY_STROKE);
        canvas.drawRoundRect(tmp, vh / 2f, vh / 2f, stroke);
        text.setColor(C_TEXT_DIM);
        canvas.drawText("−", vl + vw * 0.16f, vt + vh * 0.72f, text);
        canvas.drawText("+", vl + vw * 0.84f, vt + vh * 0.72f, text);
        text.setColor(C_TEXT);
        canvas.drawText(getContext().getString(R.string.key_volume), vl + vw * 0.5f, vt + vh * 0.72f, text);

        // 底部型号字（不印品牌 logo，避免商标问题），顺带填补留白
        text.setTextSize(bw * 0.072f);
        text.setColor(0xFF44586B);
        canvas.drawText("CN3V75", body.left + bw * 0.5f, body.top + bh * 0.935f, text);
        text.setTextSize(labelSize);
    }

    /** 画按键图标（描边风格，尺寸随 r 自适应） */
    private void drawIcon(Canvas c, int kind, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1.4f, r * 0.17f));
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);

        switch (kind) {
            case I_POWER:
                tmp.set(cx - r * 0.68f, cy - r * 0.68f, cx + r * 0.68f, cy + r * 0.68f);
                c.drawArc(tmp, -55, 290, false, stroke);
                c.drawLine(cx, cy - r * 0.95f, cx, cy - r * 0.15f, stroke);
                break;

            case I_TV:
                tmp.set(cx - r * 0.75f, cy - r * 0.6f, cx + r * 0.75f, cy + r * 0.35f);
                c.drawRoundRect(tmp, r * 0.14f, r * 0.14f, stroke);
                c.drawLine(cx, cy + r * 0.35f, cx, cy + r * 0.62f, stroke);
                c.drawLine(cx - r * 0.38f, cy + r * 0.62f, cx + r * 0.38f, cy + r * 0.62f, stroke);
                break;

            case I_SOURCE:
                tmp.set(cx - r * 0.8f, cy - r * 0.62f, cx + r * 0.8f, cy + r * 0.45f);
                c.drawRoundRect(tmp, r * 0.14f, r * 0.14f, stroke);
                c.drawLine(cx - r * 0.45f, cy - r * 0.08f, cx + r * 0.15f, cy - r * 0.08f, stroke);
                path.reset();
                path.moveTo(cx + r * 0.02f, cy - r * 0.34f);
                path.lineTo(cx + r * 0.36f, cy - r * 0.08f);
                path.lineTo(cx + r * 0.02f, cy + r * 0.18f);
                c.drawPath(path, stroke);
                break;

            case I_PLAY:
                path.reset();
                path.moveTo(cx - r * 0.45f, cy - r * 0.62f);
                path.lineTo(cx + r * 0.62f, cy);
                path.lineTo(cx - r * 0.45f, cy + r * 0.62f);
                path.close();
                c.drawPath(path, fill);
                break;

            case I_GEAR:
                c.drawCircle(cx, cy, r * 0.34f, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                    c.drawLine(cx + cos * r * 0.52f, cy + sin * r * 0.52f,
                            cx + cos * r * 0.85f, cy + sin * r * 0.85f, stroke);
                }
                break;

            case I_BACK:
                tmp.set(cx - r * 0.72f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f);
                c.drawArc(tmp, 200, 200, false, stroke);
                path.reset();
                path.moveTo(cx - r * 0.88f, cy - r * 0.10f);
                path.lineTo(cx - r * 0.50f, cy - r * 0.50f);
                path.lineTo(cx - r * 0.50f, cy + r * 0.24f);
                path.close();
                c.drawPath(path, fill);
                break;

            case I_HOME:
                path.reset();
                path.moveTo(cx - r * 0.82f, cy - r * 0.02f);
                path.lineTo(cx, cy - r * 0.78f);
                path.lineTo(cx + r * 0.82f, cy - r * 0.02f);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(cx - r * 0.60f, cy - r * 0.16f);
                path.lineTo(cx - r * 0.60f, cy + r * 0.70f);
                path.lineTo(cx + r * 0.60f, cy + r * 0.70f);
                path.lineTo(cx + r * 0.60f, cy - r * 0.16f);
                c.drawPath(path, stroke);
                break;

            case I_MENU:
                for (int i = -1; i <= 1; i++) {
                    c.drawLine(cx - r * 0.72f, cy + i * r * 0.42f,
                            cx + r * 0.72f, cy + i * r * 0.42f, stroke);
                }
                break;
        }

        stroke.setStyle(Paint.Style.STROKE);
        fill.setStyle(Paint.Style.FILL);
    }
}
