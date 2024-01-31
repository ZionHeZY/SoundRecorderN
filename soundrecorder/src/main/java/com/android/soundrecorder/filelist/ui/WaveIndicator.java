package com.android.soundrecorder.filelist.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class WaveIndicator extends View {
    private static final float SPACE_WEIGHT = 0.5f;
    private static final float STOP_HEIGHT = 1f / 12;
    private static final int FRAME_FREQ = 10;
    private static final int BARS_COUNT = 4;

    private static final int COLOR_DEFAULT_ACTIVATED = Color.RED;
    private static final int COLOR_DEFAULT_NORMAL = Color.GRAY;

    private float mBars[] = new float[BARS_COUNT];
    private float mBarsNext[] = new float[BARS_COUNT];

    private boolean mAnimate;
    private int mFrameIndex;

    private int mColorActivated = COLOR_DEFAULT_ACTIVATED;
    private int mColorNormal = COLOR_DEFAULT_NORMAL;

    private Paint mPaint;

    public WaveIndicator(Context context) {
        this(context, null);
    }

    public WaveIndicator(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public WaveIndicator(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public WaveIndicator(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        TypedArray array = context.getTheme().obtainStyledAttributes(new int[]{
                android.R.attr.colorControlActivated,
                android.R.attr.colorControlNormal,
        });
        mColorActivated = array.getColor(array.getIndex(0), COLOR_DEFAULT_ACTIVATED);
        mColorNormal = array.getColor(array.getIndex(1), COLOR_DEFAULT_NORMAL);
        array.recycle();

        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    }

    public void animate(boolean animate) {
        mAnimate = animate;
        mFrameIndex = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float height = getHeight();
        float width = getWidth();
        float weight = BARS_COUNT + (BARS_COUNT - 1) * SPACE_WEIGHT;
        float barWidth = width / weight;
        float spaceWidth = width / weight * SPACE_WEIGHT;

        if (mFrameIndex % FRAME_FREQ == 0) {
            for (int i = 0; i < mBarsNext.length; i++) {
                mBarsNext[i] = (float) Math.random();
            }
        }
        mFrameIndex++;

        for (int i = 0; i < mBars.length; i++) {
            float left = i * (barWidth + spaceWidth);
            float top = 0;
            if (mAnimate) {
                mPaint.setColor(mColorActivated);
                mBars[i] = mBars[i] + (mBarsNext[i] - mBars[i]) / FRAME_FREQ;
                top = mBars[i] * height;
            } else {
                mPaint.setColor(mColorNormal);
                top = height - STOP_HEIGHT * height;
            }
            canvas.drawRect(left, top, left + barWidth, height, mPaint);
        }

        if (mAnimate) invalidate();
    }
}
