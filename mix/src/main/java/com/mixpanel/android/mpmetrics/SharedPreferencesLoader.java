package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

class SharedPreferencesLoader {

    interface OnPrefsLoadedListener {
        void onPrefsLoaded(SharedPreferences prefs);
    }

    /**
     * 单线程线程池
     */
    private final Executor mExecutor;

    public SharedPreferencesLoader() {
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * 提前在线程池中创建SP, 之后使用FutureTask 直接获取
     *
     * @param context
     * @param name
     * @param listener
     * @return
     */
    public Future<SharedPreferences> loadPreferences(Context context, String name, OnPrefsLoadedListener listener) {
        final LoadSharedPreferences loadSharedPrefs = new LoadSharedPreferences(context, name, listener);
        final FutureTask<SharedPreferences> task = new FutureTask<SharedPreferences>(loadSharedPrefs);
        mExecutor.execute(task);
        return task;
    }

    private static class LoadSharedPreferences implements Callable<SharedPreferences> {


        private final Context mContext;
        //SP名称
        private final String mPrefsName;
        //创建成功的回调
        private final OnPrefsLoadedListener mListener;

        public LoadSharedPreferences(Context context, String prefsName, OnPrefsLoadedListener listener) {
            mContext = context;
            mPrefsName = prefsName;
            mListener = listener;
        }

        @Override
        public SharedPreferences call() {
            // 创建了指定的sp
            final SharedPreferences ret = mContext.getSharedPreferences(mPrefsName, Context.MODE_PRIVATE);
            // 调用回调函数
            if (null != mListener) {
                mListener.onPrefsLoaded(ret);
            }
            return ret;
        }

    }


}
