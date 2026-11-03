package com.mojahed.hdrstudio.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Before/after split view. Both bitmaps are drawn into the SAME destination rectangle (same
 * display size, aspect preserved), regardless of their pixel sizes; drag to move the split.
 */
public final class CompareView extends View {
    private Bitmap before, after;
    private float split = 0.5f;
    private final Paint line = new Paint(), label = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.FILTER_BITMAP_FLAG);

    public CompareView(Context c) {
        super(c);
        line.setColor(Color.parseColor("#FFD60A"));
        line.setStrokeWidth(4);
        label.setColor(Color.WHITE);
        label.setTextSize(34);
        label.setShadowLayer(4, 0, 0, Color.BLACK);
    }

    public void set(Bitmap b, Bitmap a) { before = b; after = a; requestLayout(); invalidate(); }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws);
        int h = before == null ? 0 : (int) (w * (float) before.getHeight() / before.getWidth());
        setMeasuredDimension(w, h);
    }

    @Override protected void onDraw(Canvas c) {
        if (before == null || after == null) return;
        RectF dst = new RectF(0, 0, getWidth(), getHeight());
        int sx = (int) (getWidth() * split);
        c.save();
        c.clipRect(0, 0, sx, getHeight());
        c.drawBitmap(before, fit(before, dst), bmp);
        c.restore();
        c.save();
        c.clipRect(sx, 0, getWidth(), getHeight());
        c.drawBitmap(after, fit(after, dst), bmp);
        c.restore();
        c.drawLine(sx, 0, sx, getHeight(), line);
        c.drawText("قبل", 16, 44, label);
        c.drawText("بعد", getWidth() - 90, 44, label);
    }

    private static Matrix fit(Bitmap b, RectF dst) {
        Matrix m = new Matrix();
        m.setRectToRect(new RectF(0, 0, b.getWidth(), b.getHeight()), dst, Matrix.ScaleToFit.FILL);
        return m;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (getWidth() == 0) return false;
        split = Math.max(0f, Math.min(1f, e.getX() / getWidth()));
        getParent().requestDisallowInterceptTouchEvent(true);
        invalidate();
        return true;
    }
}
