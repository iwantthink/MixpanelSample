package com.mixpanel.android.viewcrawler;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

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
        //添加到Set中,并检查当前线程是否是main,只有在main中才能添加
        super.add(newOne);
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
     *
     * @param newEdits A Map from activity name to a list of edits to apply
     */
    // Must be thread-safe
    public void setEdits(Map<String, List<ViewVisitor>> newEdits) {
        // Delete images that are no longer needed

        synchronized (mCurrentEdits) {
            for (final EditBinding stale : mCurrentEdits) {
                //停止循环
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
        for (final Activity activity : getAll()) {
            //获取Activity名称
            final String activityName = activity.getClass().getCanonicalName();
            //decorView的RootView
            final View rootView = activity.getWindow().getDecorView().getRootView();

            final List<ViewVisitor> specificChanges;
            final List<ViewVisitor> wildcardChanges;
            synchronized (mIntendedEdits) {
                //存在具体Activity
                specificChanges = mIntendedEdits.get(activityName);
                //不存在具体activity,通配
                wildcardChanges = mIntendedEdits.get(null);
            }

            //将这些AccessibilityDelegate 添加到View上
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
                final EditBinding binding =
                        new EditBinding(rootView, visitor, mUiThreadHandler);
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
                //注册回调,在视图发生变化时 会执行run
                observer.addOnGlobalLayoutListener(this);
            }
            //开启run循环
            run();
        }

        @Override
        public void onGlobalLayout() {
            run();
        }

        @Override
        public void run() {
            if (!mAlive) {
                return;
            }
            //获取rootView
            final View viewRoot = mViewRoot.get();
            //如果为空 || 已经死亡
            if (null == viewRoot || mDying) {
                //移除ViewTreeObserver的回调
                cleanUp();
                return;
            }
            // ELSE View is alive and we are alive
            //重要部分!
            //调用ViewVisitor 中的visit方法(未被子类重写)
            //具体实现交给了PathFinder 的findTargetsInRoot方法
            mEdit.visit(viewRoot);
            //移除当前消息队列中的Runnable
            mHandler.removeCallbacks(this);
            //另外发送一条message 到消息队列中,延迟1s
            mHandler.postDelayed(this, 1000);
        }

        public void kill() {
            mDying = true;
            mHandler.post(this);
        }

        @SuppressWarnings("deprecation")
        private void cleanUp() {
            //当前存活
            if (mAlive) {
                final View viewRoot = mViewRoot.get();
                if (null != viewRoot) {
                    //移除GlobalOnLayoutListener
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

        private volatile boolean mDying;
        private boolean mAlive;
        private final WeakReference<View> mViewRoot;
        /**
         * AccessibilityDelegate
         * 1. AddAccessibilityEventVisitor
         * 2. AddTextChangeListener
         * 3. ViewDetectorVisitor
         */
        private final ViewVisitor mEdit;
        private final Handler mHandler;
    }

    private final Handler mUiThreadHandler;
    /**
     * ActivityName -  ViewVisitor集合
     * <p>
     * 一个Activity  对应 的事件ViewVisitor
     */
    private final Map<String, List<ViewVisitor>> mIntendedEdits;
    /**
     * 当前的绑定的信息,控件对应的事件
     */
    private final Set<EditBinding> mCurrentEdits;

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.EditState";
}
