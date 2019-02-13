package com.mixpanel.android.util;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.View;

public class ActivityImageUtils {

    // May return null.
    public static Bitmap getScaledScreenshot(final Activity activity,
                                             int scaleWidth,
                                             int scaleHeight,
                                             boolean relativeScaleIfTrue) {
        // 找到保存内容的控件 ...是DecorView 中的一个FrameLayout
        final View someView = activity.findViewById(android.R.id.content);
        // 获取到 指定View的 最终的父类, 这里就是 DecorView
        final View rootView = someView.getRootView();
        // 需要获取原始的状态, 在获取完截图之后重新设置回去
        final boolean originalCacheState = rootView.isDrawingCacheEnabled();
        // 只有开启了drawingcache  才能获取视图
        rootView.setDrawingCacheEnabled(true);
        rootView.buildDrawingCache(true);

        // We could get a null or zero px bitmap if the rootView hasn't been measured
        // appropriately, or we grab it before layout.
        // This is ok, and we should handle it gracefully.
        final Bitmap original = rootView.getDrawingCache();
        Bitmap scaled = null;
        if (null != original && original.getWidth() > 0 && original.getHeight() > 0) {
            if (relativeScaleIfTrue) {
                scaleWidth = original.getWidth() / scaleWidth;
                scaleHeight = original.getHeight() / scaleHeight;
            }
            if (scaleWidth > 0 && scaleHeight > 0) {
                try {
                    scaled = Bitmap.createScaledBitmap(original, scaleWidth, scaleHeight, false);
                } catch (OutOfMemoryError error) {
                    MPLog.i(LOGTAG, "Not enough memory to produce scaled image, returning a null screenshot");
                }
            }
        }

        // 恢复之前的状态
        if (!originalCacheState) {
            rootView.setDrawingCacheEnabled(false);
        }
        return scaled;
    }

    /**
     * 获取android.R.id.content 中第一个像素的颜色
     *
     * @param activity
     * @return
     */
    public static int getHighlightColorFromBackground(final Activity activity) {
        int incolor = Color.BLACK;
        // 获取1px 大小的 视图 bitmap
        final Bitmap screenshot1px = getScaledScreenshot(activity,
                1, 1, false);
        if (null != screenshot1px) {
            incolor = screenshot1px.getPixel(0, 0);
        }
        return getHighlightColor(incolor);
    }

    public static int getHighlightColorFromBitmap(final Bitmap bitmap) {
        int incolor = Color.BLACK;
        if (null != bitmap) {
            final Bitmap bitmap1px = Bitmap.createScaledBitmap(bitmap, 1, 1, false);
            incolor = bitmap1px.getPixel(0, 0);
        }
        return getHighlightColor(incolor);
    }

    public static int getHighlightColor(int sampleColor) {
        // Set a constant value level in HSV, in case the averaged color is too light or too dark.
        float[] hsvBackground = new float[3];
        Color.colorToHSV(sampleColor, hsvBackground);
        hsvBackground[2] = 0.3f; // value parameter

        return Color.HSVToColor(0xf2, hsvBackground);
    }

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.ActImgUtils";
}
