package com.mixpanel.android.viewcrawler;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

import com.mixpanel.android.util.MPLog;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles applying and managing the life cycle of edits in an application. Clients
 * can replace all of the edits in an app with {@link EditState#setEdits(java.util.Map)}.
 * <p>
 * Some client is responsible for informing the EditState about the presence or absence
 * of Activities, by calling {@link EditState#add(android.app.Activity)} and {@link EditState#remove(android.app.Activity)}
 */
/* package */ class EditState extends UIThreadSet<Activity> {

    public EditState() {
        mUiThreadHandler = new Handler(Looper.getMainLooper());
        mIntendedEdits = new HashMap<String, List<ViewVisitor>>();
        mCurrentEdits = new HashSet<EditBinding>();
    }

    /**
     * Should be called whenever a new Activity appears in the application.
     */
    @Override
    public void add(Activity newOne) {
        // 添加到Set中,并检查当前线程是否是main,只有在main中才能添加
        super.add(newOne);
        // 由于Activity 信息 更新了,所以一些相对应的信息也需要进行更新,例如 AccessibilityDelegate
        applyEditsOnUiThread();
    }

    /**
     * Should be called whenever an activity leaves the application, or is otherwise no longer relevant to our edits.
     */
    @Override
    public void remove(Activity oldOne) {
        super.remove(oldOne);
    }

    /**
     * Sets the entire set of edits to be applied to the application.
     * <p>
     * Edits are represented by ViewVisitors, batched in a map by the String name of the activity
     * they should be applied to. Edits to apply to all views should be in a list associated with
     * the key {@code null} (Not the string "null", the actual null value!)
     * <p>
     * The given edits will completely replace any existing edits.
     * <p>
     * setEdits can be called from any thread, although the changes will occur (eventually) on the
     * UI thread of the application, and may not appear immediately.
     * <p>
     * <p>
     * 这个方法 在 ApplicationLifeCallback 中 也会被调用到...
     * 具体的是 onResume()方法中, joinExperimentIfAvailable()
     * 所以正常情况下 只会有 一个 EditBinding 在运行... 当然俩个也有可能
     *
     * @param newEdits A Map from activity name to a list of edits to apply
     */
    // Must be thread-safe
    public void setEdits(Map<String, List<ViewVisitor>> newEdits) {

        MPLog.v("EditState", "setEdits , newEdits.size = " + newEdits.size());
        // Delete images that are no longer needed

        synchronized (mCurrentEdits) {
            for (final EditBinding stale : mCurrentEdits) {
                // 停止循环
                // 清除缓存
                stale.kill();
            }
            //清空
            mCurrentEdits.clear();
        }

        synchronized (mIntendedEdits) {
            //清空
            mIntendedEdits.clear();
            //将传入的信息 转存到mIntendedEdits 集合中
            mIntendedEdits.putAll(newEdits);
        }

        applyEditsOnUiThread();
    }

    /**
     * 判断是否在主线程中,如果不是则切换到主线程中
     * <p>
     * 这个方法在初始化时会被调用 , 在 有新的activity 添加的时候也会被调用
     */
    private void applyEditsOnUiThread() {
        if (Thread.currentThread() == mUiThreadHandler.getLooper().getThread()) {
            applyIntendedEdits();
        } else {
            mUiThreadHandler.post(new Runnable() {
                @Override
                public void run() {
                    applyIntendedEdits();
                }
            });
        }
    }

    // Must be called on UI Thread
    private void applyIntendedEdits() {
        //遍历所有在集合中的Activity
        // LifecycleCallbacks(ViewCrawler) 会实时更新 Activity信息
        for (final Activity activity : getAll()) {
            //获取Activity名称
            final String activityName = activity.getClass().getCanonicalName();
            //decorView的RootView
            // 就是DecovView
            final View rootView = activity.getWindow().getDecorView().getRootView();

            final List<ViewVisitor> specificChanges;
            final List<ViewVisitor> wildcardChanges;
            synchronized (mIntendedEdits) {
                //存在具体Activity
                specificChanges = mIntendedEdits.get(activityName);
                //不存在具体activity,通配符
                wildcardChanges = mIntendedEdits.get(null);
            }

            //将这些 AccessibilityDelegate 添加到View上
            if (null != specificChanges) {
                applyChangesFromList(rootView, specificChanges);
            }

            if (null != wildcardChanges) {
                applyChangesFromList(rootView, wildcardChanges);
            }
        }
    }


    /**
     * Must be called on UI Thread
     *
     * @param rootView decorView的RootView
     * @param changes
     */
    private void applyChangesFromList(View rootView, List<ViewVisitor> changes) {
        synchronized (mCurrentEdits) {
            final int size = changes.size();
            for (int i = 0; i < size; i++) {
                final ViewVisitor visitor = changes.get(i);
                //保存 事件和 View之间的关联信息
                //是一个Runnable 也是一个onGlobalLayoutListener回调
                //一旦创建就开启不停的循环,判断是否需要移除 onGlobalLayoutListener回调和 AccessibilityDelegate
                // 一个 DecorView 对应一个 EditBinding ..
                // 多个Activity 就会对应多个 DecorView, 那么就会有多个EditBinding 同时运行
                // 但是 MixpanelActivityLifecycleCallbacks 生命周期 的onResume() 中会调用 setEdits()
                // 其会清空 mCurrentEdits 并重新开启新的循环
                // 而LifecycleCallbacks(ViewCrawler) 会实时更新 Activity信息
                final EditBinding binding =
                        new EditBinding(rootView,
                                visitor,
                                mUiThreadHandler);
                // 将RootView EventTriggeringVisitor 和 Handler(main) 保存到 EditBinding
                mCurrentEdits.add(binding);
            }
        }
    }

    /* The binding between a bunch of edits and a view.
    Should be instantiated and live on the UI thread */
    private static class EditBinding implements
            ViewTreeObserver.OnGlobalLayoutListener,
            Runnable {
        /**
         * @param viewRoot        DecorView.getRootView()
         * @param edit
         * @param uiThreadHandler
         */
        public EditBinding(View viewRoot, ViewVisitor edit, Handler uiThreadHandler) {
            mEdit = edit;
            //保存rootView
            mViewRoot = new WeakReference<View>(viewRoot);
            mHandler = uiThreadHandler;
            mAlive = true;
            mDying = false;

            final ViewTreeObserver observer = viewRoot.getViewTreeObserver();
            //防止出现异常,必须先判断是否存活
            if (observer.isAlive()) {
                //注册回调,在视图发生变化时,或者可见度发生变化,则会执行run
                observer.addOnGlobalLayoutListener(this);
            }
            //手动开启run循环
            run();
        }

        /**
         * 控件树布局或可见度 发生改变时会回调
         */
        @Override
        public void onGlobalLayout() {
            run();
        }

        @Override
        public void run() {
            //当前Runnable 是否存活
            if (!mAlive) {
                // 非存活状态,直接跳出
                return;
            }
            //获取rootView
            final View viewRoot = mViewRoot.get();

            Log.d("EditBinding", "ViewRoot.hashCode():" + viewRoot.hashCode());

            //如果为空 || 已经死亡(被kill()方法控制)
            if (null == viewRoot || mDying) {
                //移除ViewTreeObserver的回调
                cleanUp();
                return;
            }

            // ELSE View is alive and we are alive
            //重要部分!
            //调用EventTriggeringVisitor 中的visit方法(未被子类重写,实现在ViewVisitor)
            //具体实现交给了PathFinder 的findTargetsInRoot方法
            // mEdit 是 EventTriggeringVisitor 类型的
            mEdit.visit(viewRoot);
            //移除当前消息队列中的Runnable
            mHandler.removeCallbacks(this);
            //另外发送一条message 到消息队列中,延迟1s
            mHandler.postDelayed(this, 1000);
        }

        public void kill() {
            mDying = true;
            // 需要在run()方法中 做一些 清除 AccesibilityDelegate 的操作
            mHandler.post(this);
        }

        @SuppressWarnings("deprecation")
        private void cleanUp() {
            //当前存活,所以需要去除一些状态
            if (mAlive) {
                final View viewRoot = mViewRoot.get();
                if (null != viewRoot) {
                    //移除GlobalOnLayoutListener,其监听着控件布局变换
                    final ViewTreeObserver observer = viewRoot.getViewTreeObserver();
                    if (observer.isAlive()) {
                        observer.removeGlobalOnLayoutListener(this); // Deprecated Name
                    }
                }
                //AddAccessibilityEventVisitor.cleanup()
                //移除控件的AccesibilityDelegate!!!!!
                mEdit.cleanup();
            }
            mAlive = false;
        }

        /**
         * 用来控制结束循环
         */
        private volatile boolean mDying;
        /**
         * 当前循环是否继续
         */
        private boolean mAlive;
        /**
         * 正常情况下是 DecorView
         * <p>
         * *
         */
        private final WeakReference<View> mViewRoot;
        /**
         * EventTriggeringVisitor
         * <p>
         * 具体类型如下: 这个不是具体的 delegate类,而是 辅助设置delegate的类
         * EventTriggeringVisitor
         * 1. AddAccessibilityEventVisitor
         * 2. AddTextChangeListener
         * 3. ViewDetectorVisitor
         */
        private final ViewVisitor mEdit;
        /**
         * 运行在主线程的Handler
         */
        private final Handler mHandler;
    }

    /**
     * 运行在主线程的Handler
     */
    private final Handler mUiThreadHandler;
    /**
     * ActivityName -  ViewVisitor集合
     * <p>
     * 一个Activity  对应 的事件ViewVisitor
     */
    private final Map<String, List<ViewVisitor>> mIntendedEdits;
    /**
     * 当前的绑定的信息,控件对应的事件
     * <p>
     * EventTriggeringVisitor,RootView,Handler(in main thread) 等信息 会被封装到 EditBinding 对象中
     */
    private final Set<EditBinding> mCurrentEdits;

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.EditState";
}
