package com.mixpanel.android.viewcrawler;


import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.DisplayMetrics;
import android.util.JsonWriter;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.mixpanel.android.mpmetrics.MPConfig;
import com.mixpanel.android.mpmetrics.ResourceIds;
import com.mixpanel.android.util.MPLog;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@TargetApi(MPConfig.UI_FEATURES_MIN_API)
        /* package */ class ViewSnapshot {

    public ViewSnapshot(Context context, List<PropertyDescription> properties, ResourceIds resourceIds) {
        mConfig = MPConfig.getInstance(context);
        mProperties = properties;
        mResourceIds = resourceIds;
        mMainThreadHandler = new Handler(Looper.getMainLooper());
        mRootViewFinder = new RootViewFinder();
        mClassnameCache = new ClassNameCache(MAX_CLASS_NAME_CACHE_SIZE);
    }

    /**
     * Take a snapshot of each activity in liveActivities.
     * The given UIThreadSet will be accessed
     * on the main UI thread, and should contain a set with elements for every activity to be
     * snapshotted.
     * Given stream out will be written on the calling thread.
     */
    public void snapshots(UIThreadSet<Activity> liveActivities,
                          OutputStream out) throws IOException {
        //传入当前 正在运行的Activity列表
        mRootViewFinder.findInActivities(liveActivities);

        // 对传入的Activity列表的 decorView 进行遍历,为每一个Decorview创建截图
        final FutureTask<List<RootViewInfo>> infoFuture =
                new FutureTask<List<RootViewInfo>>(mRootViewFinder);

        //在主线程中执行
        mMainThreadHandler.post(infoFuture);
        //输出到Web端
        final OutputStreamWriter writer = new OutputStreamWriter(out);
        List<RootViewInfo> infoList = Collections.<RootViewInfo>emptyList();
        writer.write("[");

        try {

            // get方法可能导致阻塞
            //获取之前执行的结果,超过1s 直接超时
            //获取每个Activity的截图 列表
            infoList = infoFuture.get(1, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            MPLog.d(LOGTAG, "Screenshot interrupted, no screenshot will be sent.", e);
        } catch (final TimeoutException e) {
            MPLog.i(LOGTAG, "Screenshot took more than 1 second to be scheduled and executed. No screenshot will be sent.", e);
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Exception thrown during screenshot attempt", e);
        }

        //
        final int infoCount = infoList.size();
        for (int i = 0; i < infoCount; i++) {
            if (i > 0) {
                writer.write(",");
            }
            final RootViewInfo info = infoList.get(i);
            writer.write("{");
            writer.write("\"activity\":");
            writer.write(JSONObject.quote(info.activityName));
            writer.write(",");
            writer.write("\"scale\":");
            writer.write(String.format("%s", info.scale));
            writer.write(",");
            writer.write("\"serialized_objects\":");
            {
                final JsonWriter j = new JsonWriter(writer);
                j.beginObject();
                j.name("rootObject").value(info.rootView.hashCode());
                j.name("objects");
                // 获取当前类 和子类的 各种信息
                // 会遍历当前类的子类
                snapshotViewHierarchy(j, info.rootView);
                j.endObject();
                j.flush();
            }
            writer.write(",");
            writer.write("\"screenshot\":");
            writer.flush();
            //将截图转换成base64形式的字符,并添加到json中
            info.screenshot.writeBitmapJSON(Bitmap.CompressFormat.PNG, 100, out);
            writer.write("}");

        }

        writer.write("]");
        writer.flush();
    }

    // For testing only
    /* package */ List<PropertyDescription> getProperties() {
        return mProperties;
    }

    /**
     * 从rooView开始遍历,获取信息
     *
     * @param j
     * @param rootView
     * @throws IOException
     */
    void snapshotViewHierarchy(JsonWriter j, View rootView)
            throws IOException {
        j.beginArray();
        snapshotView(j, rootView);
        j.endArray();
    }

    private void snapshotView(JsonWriter j, View view)
            throws IOException {
        //如果不可见,根据配置文件进行操作
        if (view.getVisibility() == View.INVISIBLE &&
                mConfig.getIgnoreInvisibleViewsEditor()) {
            // 配置文件 决定忽略不可见的控件,那么直接结束
            return;
        }
        //获取控件id
        final int viewId = view.getId();
        //获取id对应的id-name
        final String viewIdName;
        if (-1 == viewId) {
            viewIdName = null;
        } else {
            viewIdName = mResourceIds.nameForId(viewId);
        }

        j.beginObject();
        j.name("hashCode").value(view.hashCode());
        j.name("id").value(viewId);
        j.name("mp_id_name").value(viewIdName);

        // 保存contentDescription
        final CharSequence description = view.getContentDescription();
        if (null == description) {
            j.name("contentDescription").nullValue();
        } else {
            j.name("contentDescription").value(description.toString());
        }

        //保存TAG
        final Object tag = view.getTag();
        if (null == tag) {
            j.name("tag").nullValue();
        } else if (tag instanceof CharSequence) {
            j.name("tag").value(tag.toString());
        }

        //获取该控件的 坐标信息
        j.name("top").value(view.getTop());
        j.name("left").value(view.getLeft());
        j.name("width").value(view.getWidth());
        j.name("height").value(view.getHeight());
        j.name("scrollX").value(view.getScrollX());
        j.name("scrollY").value(view.getScrollY());
        j.name("visibility").value(view.getVisibility());

        //如果Android-sdk版本大于3.0 出现了 translationX,translationY
        float translationX = 0;
        float translationY = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            translationX = view.getTranslationX();
            translationY = view.getTranslationY();
        }

        j.name("translationX").value(translationX);
        j.name("translationY").value(translationY);

        j.name("classes");
        j.beginArray();
        //获取类的字节码
        Class<?> klass = view.getClass();
        //将当前类 和 其父类关系都添加
        do {
            // 获取CanonicalName ,并进行缓存
            j.value(mClassnameCache.get(klass));
            //获取其父类字节码
            klass = klass.getSuperclass();
        } while (klass != Object.class && klass != null);
        j.endArray();
        // 采集由Web编辑端 下发的 配置文件中的属性,通过这些属性配置,去获取指定信息
        // 添加到Json中
        addProperties(j, view);

        //如果LayoutParams是 RelativeLayout,则将Runles添加到json
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams relativeLayoutParams = (RelativeLayout.LayoutParams) layoutParams;
            int[] rules = relativeLayoutParams.getRules();
            j.name("layoutRules");
            j.beginArray();
            for (int rule : rules) {
                j.value(rule);
            }
            j.endArray();
        }

        // 获取到其子类,遍历所有的子类
        // 获取所有子类的HashCode
        j.name("subviews");
        j.beginArray();
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = group.getChildAt(i);
                // child can be null when views are getting disposed.
                if (null != child) {
                    j.value(child.hashCode());
                }
            }
        }
        j.endArray();
        j.endObject();

        // 对子类进行同样的操作
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View child = group.getChildAt(i);
                // child can be null when views are getting disposed.
                if (null != child) {
                    snapshotView(j, child);
                }
            }
        }
    }


    /**
     * 通过解析 snapshot_request  下发的 配置信息
     * <p>
     * 去获取对应的控件的指定属性
     *
     * @param j
     * @param v
     * @throws IOException
     */
    private void addProperties(JsonWriter j, View v)
            throws IOException {
        //获取控件字节码
        final Class<?> viewClass = v.getClass();
        //遍历 配置文件下发的 需要采集的属性
        for (final PropertyDescription desc : mProperties) {
            // 先匹配到指定的View,同时 判断是否有需要get的属性
            // isAssignableFrom 表示 俩者是否相同 或 前者是后者的超类或接口
            // 只有符合条件,才表示viewClass 拥有该属性,才能够去获取该属性
            if (desc.targetClass.isAssignableFrom(viewClass) && null != desc.accessor) {
                //get到指定属性
                final Object value = desc.accessor.applyMethod(v);
                // 根据不同的类型 进行转换
                if (null == value) {
                    // Don't produce anything in this case
                } else if (value instanceof Number) {
                    j.name(desc.name).value((Number) value);
                } else if (value instanceof Boolean) {
                    j.name(desc.name).value((Boolean) value);
                } else if (value instanceof ColorStateList) {
                    j.name(desc.name).value((Integer) ((ColorStateList) value).getDefaultColor());
                } else if (value instanceof Drawable) {
                    final Drawable drawable = (Drawable) value;
                    final Rect bounds = drawable.getBounds();
                    j.name(desc.name);
                    j.beginObject();
                    j.name("classes");
                    j.beginArray();
                    Class klass = drawable.getClass();
                    while (klass != Object.class) {
                        j.value(klass.getCanonicalName());
                        klass = klass.getSuperclass();
                    }
                    j.endArray();
                    j.name("dimensions");
                    j.beginObject();
                    j.name("left").value(bounds.left);
                    j.name("right").value(bounds.right);
                    j.name("top").value(bounds.top);
                    j.name("bottom").value(bounds.bottom);
                    j.endObject();
                    if (drawable instanceof ColorDrawable) {
                        final ColorDrawable colorDrawable = (ColorDrawable) drawable;
                        j.name("color").value(colorDrawable.getColor());
                    }
                    j.endObject();
                } else {
                    j.name(desc.name).value(value.toString());
                }
            }
        }
    }

    private static class ClassNameCache extends LruCache<Class<?>, String> {
        public ClassNameCache(int maxSize) {
            super(maxSize);
        }

        @Override
        protected String create(Class<?> klass) {
            return klass.getCanonicalName();
        }
    }

    private static class RootViewFinder implements Callable<List<RootViewInfo>> {
        public RootViewFinder() {
            mDisplayMetrics = new DisplayMetrics();
            mRootViews = new ArrayList<RootViewInfo>();
            mCachedBitmap = new CachedBitmap();
        }

        public void findInActivities(UIThreadSet<Activity> liveActivities) {
            mLiveActivities = liveActivities;
        }

        @Override
        public List<RootViewInfo> call() throws Exception {
            mRootViews.clear();

            final Set<Activity> liveActivities = mLiveActivities.getAll();
            //遍历当前存活的activity
            for (final Activity a : liveActivities) {
                // 获取Activity类的名称
                final String activityName = a.getClass().getCanonicalName();
                // 获取RootView,理论上来说就是DecorView
                final View rootView = a.getWindow().getDecorView().getRootView();
                // 获取屏幕的一些信息,保存到 mDisplayMetrics
                a.getWindowManager().getDefaultDisplay().getMetrics(mDisplayMetrics);
                final RootViewInfo info = new RootViewInfo(activityName, rootView);
                mRootViews.add(info);
            }
            //开始 所有的这些rootView创建截图
            final int viewCount = mRootViews.size();
            for (int i = 0; i < viewCount; i++) {
                final RootViewInfo info = mRootViews.get(i);
                //创建截图,保存信息在info对象中
                takeScreenshot(info);
            }

            return mRootViews;
        }


        /**
         * 对RootView进行截图
         *
         * @param info
         */
        private void takeScreenshot(final RootViewInfo info) {
            final View rootView = info.rootView;
            Bitmap rawBitmap = null;

            try {
                //获取createSnapshot 方法,获得当前屏幕截图
                final Method createSnapshot = View.class.getDeclaredMethod(
                        "createSnapshot",
                        Bitmap.Config.class,
                        Integer.TYPE,
                        Boolean.TYPE);
                createSnapshot.setAccessible(true);
                rawBitmap = (Bitmap) createSnapshot.invoke(rootView,
                        Bitmap.Config.RGB_565,
                        Color.WHITE, false);
            } catch (final NoSuchMethodException e) {
                MPLog.v(LOGTAG, "Can't call createSnapshot, will use drawCache", e);
            } catch (final IllegalArgumentException e) {
                MPLog.d(LOGTAG, "Can't call createSnapshot with arguments", e);
            } catch (final InvocationTargetException e) {
                MPLog.e(LOGTAG, "Exception when calling createSnapshot", e);
            } catch (final IllegalAccessException e) {
                MPLog.e(LOGTAG, "Can't access createSnapshot, using drawCache", e);
            } catch (final ClassCastException e) {
                MPLog.e(LOGTAG, "createSnapshot didn't return a bitmap?", e);
            }

            //使用另外一种方式获取当前视图截图
            Boolean originalCacheState = null;
            try {
                if (null == rawBitmap) {
                    originalCacheState = rootView.isDrawingCacheEnabled();
                    rootView.setDrawingCacheEnabled(true);
                    rootView.buildDrawingCache(true);
                    rawBitmap = rootView.getDrawingCache();
                }
            } catch (final RuntimeException e) {
                MPLog.v(LOGTAG, "Can't take a bitmap snapshot of view " + rootView + ", skipping for now.", e);
            }

            float scale = 1.0f;
            if (null != rawBitmap) {
                //检查Bitmap是否进行过缩放 ,还原真实的大小
                //获取该Bitmap适合的屏幕dpi
                final int rawDensity = rawBitmap.getDensity();
                // 未知density
                if (rawDensity != Bitmap.DENSITY_NONE) {
                    scale = ((float) mClientDensity) / rawDensity;
                }
                //原始 长宽
                final int rawWidth = rawBitmap.getWidth();
                final int rawHeight = rawBitmap.getHeight();
                // 转换后的长宽
                final int destWidth = (int) ((rawBitmap.getWidth() * scale) + 0.5);
                final int destHeight = (int) ((rawBitmap.getHeight() * scale) + 0.5);

                if (rawWidth > 0 && rawHeight > 0 && destWidth > 0 && destHeight > 0) {
                    //保存到mCachedBitmap中
                    mCachedBitmap.recreate(destWidth, destHeight, mClientDensity, rawBitmap);
                }
            }
            // 恢复drawingCacheEnabled选项
            if (null != originalCacheState && !originalCacheState) {
                rootView.setDrawingCacheEnabled(false);
            }
            //保存缩放比例
            info.scale = scale;
            //保存截图
            info.screenshot = mCachedBitmap;
        }

        /**
         * 当前存活的Activity 列表
         */
        private UIThreadSet<Activity> mLiveActivities;
        /**
         * 保存了 Activity 和 DecorView的关系
         * RootViewInfo 是对俩者关系的封装类
         * <p>
         * 以及相关的截图信息
         */
        private final List<RootViewInfo> mRootViews;
        private final DisplayMetrics mDisplayMetrics;
        private final CachedBitmap mCachedBitmap;
        /**
         * 默认屏幕密度
         */
        private final int mClientDensity = DisplayMetrics.DENSITY_DEFAULT;
    }

    private static class CachedBitmap {
        public CachedBitmap() {
            mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            mCached = null;
        }

        public synchronized void recreate(int width, int height,
                                          int destDensity, Bitmap source) {
            //缓存为空,或者大小不同
            if (null == mCached ||
                    mCached.getWidth() != width ||
                    mCached.getHeight() != height) {
                try {
                    //创建新的图纸用来装载图片
                    mCached = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
                } catch (final OutOfMemoryError e) {
                    mCached = null;
                }

                if (null != mCached) {
                    //设置density
                    mCached.setDensity(destDensity);
                }
            }
            if (null != mCached) {
                //将原图画到缓存的Bitmap上
                final Canvas scaledCanvas = new Canvas(mCached);
                scaledCanvas.drawBitmap(source, 0, 0, mPaint);
            }
        }

        // Writes a QUOTED base64 string (or the string null) to the output stream

        /**
         * 将图片转成base64,添加到json中
         *
         * @param format
         * @param quality
         * @param out
         * @throws IOException
         */
        public synchronized void writeBitmapJSON(Bitmap.CompressFormat format,
                                                 int quality,
                                                 OutputStream out)
                throws IOException {
            if (null == mCached || mCached.getWidth() == 0 || mCached.getHeight() == 0) {
                out.write("null".getBytes());
            } else {
                out.write('"');
                final Base64OutputStream imageOut = new Base64OutputStream(out, Base64.NO_WRAP);
                mCached.compress(Bitmap.CompressFormat.PNG, 100, imageOut);
                imageOut.flush();
                out.write('"');
            }
        }

        private Bitmap mCached;
        private final Paint mPaint;
    }

    private static class RootViewInfo {
        public RootViewInfo(String activityName, View rootView) {
            this.activityName = activityName;
            this.rootView = rootView;
            this.screenshot = null;
            this.scale = 1.0f;
        }

        public final String activityName;
        /**
         * 理论来说是 DecorView
         */
        public final View rootView;
        public CachedBitmap screenshot;
        public float scale;
    }

    /**
     * 本地配置信息
     */
    private final MPConfig mConfig;
    /**
     * 实现了 Callable 接口
     */
    private final RootViewFinder mRootViewFinder;
    /**
     * 待抓取的控件的相关属性
     */
    private final List<PropertyDescription> mProperties;
    /**
     * LruCache,
     * <p>
     * 类字节码 - 类名称
     */
    private final ClassNameCache mClassnameCache;
    /**
     * 运行在主线程的 Handler
     */
    private final Handler mMainThreadHandler;
    /**
     * 资源管理类,通过该类获取 id 或 id名称
     */
    private final ResourceIds mResourceIds;

    private static final int MAX_CLASS_NAME_CACHE_SIZE = 255;

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.Snapshot";
}
