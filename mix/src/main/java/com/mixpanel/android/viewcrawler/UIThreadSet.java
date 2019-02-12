package com.mixpanel.android.viewcrawler;


import android.os.Looper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Wrapper around a set that will throw RuntimeErrors if accessed in a thread that is not the main thread.
 */
/* package */ class UIThreadSet<T> {
    public UIThreadSet() {
        mSet = new HashSet<T>();
    }

    /**
     * 只能在主线程中对集合进行添加
     *
     * @param item
     */
    public void add(T item) {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            throw new RuntimeException("Can't add an activity when not on the UI thread");
        }
        mSet.add(item);
    }

    /**
     * 只能在主线程中对集合进行删除
     *
     * @param item
     */
    public void remove(T item) {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            throw new RuntimeException("Can't remove an activity when not on the UI thread");
        }
        mSet.remove(item);
    }

    /**
     * 只能在主线程中
     * <p>
     * 获取当前所有正在运行的activity
     *
     * @return
     */
    public Set<T> getAll() {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            throw new RuntimeException("Can't remove an activity when not on the UI thread");
        }
        return Collections.unmodifiableSet(mSet);
    }

    /**
     * 只能在主线程中
     * 判断是否为空
     *
     * @return
     */
    public boolean isEmpty() {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            throw new RuntimeException("Can't check isEmpty() when not on the UI thread");
        }
        return mSet.isEmpty();
    }

    private Set<T> mSet;
}
