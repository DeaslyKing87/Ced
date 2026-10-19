package com.ced.sharplan;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** App-only wallpaper: no service, assets, network access or per-frame object allocation. */
public final class CyberBackground extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] stars = new float[96];
    private final long start = SystemClock.uptimeMillis();
    private boolean active;
    private int mode; // 0 animated, 1 static, 2 battery saver
    private int state; // 0 idle, 1 finding, 2 found, 3 error, 4 deep scan
    public CyberBackground(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false); setClickable(false);
        java.util.Random random = new java.util.Random(7352);
        for (int i = 0; i < stars.length; i++) stars[i] = random.nextFloat();
    }
    public void running(boolean running) { active = running; invalidate(); }
    public void mode(int value) { mode = value; invalidate(); }
    public void state(int value) { if (state != value) { state = value; invalidate(); } }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(5, 8, 15));
        float width = getWidth(), height = getHeight();
        float time = mode == 1 ? 0 : (SystemClock.uptimeMillis() - start) / 1000f;
        int accent = state == 3 ? 0xFFFF526E : state == 2 ? 0xFF53F4CF : 0xFF28CFE6;
        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density);
        paint.setColor(accent); paint.setAlpha(13);
        float horizon = height * .25f;
        for (int n = -9; n <= 9; n++)
            canvas.drawLine(width / 2 + n * width / 22, horizon,
                    width / 2 + n * width / 5, height, paint);
        for (int n = 0; n < 14; n++) {
            float p = ((n + (time * (state == 4 ? .35f : .08f)) % 1) / 14f);
            float y = horizon + p * p * (height - horizon);
            canvas.drawLine(0, y, width, y, paint);
        }
        // Minimal city silhouette, kept behind translucent cards.
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xFF102333); paint.setAlpha(60);
        for (int n = 0; n < 16; n++) {
            float x = width * n / 16f, roof = height * (.16f + stars[n] * .14f);
            canvas.drawRect(x, roof, x + width / 20, height * .37f, paint);
        }
        paint.setColor(accent); paint.setAlpha(40);
        for (int n = 0; n < 24; n++) {
            float x = stars[n * 3] * width;
            float y = ((stars[n * 3 + 1] + time * .006f) % 1) * height;
            canvas.drawCircle(x, y, density * (1 + stars[n * 3 + 2]), paint);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density);
        paint.setColor(0xFFAF55DF); paint.setAlpha(16);
        canvas.drawCircle(width * .92f, height * .12f, width * .24f, paint);
        canvas.drawCircle(width * .92f, height * .12f, width * .28f, paint);
        if (state == 1 || state == 4) {
            float y = ((time * (state == 4 ? .19f : .10f)) % 1) * height;
            paint.setColor(accent); paint.setAlpha(45); paint.setStrokeWidth(2 * density);
            canvas.drawLine(0, y, width, y, paint);
            paint.setAlpha(12); paint.setStrokeWidth(18 * density);
            canvas.drawLine(0, y, width, y, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        if (active && isShown() && mode != 1) postInvalidateDelayed(mode == 2 ? 100 : 42);
    }
}
