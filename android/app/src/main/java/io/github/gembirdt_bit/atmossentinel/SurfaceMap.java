package io.github.gembirdt_bit.atmossentinel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Mapa površine diska: svako polje je jedan deo diska. */
final class SurfaceMap extends View {

    static final byte NONE = 0, OK = 1, SLOW = 2, BAD = 3;

    private byte[] cells = new byte[0];
    private final Paint paint = new Paint();
    private final int cols = 40;

    SurfaceMap(Context c) { super(c); }

    void reset(int n) {
        cells = new byte[n];
        requestLayout();
        invalidate();
    }

    /** Postavlja stanje (zadržava najgore). */
    void set(int i, byte state) {
        if (i < 0 || i >= cells.length) return;
        if (state > cells[i]) cells[i] = state;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int rows = Math.max(1, (cells.length + cols - 1) / cols);
        float cell = w / (float) cols;
        setMeasuredDimension(w, (int) Math.ceil(rows * cell));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cell = getWidth() / (float) cols;
        float gap = Math.max(1f, cell * 0.12f);
        for (int i = 0; i < cells.length; i++) {
            int x = i % cols, y = i / cols;
            switch (cells[i]) {
                case OK: paint.setColor(0xFF34D399); break;
                case SLOW: paint.setColor(0xFFFBBF24); break;
                case BAD: paint.setColor(0xFFF87171); break;
                default: paint.setColor(0xFF2A3F37); break;
            }
            c.drawRect(x * cell, y * cell, (x + 1) * cell - gap, (y + 1) * cell - gap, paint);
        }
    }
}
