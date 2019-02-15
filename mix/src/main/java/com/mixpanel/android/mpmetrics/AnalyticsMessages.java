package com.mixpanel.android.mpmetrics;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.android.gms.iid.InstanceID;
import com.mixpanel.android.util.Base64Coder;
import com.mixpanel.android.util.HttpService;
import com.mixpanel.android.util.MPLog;
import com.mixpanel.android.util.RemoteService;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import javax.net.ssl.SSLSocketFactory;

/**
 * Manage communication of events with the internal database and the Mixpanel servers.
 * <p>
 * <p>This class straddles the thread boundary between user threads and
 * a logical Mixpanel thread.
 */
/* package */ class AnalyticsMessages {

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context) {
        mContext = context;
        //从清单文件中获取配置信息
        mConfig = getConfig(context);

        // 创建Worker 用来管理进行IO的Thread
        // Worker 会创建一个Handler,运行在自己创建的HandlerThread中
        //
        mWorker = createWorker();
        //创建一个新的HttpService , 去判断是否 启用了ADBlocker
        getPoster().checkIsMixpanelBlocked();
    }

    protected Worker createWorker() {
        return new Worker();
    }

    /**
     * Use this to get an instance of AnalyticsMessages instead of creating one directly
     * for yourself.
     *
     * @param messageContext should be the Main Activity of the application
     *                       associated with these messages.
     */
    public static AnalyticsMessages getInstance(final Context messageContext) {
        synchronized (sInstances) {
            final Context appContext = messageContext.getApplicationContext();
            AnalyticsMessages ret;
            //单例
            if (!sInstances.containsKey(appContext)) {
                ret = new AnalyticsMessages(appContext);
                sInstances.put(appContext, ret);
            } else {
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    public void eventsMessage(final EventDescription eventDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = eventDescription;
        mWorker.runMessage(m);
    }

    // Must be thread safe.
    public void peopleMessage(final PeopleDescription peopleDescription) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_PEOPLE;
        m.obj = peopleDescription;

        mWorker.runMessage(m);
    }

    public void postToServer(final FlushDescription flushDescription) {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        m.obj = flushDescription.getToken();
        m.arg1 = flushDescription.shouldCheckDecide() ? 1 : 0;

        mWorker.runMessage(m);
    }

    public void installDecideCheck(final DecideMessages check) {
        final Message m = Message.obtain();
        m.what = INSTALL_DECIDE_CHECK;
        m.obj = check;

        mWorker.runMessage(m);
    }

    public void registerForGCM(final String senderID) {
        final Message m = Message.obtain();
        m.what = REGISTER_FOR_GCM;
        m.obj = senderID;

        mWorker.runMessage(m);
    }

    public void emptyTrackingQueues(final MixpanelDescription mixpanelDescription) {
        final Message m = Message.obtain();
        m.what = EMPTY_QUEUES;
        m.obj = mixpanelDescription;

        mWorker.runMessage(m);
    }

    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;

        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    /**
     * 创建SqliteOpenHelper
     *
     * @param context
     * @return
     */
    protected MPDbAdapter makeDbAdapter(Context context) {
        return MPDbAdapter.getInstance(context);
    }

    /**
     * 获取配置信息的单例对象
     *
     * @param context
     * @return
     */
    protected MPConfig getConfig(Context context) {
        return MPConfig.getInstance(context);
    }

    /**
     * 创建一个新的HttpService
     *
     * @return
     */
    protected RemoteService getPoster() {
        return new HttpService();
    }

    ////////////////////////////////////////////////////

    static class EventDescription extends MixpanelDescription {
        public EventDescription(String eventName,
                                JSONObject properties,
                                String token) {
            this(eventName, properties, token, false, new JSONObject());
        }

        public EventDescription(String eventName,
                                JSONObject properties,
                                String token,
                                boolean isAutomatic,
                                JSONObject sessionMetada) {
            super(token);
            mEventName = eventName;
            mProperties = properties;
            mIsAutomatic = isAutomatic;
            mSessionMetadata = sessionMetada;
        }

        public String getEventName() {
            return mEventName;
        }

        public JSONObject getProperties() {
            return mProperties;
        }

        public JSONObject getSessionMetadata() {
            return mSessionMetadata;
        }

        public boolean isAutomatic() {
            return mIsAutomatic;
        }

        private final String mEventName;
        private final JSONObject mProperties;
        private final JSONObject mSessionMetadata;
        private final boolean mIsAutomatic;
    }

    static class PeopleDescription extends MixpanelDescription {
        public PeopleDescription(JSONObject message, String token) {
            super(token);
            this.message = message;
        }

        @Override
        public String toString() {
            return message.toString();
        }

        public JSONObject getMessage() {
            return message;
        }

        private final JSONObject message;
    }

    static class FlushDescription extends MixpanelDescription {
        public FlushDescription(String token) {
            this(token, true);
        }

        protected FlushDescription(String token, boolean checkDecide) {
            super(token);
            this.checkDecide = checkDecide;
        }


        public boolean shouldCheckDecide() {
            return checkDecide;
        }

        private final boolean checkDecide;
    }

    /**
     * 保存了token的 bean类
     */
    static class MixpanelDescription {
        public MixpanelDescription(String token) {
            this.mToken = token;
        }

        public String getToken() {
            return mToken;
        }

        private final String mToken;
    }

    // Sends a message if and only if we are running with Mixpanel Message log enabled.
    // Will be called from the Mixpanel thread.
    private void logAboutMessageToMixpanel(String message) {
        MPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
    }

    private void logAboutMessageToMixpanel(String message, Throwable e) {
        MPLog.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
    }

    // Worker will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance.
    // XXX: Worker class is unnecessary, should be just a subclass of HandlerThread
    // Worker 能够管理与 AnalyticsMessages 实例相关联的 IO 线程,最多一个!
    class Worker {
        public Worker() {
            mHandler = restartWorkerThread();
        }

        /**
         * 判断Handler 是否为空
         *
         * @return
         */
        public boolean isDead() {
            synchronized (mHandlerLock) {
                return mHandler == null;
            }
        }

        /**
         * 发送指定msg
         *
         * @param msg
         */
        public void runMessage(Message msg) {
            synchronized (mHandlerLock) {
                // 对Handler进行非空判断
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToMixpanel("Dead mixpanel worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        // NOTE that the returned worker will run FOREVER, unless you send a hard kill
        // (which you really shouldn't)
        protected Handler restartWorkerThread() {
            // 创建一个HandlerThread,其开启了 Looper
            final HandlerThread thread = new HandlerThread("com.mixpanel.android.AnalyticsWorker", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            // 创建一个Handler  具体逻辑 执行在HandlerThread中
            final Handler ret = new AnalyticsMessageHandler(thread.getLooper());
            return ret;
        }

        class AnalyticsMessageHandler extends Handler {

            public AnalyticsMessageHandler(Looper looper) {
                super(looper);
                // Handler初始化时 不会去创建,只有在处理msg时 才会去创建 DBAdapter
                mDbAdapter = null;
                // 系统信息获取的封装
                mSystemInformation = SystemInformation.getInstance(mContext);
                mDecideChecker = createDecideChecker();
                // 刷新间隔间隔
                mFlushInterval = mConfig.getFlushInterval();
            }

            protected DecideChecker createDecideChecker() {
                return new DecideChecker(mContext, mConfig);
            }

            @Override
            public void handleMessage(Message msg) {
                // 创建 SqliteOpenHelper
                // 并删除超过有效期的数据 , 针对 Events 表 和 People表
                if (mDbAdapter == null) {
                    mDbAdapter = makeDbAdapter(mContext);
                    // 当前时间- 有效期限 = 最大的有效时间
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(),
                            MPDbAdapter.Table.EVENTS);
                    mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(),
                            MPDbAdapter.Table.PEOPLE);
                }

                try {
                    //执行相应指令的 响应码,会根据这个响应码做一些其他的事情
                    // 默认是 DB 未定义
                    int returnCode = MPDbAdapter.DB_UNDEFINED_CODE;
                    String token = null;

                    // people 入队
                    if (msg.what == ENQUEUE_PEOPLE) {
                        final PeopleDescription message = (PeopleDescription) msg.obj;

                        logAboutMessageToMixpanel("Queuing people record for sending later");
                        logAboutMessageToMixpanel("    " + message.toString());
                        token = message.getToken();
                        returnCode = mDbAdapter.addJSON(message.getMessage(), token, MPDbAdapter.Table.PEOPLE, false);
                        // event 入队
                    } else if (msg.what == ENQUEUE_EVENTS) {
                        final EventDescription eventDescription =
                                (EventDescription) msg.obj;
                        try {
                            //解析
                            final JSONObject message = prepareEventObject(eventDescription);
                            logAboutMessageToMixpanel("Queuing event for sending later");
                            logAboutMessageToMixpanel("    " + message.toString());
                            token = eventDescription.getToken();

                            DecideMessages decide =
                                    mDecideChecker.getDecideMessages(token);

                            // DecideMessages 不为空
                            // 事件是自动事件
                            // DecideMessages 不抓取 自动事件
                            if (decide != null &&
                                    eventDescription.isAutomatic() &&
                                    !decide.shouldTrackAutomaticEvent()) {
                                // 直接结束数据上传
                                return;
                            }
                            //执行成功,则返回插入数据的数量
                            //执行失败,则返回失败的原因
                            // 就是往数据库中添加数据
                            returnCode = mDbAdapter.addJSON(message,
                                    token,
                                    MPDbAdapter.Table.EVENTS,
                                    eventDescription.isAutomatic());
                        } catch (final JSONException e) {
                            MPLog.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }

                        // 上传信息
                        // 根据周期性计划或是 强制刷新
                    } else if (msg.what == FLUSH_QUEUE) {
                        //将数据库中的数据统统出库上传
                        logAboutMessageToMixpanel("Flushing queue due to scheduled or forced flush");
                        // 更新刷新频率信息
                        updateFlushFrequency();
                        // token信息
                        token = (String) msg.obj;
                        //  这个需要查看 发送FLUSH_QUEUE 消息的地方
                        boolean shouldCheckDecide = msg.arg1 == 1 ? true : false;

                        sendAllData(mDbAdapter, token);
                        if (shouldCheckDecide && SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(token, getPoster());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                    } else if (msg.what == INSTALL_DECIDE_CHECK) {
                        logAboutMessageToMixpanel("Installing a check for in-app notifications");
                        final DecideMessages check = (DecideMessages) msg.obj;
                        // 往 mChecks 中添加DecideMessages
                        // key= token , obj = DecideMessages
                        mDecideChecker.addDecideCheck(check);
                        //超过重试的时间
                        if (SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(
                                        check.getToken(),
                                        getPoster());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() +
                                        e.getRetryAfter() * 1000;
                            }
                        }
                    } else if (msg.what == REGISTER_FOR_GCM) {
                        final String senderId = (String) msg.obj;
                        runGCMRegistration(senderId);

                        // 清空数据库
                    } else if (msg.what == EMPTY_QUEUES) {
                        //清空数据库中所有的 Events 和People相关的数据
                        final MixpanelDescription message = (MixpanelDescription) msg.obj;
                        token = message.getToken();
                        // 清空 events表中 指定token的数据
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.EVENTS, token);
                        // 清空 people表中 指定token的数据
                        mDbAdapter.cleanupAllEvents(MPDbAdapter.Table.PEOPLE, token);

                        // 强制杀死 Worker
                    } else if (msg.what == KILL_WORKER) {
                        MPLog.w(LOGTAG, "Worker received a hard kill. " +
                                "Dumping all events and force-killing. " +
                                "Thread id " + Thread.currentThread().getId());
                        synchronized (mHandlerLock) {
                            // 删除数据库
                            mDbAdapter.deleteDB();
                            // 置空 Handler
                            mHandler = null;
                            // 关闭Looper
                            Looper.myLooper().quit();
                        }
                    } else {
                        MPLog.e(LOGTAG, "Unexpected message received by Mixpanel worker: " + msg);
                    }


                    // 针对 returnCode 进行判断
                    // ENQUEUE_PEOPLE 和 ENQUEUE_EVENTS 时 会对returncode进行修改,默认是 DB_UNDEFINED_CODE
                    // 如果 returnCode 超过一次上传的数量限制,默认是40
                    // 或者 db out of memory error
                    if ((returnCode >= mConfig.getBulkUploadLimit() ||
                            returnCode == MPDbAdapter.DB_OUT_OF_MEMORY_ERROR)
                            && mFailedRetries <= 0 && token != null) {
                        logAboutMessageToMixpanel("Flushing queue due to bulk upload limit (" + returnCode + ") for project " + token);
                        //更新并记录刷新时间
                        updateFlushFrequency();
                        sendAllData(mDbAdapter, token);
                        if (SystemClock.elapsedRealtime() >= mDecideRetryAfter) {
                            try {
                                mDecideChecker.runDecideCheck(token, getPoster());
                            } catch (RemoteService.ServiceUnavailableException e) {
                                mDecideRetryAfter = SystemClock.elapsedRealtime() + e.getRetryAfter() * 1000;
                            }
                        }
                    } else if (returnCode > 0 && !hasMessages(FLUSH_QUEUE, token)) {
                        // The !hasMessages(FLUSH_QUEUE, token) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToMixpanel("Queue depth " + returnCode + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            final Message flushMessage = Message.obtain();
                            flushMessage.what = FLUSH_QUEUE;
                            flushMessage.obj = token;
                            flushMessage.arg1 = 1;
                            sendMessageDelayed(flushMessage, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    MPLog.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            MPLog.e(LOGTAG, "Mixpanel will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            MPLog.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            }// handleMessage

            protected long getTrackEngageRetryAfter() {
                return mTrackEngageRetryAfter;
            }

            private void runGCMRegistration(String senderID) {
                final String registrationId;
                try {
                    // We don't actually require Google Play Services to be available
                    // (since we can't specify what version customers will be using,
                    // and because the latest Google Play Services actually have
                    // dependencies on Java 7)

                    // Consider adding a transitive dependency on the latest
                    // Google Play Services version and requiring Java 1.7
                    // in the next major library release.
                    try {
                        final int resultCode = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(mContext);
                        if (resultCode != ConnectionResult.SUCCESS) {
                            MPLog.i(LOGTAG, "Can't register for push notifications, Google Play Services are not installed.");
                            return;
                        }
                    } catch (RuntimeException e) {
                        MPLog.i(LOGTAG, "Can't register for push notifications, Google Play services are not configured.");
                        return;
                    }

                    InstanceID instanceID = InstanceID.getInstance(mContext);
                    registrationId = instanceID.getToken(senderID, GoogleCloudMessaging.INSTANCE_ID_SCOPE, null);
                } catch (IOException e) {
                    MPLog.i(LOGTAG, "Exception when trying to register for GCM", e);
                    return;
                } catch (NoClassDefFoundError e) {
                    MPLog.w(LOGTAG, "Google play services were not part of this build, push notifications cannot be registered or delivered");
                    return;
                }

                MixpanelAPI.allInstances(new MixpanelAPI.InstanceProcessor() {
                    @Override
                    public void process(MixpanelAPI api) {
                        MPLog.v(LOGTAG, "Using existing pushId " + registrationId);
                        api.getPeople().setPushRegistrationId(registrationId);
                    }
                });
            }

            private void sendAllData(MPDbAdapter dbAdapter, String token) {
                final RemoteService poster = getPoster();
                // 如果无法联网,则直接结束数据的发送逻辑
                if (!poster.isOnline(mContext, mConfig.getOfflineMode())) {
                    logAboutMessageToMixpanel("Not flushing data to Mixpanel because the device is not connected to the internet.");
                    return;
                }

                // getEventsEndPoints 获取 events 请求地址
                sendData(dbAdapter,
                        token,
                        MPDbAdapter.Table.EVENTS,
                        mConfig.getEventsEndpoint());

                sendData(dbAdapter,
                        token,
                        MPDbAdapter.Table.PEOPLE,
                        mConfig.getPeopleEndpoint());
            }

            private void sendData(MPDbAdapter dbAdapter, String token, MPDbAdapter.Table table, String url) {
                final RemoteService poster = getPoster();
                // 获取指定token对应的 DecideMessage
                // DecideChecker 包含了 一个hashmap, 保存 DecideMessages 对象
                DecideMessages decideMessages = mDecideChecker.getDecideMessages(token);

                // 是否包含 automatyic 的数据
                boolean includeAutomaticEvents = true;
                if (decideMessages == null ||
                        decideMessages.isAutomaticEventsEnabled() == null) {
                    includeAutomaticEvents = false;
                }

                //  返回的数据格式 {last_id, data, queueCount};
                // queueCount 表示的总数
                // data 表示具体的数据生成的json, 一条data 最多包含50 条数据
                String[] eventsData = dbAdapter.generateDataString(
                        table,
                        token,
                        includeAutomaticEvents);

                // 指定table中 ,指定token字段的数据的数量
                Integer queueCount = 0;
                if (eventsData != null) {
                    queueCount = Integer.valueOf(eventsData[2]);
                }


                while (eventsData != null && queueCount > 0) {
                    // 获取的N条数据中, 最后一条的id
                    final String lastId = eventsData[0];
                    // 原始数据
                    final String rawMessage = eventsData[1];
                    // 对数据进行base64编码
                    final String encodedData = Base64Coder.encodeString(rawMessage);
                    // 保存数据
                    final Map<String, Object> params = new HashMap<String, Object>();
                    params.put("data", encodedData);
                    if (MPConfig.DEBUG) {
                        params.put("verbose", "1");
                    }

                    // 表示是否删除数据库中的 data
                    boolean deleteEvents = true;
                    byte[] response;
                    try {
                        final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
                        response = poster.performRequest(url, params, socketFactory);
                        if (null == response) {
                            deleteEvents = false;
                            logAboutMessageToMixpanel("Response was null, unexpected failure posting to " + url + ".");
                        } else {
                            deleteEvents = true; // Delete events on any successful post, regardless of 1 or 0 response
                            String parsedResponse;
                            try {
                                parsedResponse = new String(response, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException("UTF not supported on this platform?", e);
                            }
                            if (mFailedRetries > 0) {
                                mFailedRetries = 0;
                                removeMessages(FLUSH_QUEUE, token);
                            }

                            logAboutMessageToMixpanel("Successfully posted to " + url + ": \n" + rawMessage);
                            logAboutMessageToMixpanel("Response was " + parsedResponse);
                        }
                    } catch (final OutOfMemoryError e) {
                        MPLog.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                    } catch (final MalformedURLException e) {
                        MPLog.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                    } catch (final RemoteService.ServiceUnavailableException e) {
                        logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                        mTrackEngageRetryAfter = e.getRetryAfter() * 1000;
                    } catch (final SocketTimeoutException e) {
                        logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    } catch (final IOException e) {
                        logAboutMessageToMixpanel("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    }

                    if (deleteEvents) {
                        logAboutMessageToMixpanel("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table, token, includeAutomaticEvents);
                    } else {
                        removeMessages(FLUSH_QUEUE, token);
                        mTrackEngageRetryAfter = Math.max((long) Math.pow(2, mFailedRetries) * 60000, mTrackEngageRetryAfter);
                        mTrackEngageRetryAfter = Math.min(mTrackEngageRetryAfter, 10 * 60 * 1000); // limit 10 min
                        final Message flushMessage = Message.obtain();
                        flushMessage.what = FLUSH_QUEUE;
                        flushMessage.obj = token;
                        sendMessageDelayed(flushMessage, mTrackEngageRetryAfter);
                        mFailedRetries++;
                        logAboutMessageToMixpanel("Retrying this batch of events in " + mTrackEngageRetryAfter + " ms");
                        break;
                    }

                    eventsData = dbAdapter.generateDataString(table, token, includeAutomaticEvents);
                    if (eventsData != null) {
                        queueCount = Integer.valueOf(eventsData[2]);
                    }
                }
            }

            private JSONObject getDefaultEventProperties()
                    throws JSONException {
                final JSONObject ret = new JSONObject();

                ret.put("mp_lib", "android");
                ret.put("$lib_version", MPConfig.VERSION);

                // For querying together with data from other libraries
                ret.put("$os", "Android");
                ret.put("$os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);

                ret.put("$manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
                ret.put("$brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
                ret.put("$model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);

                try {
                    try {
                        final int servicesAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(mContext);
                        switch (servicesAvailable) {
                            case ConnectionResult.SUCCESS:
                                ret.put("$google_play_services", "available");
                                break;
                            case ConnectionResult.SERVICE_MISSING:
                                ret.put("$google_play_services", "missing");
                                break;
                            case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED:
                                ret.put("$google_play_services", "out of date");
                                break;
                            case ConnectionResult.SERVICE_DISABLED:
                                ret.put("$google_play_services", "disabled");
                                break;
                            case ConnectionResult.SERVICE_INVALID:
                                ret.put("$google_play_services", "invalid");
                                break;
                        }
                    } catch (RuntimeException e) {
                        // Turns out even checking for the service will cause explosions
                        // unless we've set up meta-data
                        ret.put("$google_play_services", "not configured");
                    }

                } catch (NoClassDefFoundError e) {
                    ret.put("$google_play_services", "not included");
                }

                final DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
                ret.put("$screen_dpi", displayMetrics.densityDpi);
                ret.put("$screen_height", displayMetrics.heightPixels);
                ret.put("$screen_width", displayMetrics.widthPixels);

                final String applicationVersionName = mSystemInformation.getAppVersionName();
                if (null != applicationVersionName) {
                    ret.put("$app_version", applicationVersionName);
                    ret.put("$app_version_string", applicationVersionName);
                }

                final Integer applicationVersionCode = mSystemInformation.getAppVersionCode();
                if (null != applicationVersionCode) {
                    ret.put("$app_release", applicationVersionCode);
                    ret.put("$app_build_number", applicationVersionCode);
                }

                final Boolean hasNFC = mSystemInformation.hasNFC();
                if (null != hasNFC)
                    ret.put("$has_nfc", hasNFC.booleanValue());

                final Boolean hasTelephony = mSystemInformation.hasTelephony();
                if (null != hasTelephony)
                    ret.put("$has_telephone", hasTelephony.booleanValue());

                final String carrier = mSystemInformation.getCurrentNetworkOperator();
                if (null != carrier)
                    ret.put("$carrier", carrier);

                final Boolean isWifi = mSystemInformation.isWifiConnected();
                if (null != isWifi)
                    ret.put("$wifi", isWifi.booleanValue());

                final Boolean isBluetoothEnabled = mSystemInformation.isBluetoothEnabled();
                if (isBluetoothEnabled != null)
                    ret.put("$bluetooth_enabled", isBluetoothEnabled);

                final String bluetoothVersion = mSystemInformation.getBluetoothVersion();
                if (bluetoothVersion != null)
                    ret.put("$bluetooth_version", bluetoothVersion);

                return ret;
            }

            private JSONObject prepareEventObject(EventDescription eventDescription) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject sendProperties = getDefaultEventProperties();
                sendProperties.put("token", eventDescription.getToken());
                if (eventProperties != null) {
                    for (final Iterator<?> iter = eventProperties.keys(); iter.hasNext(); ) {
                        final String key = (String) iter.next();
                        sendProperties.put(key, eventProperties.get(key));
                    }
                }
                eventObj.put("event", eventDescription.getEventName());
                eventObj.put("properties", sendProperties);
                eventObj.put("$mp_metadata", eventDescription.getSessionMetadata());
                return eventObj;
            }

            private MPDbAdapter mDbAdapter;
            /**
             * 会保存 key= token , obj = DecideMessages
             */
            private final DecideChecker mDecideChecker;
            private final long mFlushInterval;
            /**
             * 多少秒之后重试,值从http header中获取
             */
            private long mDecideRetryAfter;
            private long mTrackEngageRetryAfter;
            private int mFailedRetries;
        }// AnalyticsMessageHandler

        /**
         * 更新刷新的频率
         */
        private void updateFlushFrequency() {
            // 当前时间
            final long now = System.currentTimeMillis();
            // 刷新次数+1
            final long newFlushCount = mFlushCount + 1;

            // 判断是否是第一次刷新, 如果>0则不是 则需要进行刷新间隔时间的判断
            if (mLastFlushTime > 0) {
                // 刷新间隔
                final long flushInterval = now - mLastFlushTime;
                // 刷新间隔+ 刷新次数* 平均刷新频率
                //  1.  = flushInterval
                //  2.  = flushInterval + lastFlushInterval*1
                final long totalFlushTime = flushInterval +
                        (mAveFlushFrequency * mFlushCount);
                // 总刷新时间 / 刷新次数 =  平均刷新时间
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToMixpanel("Average send frequency approximately " +
                        seconds + " seconds.");
            }

            // 记录当前的刷新时间, 为下一次 判断做准备
            mLastFlushTime = now;
            // 记录刷新次数
            mFlushCount = newFlushCount;
        }

        private final Object mHandlerLock = new Object();
        /**
         * AnalyticsMessageHandler
         * <p>
         * 运行在子线程中
         */
        private Handler mHandler;
        private long mFlushCount = 0;
        /**
         * 平均刷新时间
         */
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;
    }

    public long getTrackEngageRetryAfter() {
        return ((Worker.AnalyticsMessageHandler) mWorker.mHandler).getTrackEngageRetryAfter();
    }
    /////////////////////////////////////////////////////////

    // Used across thread boundaries
    private final Worker mWorker;
    protected final Context mContext;
    protected final MPConfig mConfig;

    // Messages for our thread
    private static final int ENQUEUE_PEOPLE = 0; // submit events and people data
    private static final int ENQUEUE_EVENTS = 1; // push given JSON message to people DB
    private static final int FLUSH_QUEUE = 2; // push given JSON message to events DB
    private static final int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue. This is for testing, or disasters.
    private static final int EMPTY_QUEUES = 6; // Remove any local (and pending to be flushed) events or people updates from the db
    private static final int INSTALL_DECIDE_CHECK = 12; // Run this DecideCheck at intervals until it isDestroyed()
    private static final int REGISTER_FOR_GCM = 13; // Register for GCM using Google Play Services

    private static final String LOGTAG = "MixpanelAPI.Messages";

    private static final Map<Context, AnalyticsMessages> sInstances =
            new HashMap<Context, AnalyticsMessages>();

}
