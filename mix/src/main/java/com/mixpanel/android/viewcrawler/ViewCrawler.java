package com.mixpanel.android.viewcrawler;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.JsonWriter;
import android.util.Log;

import com.mixpanel.android.mpmetrics.MPConfig;
import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.mixpanel.android.mpmetrics.OnMixpanelTweaksUpdatedListener;
import com.mixpanel.android.mpmetrics.ResourceIds;
import com.mixpanel.android.mpmetrics.ResourceReader;
import com.mixpanel.android.mpmetrics.SuperPropertyUpdate;
import com.mixpanel.android.mpmetrics.Tweaks;
import com.mixpanel.android.util.ImageStore;
import com.mixpanel.android.util.JSONUtils;
import com.mixpanel.android.util.MPLog;
import com.mixpanel.android.util.MPPair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLSocketFactory;

/**
 * This class is for internal use by the Mixpanel API, and should
 * not be called directly by your code.
 */
@TargetApi(MPConfig.UI_FEATURES_MIN_API)
public class ViewCrawler implements UpdatesFromMixpanel,
        TrackingDebug,
        ViewVisitor.OnLayoutErrorListener {

    public ViewCrawler(Context context, String token, MixpanelAPI mixpanel, Tweaks tweaks) {
        //获取配置
        mConfig = MPConfig.getInstance(context);

        mContext = context;
        // 集合 保存activity信息
        mEditState = new EditState();
        mTweaks = tweaks;
        //不变的设备信息
        mDeviceInfo = mixpanel.getDeviceInfo();
        //字体的缩放因子,通常与density相等,但是调节系统字体大小之后会改变
        mScaledDensity = Resources.getSystem().getDisplayMetrics().scaledDensity;
        mTweaksUpdatedListeners = Collections.newSetFromMap(new ConcurrentHashMap<OnMixpanelTweaksUpdatedListener, Boolean>());
        // 创建一个带Handler 的Thread
        final HandlerThread thread = new HandlerThread(ViewCrawler.class.getCanonicalName());
        thread.setPriority(Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        // layouterrorListener 就是 布局错误的处理回调
        mMessageThreadHandler = new ViewCrawlerHandler(context, token,
                thread.getLooper(),
                this);

        // 用来处理事件触发之后的逻辑,发送,去抖动
        mDynamicEventTracker = new DynamicEventTracker(mixpanel, mMessageThreadHandler);

        mMixpanel = mixpanel;

        final Application app = (Application) context.getApplicationContext();

        // 为每个Activity注册 加速度传感器 监听回调
        // 将每个Activity 传入到 mEditState中 待用
        // 会区分 模拟器和真机 ,俩者有不同的逻辑
        app.registerActivityLifecycleCallbacks(new LifecycleCallbacks());

        mTweaks.addOnTweakDeclaredListener(new Tweaks.OnTweakDeclaredListener() {
            @Override
            public void onTweakDeclared() {
                // 发送设备信息到编辑页面
                final Message msg = mMessageThreadHandler.
                        obtainMessage(ViewCrawler.MESSAGE_SEND_DEVICE_INFO);
                mMessageThreadHandler.sendMessage(msg);
            }
        });
    }

    /**
     * 用于测试的
     */
    public void connectEditor() {
        final Message message = mMessageThreadHandler.obtainMessage(MESSAGE_CONNECT_TO_EDITOR);
        mMessageThreadHandler.sendMessage(message);
    }

    /**
     * 会在MixpanelApi 构造函数中最后 被调用
     */
    @Override
    public void startUpdates() {
        // 启用MessageThreadHandler, 释放lock ,允许Handler 去处理 msg
        mMessageThreadHandler.start();
        //加载本地Event数据!
        applyPersistedUpdates();
    }

    @Override
    public void applyPersistedUpdates() {
        mMessageThreadHandler.sendMessage(
                mMessageThreadHandler.obtainMessage(MESSAGE_INITIALIZE_CHANGES));
    }

    @Override
    public void storeVariants(JSONArray variants) {
        if (variants != null) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_PERSIST_VARIANTS_RECEIVED);
            msg.obj = variants;
            mMessageThreadHandler.sendMessage(msg);
        }
    }

    @Override
    public Tweaks getTweaks() {
        return mTweaks;
    }

    /**
     * 这里需要等到 MixpanelApi的构造函数 调用了 startUpdates() 方法才行
     * <p>
     * 因为 mMessageThreadHandler 中 在构造函数尾部  调用了lock()方法获取了锁
     * 直到 startUpdates()方法被调用, 里面调用了 mMessageThreadHandler.start() 方法 释放了锁
     * 才允许Handler开始工作
     *
     * @param bindings
     */
    @Override
    public void setEventBindings(JSONArray bindings) {
        if (bindings != null) {
            final Message msg = mMessageThreadHandler.
                    obtainMessage(ViewCrawler.MESSAGE_EVENT_BINDINGS_RECEIVED);
            msg.obj = bindings;
            mMessageThreadHandler.sendMessage(msg);
        }
    }

    @Override
    public void setVariants(JSONArray variants) {
        if (variants != null) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_VARIANTS_RECEIVED);
            msg.obj = variants;
            mMessageThreadHandler.sendMessage(msg);
        }
    }

    @Override
    public void addOnMixpanelTweaksUpdatedListener(OnMixpanelTweaksUpdatedListener listener) {
        if (null == listener) {
            throw new NullPointerException("Listener cannot be null");
        }

        mTweaksUpdatedListeners.add(listener);
    }

    @Override
    public void removeOnMixpanelTweaksUpdatedListener(OnMixpanelTweaksUpdatedListener listener) {
        mTweaksUpdatedListeners.remove(listener);
    }

    @Override
    public void reportTrack(String eventName) {
        final Message m = mMessageThreadHandler.obtainMessage();
        m.what = MESSAGE_SEND_EVENT_TRACKED;
        m.obj = eventName;

        mMessageThreadHandler.sendMessage(m);
    }

    /**
     * 往编辑页面发送布局错误消息
     *
     * @param e
     */
    @Override
    public void onLayoutError(ViewVisitor.LayoutErrorMessage e) {
        final Message m = mMessageThreadHandler.obtainMessage();
        m.what = MESSAGE_SEND_LAYOUT_ERROR;
        m.obj = e;
        mMessageThreadHandler.sendMessage(m);
    }

    private class EmulatorConnector implements Runnable {
        public EmulatorConnector() {
            mStopped = true;
        }

        @Override
        public void run() {
            if (!mStopped) {
                final Message message = mMessageThreadHandler.obtainMessage(MESSAGE_CONNECT_TO_EDITOR);
                mMessageThreadHandler.sendMessage(message);
            }

            mMessageThreadHandler.postDelayed(this, EMULATOR_CONNECT_ATTEMPT_INTERVAL_MILLIS);
        }

        //直接发送消息
        public void start() {
            mStopped = false;
            mMessageThreadHandler.post(this);
        }

        public void stop() {
            mStopped = true;
            mMessageThreadHandler.removeCallbacks(this);
        }

        private volatile boolean mStopped;
    }

    private class LifecycleCallbacks implements
            Application.ActivityLifecycleCallbacks,
            FlipGesture.OnFlipGestureListener {

        public LifecycleCallbacks() {
            // 创建 翻动手势
            mFlipGesture = new FlipGesture(this);
            // 模拟器连接器
            mEmulatorConnector = new EmulatorConnector();
        }

        /**
         * 翻动手势的回调
         */
        @Override
        public void onFlipGesture() {
            mMixpanel.track("$ab_gesture3");
            // 连接到web端的Editor页面
            final Message message = mMessageThreadHandler.obtainMessage(MESSAGE_CONNECT_TO_EDITOR);
            mMessageThreadHandler.sendMessage(message);
        }

        @Override
        public void onActivityCreated(Activity activity, Bundle bundle) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            installConnectionSensor(activity);
            //将当前Activity添加到 EditState
            mEditState.add(activity);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            mEditState.remove(activity);
            uninstallConnectionSensor(activity);
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }

        /**
         * 对传入的activity 设置加速度传感器监听
         * 会判断是否是模拟器类型
         *
         * @param activity
         */
        private void installConnectionSensor(final Activity activity) {
            // 模拟器 && 配置中并不禁止模拟器绑定事件
            if (isInEmulator() && !mConfig.getDisableEmulatorBindingUI()) {
                mEmulatorConnector.start();
                // 配置中并不禁止 手势绑定
            } else if (!mConfig.getDisableGestureBindingUI()) {
                //获取传感器管理器
                final SensorManager sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
                //获取加速度传感器
                final Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                //注册加速度传感器回调
                sensorManager.registerListener(mFlipGesture,
                        accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            }
        }

        /**
         * 解除注册
         *
         * @param activity
         */
        private void uninstallConnectionSensor(final Activity activity) {
            if (isInEmulator() && !mConfig.getDisableEmulatorBindingUI()) {
                mEmulatorConnector.stop();
            } else if (!mConfig.getDisableGestureBindingUI()) {
                final SensorManager sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
                //解除注册
                sensorManager.unregisterListener(mFlipGesture);
            }
        }

        /**
         * 判断当前设备是否是模拟器
         *
         * @return
         */
        private boolean isInEmulator() {
            if (!Build.HARDWARE.toLowerCase().equals("goldfish") && !Build.HARDWARE.toLowerCase().equals("ranchu")) {
                return false;
            }

            if (!Build.BRAND.toLowerCase().startsWith("generic") && !Build.BRAND.toLowerCase().equals("android") && !Build.BRAND.toLowerCase().equals("google")) {
                return false;
            }

            if (!Build.DEVICE.toLowerCase().startsWith("generic")) {
                return false;
            }

            if (!Build.PRODUCT.toLowerCase().contains("sdk")) {
                return false;
            }

            if (!Build.MODEL.toLowerCase(Locale.US).contains("sdk")) {
                return false;
            }

            return true;
        }

        private final FlipGesture mFlipGesture;
        private final EmulatorConnector mEmulatorConnector;
    }

    private class ViewCrawlerHandler extends Handler {

        public ViewCrawlerHandler(Context context, String token, Looper looper,
                                  ViewVisitor.OnLayoutErrorListener layoutErrorListener) {
            super(looper);
            mToken = token;
            mSnapshot = null;

            //获取 清单文件中的 package属性,该属性决定了R文件的名称
            String resourcePackage = mConfig.getResourcePackageName();
            if (null == resourcePackage) {
                resourcePackage = context.getPackageName();
            }
            // 获取资源ID信息
            final ResourceIds resourceIds = new ResourceReader.Ids(resourcePackage, context);
            // 图片保存处理
            mImageStore = new ImageStore(context, "ViewCrawler");
            //TODO 待分析作用
            mProtocol = new EditProtocol(context, resourceIds,
                    mImageStore, layoutErrorListener);

            mOriginalEventBindings = new HashSet<MPPair<String, JSONObject>>();
            mEditorChanges = new HashMap<String, MPPair<String, JSONObject>>();
            mEditorTweaks = new HashMap<String, MPPair<String, Object>>();
            mEditorAssetUrls = new ArrayList<String>();
            mEditorEventBindings = new HashMap<String, MPPair<String, JSONObject>>();
            mAppliedVisualChanges = new HashSet<VariantChange>();
            mAppliedTweaks = new HashSet<VariantTweak>();
            mEmptyExperiments = new HashSet<MPPair<Integer, Integer>>();
            mPersistentEventBindings = new HashSet<MPPair<String, JSONObject>>();
            mSeenExperiments = new HashSet<MPPair<Integer, Integer>>();
            mStartLock = new ReentrantLock();
            //等待 start()调用
            mStartLock.lock();
        }

        public void start() {
            mStartLock.unlock();
        }

        @Override
        public void handleMessage(Message msg) {
            //同步锁
            mStartLock.lock();
            try {

                final int what = msg.what;
                switch (what) {
                    // 在MixpanelApi 的 构造函数中 被调用
                    case MESSAGE_INITIALIZE_CHANGES:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_INITIALIZE_CHANGES,loadKnownChanges");
                        // 加载本地的缓存数据,例如 AB ,tweaks,Event bindings 等信息
                        loadKnownChanges();
                        break;
                    case MESSAGE_CONNECT_TO_EDITOR:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_CONNECT_TO_EDITOR,connectToEditor");
                        //连接到web端的Editor界面
                        connectToEditor();
                        break;
                    case MESSAGE_SEND_DEVICE_INFO:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_SEND_DEVICE_INFO,sendDeviceInfo");
                        sendDeviceInfo();
                        break;
                    case MESSAGE_SEND_STATE_FOR_EDITING:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_SEND_STATE_FOR_EDITING,sendSnapshot");
                        sendSnapshot((JSONObject) msg.obj);
                        break;
                    case MESSAGE_SEND_EVENT_TRACKED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_SEND_EVENT_TRACKED,sendReportTrackToEditor");
                        sendReportTrackToEditor((String) msg.obj);
                        break;
                    case MESSAGE_SEND_LAYOUT_ERROR:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_SEND_LAYOUT_ERROR,sendLayoutError");
                        sendLayoutError((ViewVisitor.LayoutErrorMessage) msg.obj);
                        break;
                    case MESSAGE_VARIANTS_RECEIVED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_VARIANTS_RECEIVED,handleVariantsReceived");
                        // 处理通过decide 接口获取到的 variants 数据

                        handleVariantsReceived((JSONArray) msg.obj);
                        break;
                    case MESSAGE_HANDLE_EDITOR_CHANGES_RECEIVED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_HANDLE_EDITOR_CHANGES_RECEIVED,handleEditorChangeReceived");
                        handleEditorChangeReceived((JSONObject) msg.obj);
                        break;
                    case MESSAGE_EVENT_BINDINGS_RECEIVED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_EVENT_BINDINGS_RECEIVED,handleEventBindingsReceived");
                        // 处理从服务器主动获取的 event bindings 事件
                        // 通过decide接口获取到的
                        // DecideMessages.reportResults
                        // 这里的 event binding 数据来自 decide 接口......
                        handleEventBindingsReceived((JSONArray) msg.obj);
                        break;
                    case MESSAGE_HANDLE_EDITOR_BINDINGS_RECEIVED:
                        MPLog.v("ViewCrawlerHandler",
                                "MESSAGE_HANDLE_EDITOR_BINDINGS_RECEIVED,handleEditorBindingsReceived");
                        //
                        handleEditorBindingsReceived((JSONObject) msg.obj);
                        break;
                    case MESSAGE_HANDLE_EDITOR_CHANGES_CLEARED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_HANDLE_EDITOR_CHANGES_CLEARED,handleEditorBindingsCleared");
                        handleEditorBindingsCleared((JSONObject) msg.obj);
                        break;
                    case MESSAGE_HANDLE_EDITOR_TWEAKS_RECEIVED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_HANDLE_EDITOR_TWEAKS_RECEIVED,handleEditorTweaksReceived");
                        handleEditorTweaksReceived((JSONObject) msg.obj);
                        break;
                    case MESSAGE_HANDLE_EDITOR_CLOSED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_HANDLE_EDITOR_CLOSED,handleEditorClosed");
                        handleEditorClosed();
                        break;
                    case MESSAGE_PERSIST_VARIANTS_RECEIVED:
                        MPLog.v("ViewCrawlerHandler", "MESSAGE_PERSIST_VARIANTS_RECEIVED,persistVariants");
                        // 保存variants到本地(SP)
                        persistVariants((JSONArray) msg.obj);
                        break;
                }
            } finally {
                mStartLock.unlock();
            }
        }

        /**
         * Load the experiment ids and variants already in persistent storage into
         * into our set of seen experiments, so we don't double track them.
         * <p>
         * Load stored changes (AB, tweaks and event bindings) from persistent storage.
         * <p>
         * 加载缓存在本地的事件到内存中,避免重复抓取
         */
        private void loadKnownChanges() {
            final SharedPreferences preferences = getSharedPreferences();
            final String storedChanges = preferences.getString(SHARED_PREF_CHANGES_KEY,
                    null);
            //获取缓存的 绑定事件
            final String storedBindings = preferences.getString(SHARED_PREF_BINDINGS_KEY,
                    null);

            mAppliedVisualChanges.clear();
            mAppliedTweaks.clear();
            mSeenExperiments.clear();
            // 解析variants 字符串
            loadVariants(storedChanges, false);
            // 清空本地的event binding 数据
            mPersistentEventBindings.clear();


            // 解析json,保存到成员变量中去备用(加载到内存中)
            // 加载Event事件
            loadEventBindings(storedBindings);

            applyVariantsAndEventBindings();
        }

        /**
         * 保存 variants 到SP
         *
         * @param variants
         */
        private void persistVariants(JSONArray variants) {
            final SharedPreferences preferences = getSharedPreferences();
            final SharedPreferences.Editor editor = preferences.edit();
            editor.putString(SHARED_PREF_CHANGES_KEY, variants.toString());
            editor.apply();
        }


        /**
         * 将 variants 进行解析加载到内存中,保存在不同的集合中
         *
         * @param variants
         * @param newVariants
         */
        private void loadVariants(String variants, boolean newVariants) {
            if (null != variants) {
                try {
                    // 转换成JsonArray
                    final JSONArray variantsJson = new JSONArray(variants);

                    final int variantsLength = variantsJson.length();
                    // 遍历每个 variant
                    for (int variantIx = 0; variantIx < variantsLength; variantIx++) {

                        final JSONObject nextVariant = variantsJson.
                                getJSONObject(variantIx);
                        // 获取id
                        final int variantIdPart = nextVariant.getInt("id");
                        // 获取experiment_id
                        final int experimentIdPart = nextVariant.getInt("experiment_id");
                        // 保存上面获取到的俩个id
                        final MPPair<Integer, Integer> variantId =
                                new MPPair<Integer, Integer>(experimentIdPart, variantIdPart);

                        // 获取actions
                        final JSONArray actions = nextVariant.getJSONArray("actions");
                        final int actionsLength = actions.length();
                        for (int i = 0; i < actionsLength; i++) {
                            final JSONObject change = actions.getJSONObject(i);
                            // 从change 中 获取 target_activity , 为空则返回null
                            final String targetActivity = JSONUtils.
                                    optionalStringKey(change,
                                            "target_activity");
                            final String name = change.getString("name");
                            // 改变的地方
                            final VariantChange variantChange =
                                    new VariantChange(name,
                                            targetActivity,
                                            change,
                                            variantId);

                            mAppliedVisualChanges.add(variantChange);
                        }

                        final JSONArray tweaks = nextVariant.getJSONArray("tweaks");
                        final int tweaksLength = tweaks.length();
                        for (int i = 0; i < tweaksLength; i++) {
                            final JSONObject tweakDesc = tweaks.getJSONObject(i);
                            final String tweakName = tweakDesc.getString("name");
                            final VariantTweak variantTweak = new VariantTweak(tweakName, tweakDesc, variantId);
                            mAppliedTweaks.add(variantTweak);
                        }

                        if (!newVariants) {
                            mSeenExperiments.add(variantId);
                        }

                        if (tweaksLength == 0 && actionsLength == 0) {
                            mEmptyExperiments.add(variantId);
                        }
                    }
                } catch (JSONException e) {
                    MPLog.i(LOGTAG, "JSON error when loading ab tests / tweaks, clearing persistent memory", e);
                    final SharedPreferences preferences = getSharedPreferences();
                    final SharedPreferences.Editor editor = preferences.edit();
                    editor.remove(SHARED_PREF_CHANGES_KEY);
                    editor.apply();
                }
            }
        }

        /**
         * 解析json, 保存到mPersisitentEventBindings[Set]中
         * <p>
         * activityName  -----  事件
         *
         * @param eventBindings
         */
        private void loadEventBindings(String eventBindings) {
            if (null != eventBindings) {
                try {
                    final JSONArray bindings = new JSONArray(eventBindings);
                    mPersistentEventBindings.clear();
                    for (int i = 0; i < bindings.length(); i++) {
                        final JSONObject event = bindings.getJSONObject(i);
                        final String targetActivity = JSONUtils.optionalStringKey(event, "target_activity");
                        mPersistentEventBindings.add(
                                new MPPair<String, JSONObject>(targetActivity, event));
                    }
                } catch (final JSONException e) {
                    MPLog.i(LOGTAG, "JSON error when loading event bindings, clearing persistent memory", e);
                    final SharedPreferences preferences = getSharedPreferences();
                    final SharedPreferences.Editor editor = preferences.edit();
                    editor.remove(SHARED_PREF_BINDINGS_KEY);
                    editor.apply();
                }
            }
        }

        /**
         * Try to connect to the remote interactive editor,
         * if a connection does not already exist.
         * 连接到远程交互页面,会判断是否已经存在连接
         */
        private void connectToEditor() {
            MPLog.v(LOGTAG, "connecting to editor");
            //判断是否已经存在一个链接对象
            if (mEditorConnection != null && mEditorConnection.isValid()) {
                MPLog.v(LOGTAG, "There is already a valid connection to an events editor.");
                return;
            }

            final SSLSocketFactory socketFactory = mConfig.getSSLSocketFactory();
            if (null == socketFactory) {
                MPLog.v(LOGTAG, "SSL is not available on this device, no connection will be attempted to the events editor.");
                return;
            }

            final String url = MPConfig.getInstance(mContext).getEditorUrl() + mToken;
//            final String url = MPConfig.getInstance(mContext).getEditorUrl();
            try {
                //创建一个sslSocket
                final Socket sslSocket = socketFactory.createSocket();
                //创建连接管理对象
                mEditorConnection = new EditorConnection(
                        new URI(url),
                        new Editor(),
                        sslSocket);
            } catch (final URISyntaxException e) {
                MPLog.e(LOGTAG, "Error parsing URI " + url + " for editor websocket", e);
            } catch (final EditorConnection.EditorConnectionException e) {
                MPLog.e(LOGTAG, "Error connecting to URI " + url, e);
            } catch (final IOException e) {
                MPLog.i(LOGTAG, "Can't create SSL Socket to connect to editor service", e);
            }
        }

        /**
         * Send a string error message to the connected web UI.
         */
        private void sendError(String errorMessage) {
            if (mEditorConnection == null || !mEditorConnection.isValid() || !mEditorConnection.isConnected()) {
                return;
            }

            final JSONObject errorObject = new JSONObject();
            try {
                errorObject.put("error_message", errorMessage);
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Apparently impossible JSONException", e);
            }

            final OutputStreamWriter writer = new OutputStreamWriter(mEditorConnection.getBufferedOutputStream());
            try {
                writer.write("{\"type\": \"error\", ");
                writer.write("\"payload\": ");
                writer.write(errorObject.toString());
                writer.write("}");
            } catch (final IOException e) {
                MPLog.e(LOGTAG, "Can't write error message to editor", e);
            } finally {
                try {
                    writer.close();
                } catch (final IOException e) {
                    MPLog.e(LOGTAG, "Could not close output writer to editor", e);
                }
            }
        }

        /**
         * Report on device info to the connected web UI.
         */
        private void sendDeviceInfo() {
            if (mEditorConnection == null || !mEditorConnection.isValid() || !mEditorConnection.isConnected()) {
                return;
            }

            final OutputStream out = mEditorConnection.getBufferedOutputStream();
            final JsonWriter j = new JsonWriter(new OutputStreamWriter(out));

            try {
                j.beginObject();
                j.name("type").value("device_info_response");
                j.name("payload").beginObject();
                j.name("device_type").value("Android");
                j.name("device_name").value(Build.BRAND + "/" + Build.MODEL);
                j.name("scaled_density").value(mScaledDensity);
                for (final Map.Entry<String, String> entry : mDeviceInfo.entrySet()) {
                    j.name(entry.getKey()).value(entry.getValue());
                }

                final Map<String, Tweaks.TweakValue> tweakDescs = mTweaks.getAllValues();
                j.name("tweaks").beginArray();
                for (Map.Entry<String, Tweaks.TweakValue> tweak : tweakDescs.entrySet()) {
                    final Tweaks.TweakValue desc = tweak.getValue();
                    final String tweakName = tweak.getKey();
                    j.beginObject();
                    j.name("name").value(tweakName);
                    j.name("minimum").value(desc.getMinimum());
                    j.name("maximum").value(desc.getMaximum());
                    switch (desc.type) {
                        case Tweaks.BOOLEAN_TYPE:
                            j.name("type").value("boolean");
                            j.name("value").value(desc.getBooleanValue());
                            j.name("default").value((Boolean) desc.getDefaultValue());
                            break;
                        case Tweaks.DOUBLE_TYPE:
                            j.name("type").value("number");
                            j.name("encoding").value("d");
                            j.name("value").value(desc.getNumberValue().doubleValue());
                            j.name("default").value(((Number) desc.getDefaultValue()).doubleValue());
                            break;
                        case Tweaks.LONG_TYPE:
                            j.name("type").value("number");
                            j.name("encoding").value("l");
                            j.name("value").value(desc.getNumberValue().longValue());
                            j.name("default").value(((Number) desc.getDefaultValue()).longValue());
                            break;
                        case Tweaks.STRING_TYPE:
                            j.name("type").value("string");
                            j.name("value").value(desc.getStringValue());
                            j.name("default").value((String) desc.getDefaultValue());
                            break;
                        default:
                            MPLog.wtf(LOGTAG, "Unrecognized Tweak Type " + desc.type + " encountered.");
                    }
                    j.endObject();
                }
                j.endArray();
                j.endObject(); // payload
                j.endObject();
            } catch (final IOException e) {
                MPLog.e(LOGTAG, "Can't write device_info to server", e);
            } finally {
                try {
                    j.close();
                } catch (final IOException e) {
                    MPLog.e(LOGTAG, "Can't close websocket writer", e);
                }
            }
        }

        /**
         * Send a snapshot response,
         * with crawled views and screenshot image, to the connected web UI.
         */
        private void sendSnapshot(JSONObject message) {
            final long startSnapshot = System.currentTimeMillis();
            try {
                //
                final JSONObject payload = message.getJSONObject("payload");
                if (payload.has("config")) {
                    //解析config,并返回ViewSnapShot
                    mSnapshot = mProtocol.readSnapshotConfig(payload);
                    MPLog.v(LOGTAG, "Initializing snapshot with configuration");
                }
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Payload with snapshot config required with snapshot request", e);
                sendError("Payload with snapshot config required with snapshot request");
                return;
            } catch (final EditProtocol.BadInstructionsException e) {
                MPLog.e(LOGTAG, "Editor sent malformed message with snapshot request", e);
                sendError(e.getMessage());
                return;
            }

            //如果ViewSnapShot没有被创建,说明没有从Web处获得Config,直接报错
            if (null == mSnapshot) {
                sendError("No snapshot configuration (or a malformed snapshot configuration) was sent.");
                MPLog.w(LOGTAG, "Mixpanel editor is misconfigured, sent a snapshot request without a valid configuration.");
                return;
            }
            // ELSE config is valid:

            final OutputStream out = mEditorConnection.getBufferedOutputStream();
            final OutputStreamWriter writer = new OutputStreamWriter(out);

            try {
                writer.write("{");
                writer.write("\"type\": \"snapshot_response\",");
                writer.write("\"payload\": {");
                {
                    writer.write("\"activities\":");
                    writer.flush();
                    mSnapshot.snapshots(mEditState, out);
                }

                final long snapshotTime = System.currentTimeMillis() - startSnapshot;
                writer.write(",\"snapshot_time_millis\": ");
                writer.write(Long.toString(snapshotTime));

                writer.write("}"); // } payload
                writer.write("}"); // } whole message
            } catch (final IOException e) {
                MPLog.e(LOGTAG, "Can't write snapshot request to server", e);
            } finally {
                try {
                    writer.close();
                } catch (final IOException e) {
                    MPLog.e(LOGTAG, "Can't close writer.", e);
                }
            }
        }

        /**
         * Report that a track has occurred to the connected web UI.
         * <p>
         * 通知关联的 web ui 界面, 有一个事件被抓取到了
         */
        private void sendReportTrackToEditor(String eventName) {

            // 判断连接管理类 是否被创建了 是否有效  是否处于连接状态
            if (mEditorConnection == null ||
                    !mEditorConnection.isValid() ||
                    !mEditorConnection.isConnected()) {
                return;
            }

            final OutputStream out = mEditorConnection.getBufferedOutputStream();
            final OutputStreamWriter writer = new OutputStreamWriter(out);
            //创建一个 json 编辑对象,直接输出给流
            final JsonWriter j = new JsonWriter(writer);

            try {

                j.beginObject();
                j.name("type").value("track_message");
                j.name("payload");
                {
                    j.beginObject();
                    j.name("event_name").value(eventName);
                    j.endObject();
                }
                // 子对象 { "type"   :"track_message",
                //         "payload":{
                //              "event_name":"xxxx"
                //         }
                //       }


                j.endObject();
                j.flush();
            } catch (final IOException e) {
                MPLog.e(LOGTAG, "Can't write track_message to server", e);
            } finally {
                try {
                    j.close();
                } catch (final IOException e) {
                    MPLog.e(LOGTAG, "Can't close writer.", e);
                }
            }
        }

        private void sendLayoutError(ViewVisitor.LayoutErrorMessage exception) {
            if (mEditorConnection == null || !mEditorConnection.isValid() || !mEditorConnection.isConnected()) {
                return;
            }

            final OutputStream out = mEditorConnection.getBufferedOutputStream();
            final OutputStreamWriter writer = new OutputStreamWriter(out);
            final JsonWriter j = new JsonWriter(writer);

            try {
                j.beginObject();
                j.name("type").value("layout_error");
                j.name("exception_type").value(exception.getErrorType());
                j.name("cid").value(exception.getName());
                j.endObject();
            } catch (final IOException e) {
                MPLog.e(LOGTAG, "Can't write track_message to server", e);
            } finally {
                try {
                    j.close();
                } catch (final IOException e) {
                    MPLog.e(LOGTAG, "Can't close writer.", e);
                }
            }
        }

        /**
         * Accept and apply a change from the connected UI.
         */
        private void handleEditorChangeReceived(JSONObject changeMessage) {
            try {
                final JSONObject payload = changeMessage.getJSONObject("payload");
                final JSONArray actions = payload.getJSONArray("actions");

                for (int i = 0; i < actions.length(); i++) {
                    final JSONObject change = actions.getJSONObject(i);
                    final String targetActivity = JSONUtils.optionalStringKey(change, "target_activity");
                    final String name = change.getString("name");
                    mEditorChanges.put(name, new MPPair<String, JSONObject>(targetActivity, change));
                }

                applyVariantsAndEventBindings();
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Bad change request received", e);
            }
        }

        /**
         * Remove a change from the connected UI.
         */
        private void handleEditorBindingsCleared(JSONObject clearMessage) {
            try {
                final JSONObject payload = clearMessage.getJSONObject("payload");
                final JSONArray actions = payload.getJSONArray("actions");

                // Don't throw any JSONExceptions after this, or you'll leak the item
                for (int i = 0; i < actions.length(); i++) {
                    final String changeId = actions.getString(i);
                    mEditorChanges.remove(changeId);
                }
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Bad clear request received", e);
            }

            applyVariantsAndEventBindings();
        }

        private void handleEditorTweaksReceived(JSONObject tweaksMessage) {
            try {
                final JSONObject payload = tweaksMessage.getJSONObject("payload");
                final JSONArray tweaks = payload.getJSONArray("tweaks");
                final int length = tweaks.length();
                for (int i = 0; i < length; i++) {
                    final JSONObject tweakDesc = tweaks.getJSONObject(i);
                    MPPair<String, Object> tweak = mProtocol.readTweak(tweakDesc);
                    mEditorTweaks.put(tweak.first, tweak);
                }
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Bad tweaks received", e);
            } catch (final EditProtocol.BadInstructionsException e) {
                MPLog.e(LOGTAG, "Bad tweaks received", e);
            }

            applyVariantsAndEventBindings();
        }

        /**
         * Accept and apply variant changes from a non-interactive source.
         */
        private void handleVariantsReceived(JSONArray variants) {
            // 往SP中保存 variants
            persistVariants(variants);
            // 加载到内存中
            loadVariants(variants.toString(), true);
            applyVariantsAndEventBindings();
        }

        /**
         * Accept and apply a persistent event binding from a non-interactive source.
         * <p>
         * 接收并应用 来自本地持久化Sp中的 event binding
         */
        private void handleEventBindingsReceived(JSONArray eventBindings) {
            final SharedPreferences preferences = getSharedPreferences();
            final SharedPreferences.Editor editor = preferences.edit();
            editor.putString(SHARED_PREF_BINDINGS_KEY, eventBindings.toString());
            editor.apply();
            // 将event 数据加载到内存中
            loadEventBindings(eventBindings.toString());
            // 绑定事件等
            applyVariantsAndEventBindings();
        }

        /**
         * Accept and apply a temporary event binding from the connected UI.
         */
        private void handleEditorBindingsReceived(JSONObject message) {
            final JSONArray eventBindings;
            try {
                final JSONObject payload = message.getJSONObject("payload");
                eventBindings = payload.getJSONArray("events");
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Bad event bindings received", e);
                return;
            }

            final int eventCount = eventBindings.length();

            mEditorEventBindings.clear();
            if (!mPersistentEventBindings.isEmpty() && mOriginalEventBindings.isEmpty()) {
                mOriginalEventBindings.addAll(mPersistentEventBindings);
                for (MPPair<String, JSONObject> eventBinding : mPersistentEventBindings) {
                    try {
                        //将事件信息 保存到 mEditorEventBindings 中
                        mEditorEventBindings.put(
                                eventBinding.second.get("path").toString(),
                                eventBinding);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
                //清空临时存储
                mPersistentEventBindings.clear();
            }
            for (int i = 0; i < eventCount; i++) {
                try {
                    final JSONObject event = eventBindings.getJSONObject(i);
                    final String targetActivity = JSONUtils.optionalStringKey(event, "target_activity");
                    mEditorEventBindings.put(event.get("path").toString(), new MPPair<String, JSONObject>(targetActivity, event));
                } catch (final JSONException e) {
                    MPLog.e(LOGTAG, "Bad event binding received from editor in " + eventBindings.toString(), e);
                }
            }

            applyVariantsAndEventBindings();
        }

        /**
         * Clear state associated with the editor now that the editor is gone.
         */
        private void handleEditorClosed() {
            mEditorChanges.clear();
            mEditorEventBindings.clear();
            mEditorTweaks.clear();
            mPersistentEventBindings.addAll(mOriginalEventBindings);
            mOriginalEventBindings.clear();

            // Free (or make available) snapshot memory
            mSnapshot = null;

            MPLog.v(LOGTAG, "Editor closed- freeing snapshot");

            applyVariantsAndEventBindings();
            for (final String assetUrl : mEditorAssetUrls) {
                mImageStore.deleteStorage(assetUrl);
            }
        }

        /**
         * Reads our JSON-stored edits from memory and submits them to our EditState. Overwrites
         * any existing edits at the time that it is run.
         * <p>
         * applyVariantsAndEventBindings should be called any time we load new edits, event bindings,
         * or tweaks from disk or when we receive new edits from the interactive UI editor.
         * <p>
         * Changes and event bindings from our persistent storage and temporary changes
         * received from interactive editing will all be submitted to our EditState, tweaks
         * will be updated, and experiment statuses will be tracked.
         * <p>
         * 每当从磁盘中加载新的 [edits],[event binding],[tweaks] ,或者从交互式UI编辑器 中收到新的[edits]时
         * 都应该调用 applyVariantsAndEventBindings
         * <p>
         * 来自持久化存储中的[Changes] [event bindings] 以及 通过交互式编辑得到的 [temporary changes] 都会提交给 EditState
         */
        private void applyVariantsAndEventBindings() {
            //保存 activityName  - ViewVisitor 的关系
            //ViewVisitor 由event 事件信解析而来
            final List<MPPair<String, ViewVisitor>> newVisitors =
                    new ArrayList<MPPair<String, ViewVisitor>>();

            final Set<MPPair<Integer, Integer>> toTrack =
                    new HashSet<MPPair<Integer, Integer>>();

            Set<String> updatedTweaks = new HashSet<>();

            // 对variants 中的 acitons 进行处理
            {
                for (VariantChange changeInfo : mAppliedVisualChanges) {
                    try {
                        // 解析成 Edit
                        final EditProtocol.Edit edit =
                                mProtocol.readEdit(changeInfo.change);
                        // 添加到 集合
                        newVisitors.add(new MPPair<String, ViewVisitor>(
                                changeInfo.activityName,
                                edit.visitor));
                        if (!mSeenExperiments.contains(changeInfo.variantId)) {
                            toTrack.add(changeInfo.variantId);
                        }
                    } catch (final EditProtocol.CantGetEditAssetsException e) {
                        MPLog.v(LOGTAG, "Can't load assets for an edit, won't apply the change now", e);
                    } catch (final EditProtocol.InapplicableInstructionsException e) {
                        MPLog.i(LOGTAG, e.getMessage());
                    } catch (final EditProtocol.BadInstructionsException e) {
                        MPLog.e(LOGTAG, "Bad persistent change request cannot be applied.", e);
                    }
                }
            }

            // A/B 测试
            {
                for (VariantTweak tweakInfo : mAppliedTweaks) {
                    try {
                        final MPPair<String, Object> tweakValue = mProtocol.readTweak(tweakInfo.tweak);

                        if (!mSeenExperiments.contains(tweakInfo.variantId)) {
                            toTrack.add(tweakInfo.variantId);
                            updatedTweaks.add(tweakValue.first);
                        } else if (mTweaks.isNewValue(tweakValue.first, tweakValue.second)) {
                            updatedTweaks.add(tweakValue.first);
                        }

                        if (!mTweaks.getAllValues().containsKey(tweakValue.first)) {
                            Tweaks.TweakValue notDeclaredTweak = Tweaks.TweakValue.fromJson(tweakInfo.tweak);
                            mTweaks.addUndeclaredTweak(tweakValue.first, notDeclaredTweak);
                        } else {
                            mTweaks.set(tweakValue.first, tweakValue.second);
                        }
                    } catch (EditProtocol.BadInstructionsException e) {
                        MPLog.e(LOGTAG, "Bad editor tweak cannot be applied.", e);
                    }
                }

                if (mAppliedTweaks.size() == 0) { // there are no new tweaks, so reset to default values
                    final Map<String, Tweaks.TweakValue> tweakDefaults = mTweaks.getDefaultValues();
                    for (Map.Entry<String, Tweaks.TweakValue> tweak : tweakDefaults.entrySet()) {
                        final Tweaks.TweakValue tweakValue = tweak.getValue();
                        final String tweakName = tweak.getKey();
                        if (mTweaks.isNewValue(tweakName, tweakValue.getValue())) {
                            mTweaks.set(tweakName, tweakValue.getValue());
                            updatedTweaks.add(tweakName);
                        }
                    }
                }
            }

            {
                for (MPPair<String, JSONObject> changeInfo : mEditorChanges.values()) {
                    try {
                        final EditProtocol.Edit edit =
                                mProtocol.readEdit(changeInfo.second);
                        newVisitors.add(new MPPair<String, ViewVisitor>(changeInfo.first, edit.visitor));
                        mEditorAssetUrls.addAll(edit.imageUrls);
                    } catch (final EditProtocol.CantGetEditAssetsException e) {
                        MPLog.v(LOGTAG, "Can't load assets for an edit, won't apply the change now", e);
                    } catch (final EditProtocol.InapplicableInstructionsException e) {
                        MPLog.i(LOGTAG, e.getMessage());
                    } catch (final EditProtocol.BadInstructionsException e) {
                        MPLog.e(LOGTAG, "Bad editor change request cannot be applied.", e);
                    }
                }
            }

            {
                for (MPPair<String, Object> tweak : mEditorTweaks.values()) {
                    if (mTweaks.isNewValue(tweak.first, tweak.second)) {
                        updatedTweaks.add(tweak.first);
                    }
                    mTweaks.set(tweak.first, tweak.second);
                }
            }

            {
                if (mEditorEventBindings.size() == 0 && mOriginalEventBindings.size() == 0) {
                    //activityName -  事件信息
                    //mPersistentEventBindings 是未解析的 eventbinding事件
                    // 需要进行处理
                    for (MPPair<String, JSONObject> changeInfo : mPersistentEventBindings) {
                        try {
                            // 创建ViewVisitor ,每个事件对应一个ViewVisitor
                            // 对应 EventTriggeringVisitor 对象(继承自ViewVisitor)
                            //
                            final ViewVisitor visitor =
                                    mProtocol.readEventBinding(changeInfo.second,
                                            mDynamicEventTracker);
                            // 创建 activityName -  ViewVisitor 关系
                            newVisitors.add(
                                    new MPPair<String, ViewVisitor>(changeInfo.first,
                                            visitor));
                        } catch (final EditProtocol.InapplicableInstructionsException e) {
                            MPLog.i(LOGTAG, e.getMessage());
                        } catch (final EditProtocol.BadInstructionsException e) {
                            MPLog.e(LOGTAG, "Bad persistent event binding cannot be applied.", e);
                        }
                    }
                }
            }

            {
                for (MPPair<String, JSONObject> changeInfo :
                        mEditorEventBindings.values()) {
                    try {
                        final ViewVisitor visitor =
                                mProtocol.readEventBinding(changeInfo.second, mDynamicEventTracker);
                        newVisitors.add(new MPPair<String, ViewVisitor>(changeInfo.first, visitor));
                    } catch (final EditProtocol.InapplicableInstructionsException e) {
                        MPLog.i(LOGTAG, e.getMessage());
                    } catch (final EditProtocol.BadInstructionsException e) {
                        MPLog.e(LOGTAG, "Bad editor event binding cannot be applied.", e);
                    }
                }
            }

            //ActivityName 对应 该Activity下的 ViewVisitor集合
            final Map<String, List<ViewVisitor>> editMap = new HashMap<String, List<ViewVisitor>>();
            final int totalEdits = newVisitors.size();
            for (int i = 0; i < totalEdits; i++) {
                // ActivityName  - ViewVisitor
                final MPPair<String, ViewVisitor> next = newVisitors.get(i);
                //保存ViewVisitor
                final List<ViewVisitor> mapElement;
                //判断当前editMap是否存在 ActivityName
                if (editMap.containsKey(next.first)) {
                    //取出对应的List
                    mapElement = editMap.get(next.first);
                } else {
                    //由于不存在,所以创建并放入map中
                    mapElement = new ArrayList<ViewVisitor>();
                    editMap.put(next.first, mapElement);
                }
                //将ActivityName对应的ViewVisitor   放入List中
                mapElement.add(next.second);
            }
            //开启循环,将 ViewVisitor传入
            mEditState.setEdits(editMap);

            Log.e("ViewCrawlerHandler", "mEditState.setEdits(editMap),key size =  " + editMap.keySet().size());
            for (MPPair<Integer, Integer> emptyExperiment : mEmptyExperiments) {
                if (!mSeenExperiments.contains(emptyExperiment)) {
                    toTrack.add(emptyExperiment);
                }
            }
            mSeenExperiments.addAll(toTrack);
            trackSeenExperiments(toTrack);
            mEmptyExperiments.clear();
            if (updatedTweaks.size() > 0) {
                for (OnMixpanelTweaksUpdatedListener listener : mTweaksUpdatedListeners) {
                    listener.onMixpanelTweakUpdated(updatedTweaks);
                }
            }
        }

        private void trackSeenExperiments(Set<MPPair<Integer, Integer>> toTrack) {
            if (toTrack != null && toTrack.size() > 0) {
                final JSONObject variantObject = new JSONObject();

                try {
                    for (MPPair<Integer, Integer> variant : toTrack) {
                        final int experimentId = variant.first;
                        final int variantId = variant.second;

                        final JSONObject trackProps = new JSONObject();
                        trackProps.put("$experiment_id", experimentId);
                        trackProps.put("$variant_id", variantId);

                        variantObject.put(Integer.toString(experimentId), variantId);

                        mMixpanel.getPeople().merge("$experiments", variantObject);
                        mMixpanel.updateSuperProperties(new SuperPropertyUpdate() {
                            public JSONObject update(JSONObject in) {
                                try {
                                    in.put("$experiments", variantObject);
                                } catch (JSONException e) {
                                    MPLog.wtf(LOGTAG, "Can't write $experiments super property", e);
                                }
                                return in;
                            }
                        });

                        mMixpanel.track("$experiment_started", trackProps);
                    }
                } catch (JSONException e) {
                    MPLog.wtf(LOGTAG, "Could not build JSON for reporting experiment start", e);
                }
            }
        }

        private SharedPreferences getSharedPreferences() {
            final String sharedPrefsName = SHARED_PREF_EDITS_FILE + mToken;
            return mContext.getSharedPreferences(sharedPrefsName, Context.MODE_PRIVATE);
        }

        /**
         * 与编辑页面的 管理类
         */
        private EditorConnection mEditorConnection;
        private ViewSnapshot mSnapshot;
        private final String mToken;
        /**
         * TODO 为什么这里需要一个 Lock.....
         */
        private final Lock mStartLock;
        private final EditProtocol mProtocol;
        private final ImageStore mImageStore;

        private final Map<String, MPPair<String, JSONObject>> mEditorChanges;
        private final Map<String, MPPair<String, Object>> mEditorTweaks;
        private final List<String> mEditorAssetUrls;
        /**
         * Path -   ActivityName+事件信息
         */
        private final Map<String, MPPair<String, JSONObject>> mEditorEventBindings;
        /**
         * variants 中的 actions字段
         * <p>
         * 在loadVariants 中被赋值
         * <p>
         * 可视化的改变 , AB测试
         */
        private final Set<VariantChange> mAppliedVisualChanges;
        private final Set<VariantTweak> mAppliedTweaks;
        private final Set<MPPair<Integer, Integer>> mEmptyExperiments;
        /**
         * 绑定的Event事件
         * <p>
         * 由event json 解析而来... 路径信息
         *
         * <p>
         * ActivityName  -  事件信息
         */
        private final Set<MPPair<String, JSONObject>> mPersistentEventBindings;
        private final Set<MPPair<String, JSONObject>> mOriginalEventBindings;
        private final Set<MPPair<Integer, Integer>> mSeenExperiments;
    }

    /**
     * 与web进行交互的一些行为,通过MessageThreadHandler将具体的执行的代码切换到子线程
     */
    private class Editor implements EditorConnection.Editor {
        //{"type":"snapshot_request","payload":{"image_hash":"b5f1d4867209bb3516db082fdff48040"}}
        @Override
        public void sendSnapshot(JSONObject message) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_SEND_STATE_FOR_EDITING);
            msg.obj = message;
            mMessageThreadHandler.sendMessage(msg);
        }

        @Override
        public void performEdit(JSONObject message) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_HANDLE_EDITOR_CHANGES_RECEIVED);
            msg.obj = message;
            mMessageThreadHandler.sendMessage(msg);
        }

        @Override
        public void clearEdits(JSONObject message) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_HANDLE_EDITOR_CHANGES_CLEARED);
            msg.obj = message;
            mMessageThreadHandler.sendMessage(msg);
        }

        @Override
        public void setTweaks(JSONObject message) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_HANDLE_EDITOR_TWEAKS_RECEIVED);
            msg.obj = message;
            mMessageThreadHandler.sendMessage(msg);
        }

        @Override
        public void bindEvents(JSONObject message) {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_HANDLE_EDITOR_BINDINGS_RECEIVED);
            msg.obj = message;
            mMessageThreadHandler.sendMessage(msg);
        }

        /**
         * 发送设备信息到 后台
         */
        @Override
        public void sendDeviceInfo() {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_SEND_DEVICE_INFO);
            mMessageThreadHandler.sendMessage(msg);
        }

        @Override
        public void cleanup() {
            final Message msg = mMessageThreadHandler.obtainMessage(ViewCrawler.MESSAGE_HANDLE_EDITOR_CLOSED);
            mMessageThreadHandler.sendMessage(msg);
        }
    }

    private static class VariantChange {
        public VariantChange(String aName, String anActivityName, JSONObject someChange, MPPair<Integer, Integer> aVariantId) {
            name = aName;
            activityName = anActivityName;
            change = someChange;
            variantId = aVariantId;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof VariantChange) {
                return obj.hashCode() == hashCode();
            }

            return false;
        }

        public final String name;
        public final String activityName;
        public final JSONObject change;
        public final MPPair<Integer, Integer> variantId;
    }

    private static class VariantTweak {
        public VariantTweak(String aTweakName, JSONObject aTweak, MPPair<Integer, Integer> aVariantId) {
            tweakName = aTweakName;
            tweak = aTweak;
            variantId = aVariantId;
        }

        @Override
        public int hashCode() {
            return tweakName.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof VariantTweak) {
                return obj.hashCode() == hashCode();
            }

            return false;
        }

        public final String tweakName;
        public final JSONObject tweak;
        public final MPPair<Integer, Integer> variantId;
    }

    private final MPConfig mConfig;
    private final Context mContext;
    private final MixpanelAPI mMixpanel;
    /**
     * 控件触发事件时会回调这个类中的onEvent
     * <p>
     * DynamicEventTracker ,在ViewCrawler中被创建
     */
    private final DynamicEventTracker mDynamicEventTracker;
    /**
     * 当前进入Editor页面的一些状态
     */
    private final EditState mEditState;
    /**
     * 用于AB测试的!
     */
    private final Tweaks mTweaks;
    private final Map<String, String> mDeviceInfo;
    /**
     * 后台Handler
     * Handler 用来处理与编辑页面的交互
     * 运行在单独的线程中
     */
    private final ViewCrawlerHandler mMessageThreadHandler;
    private final float mScaledDensity;
    //支持并发的Set
    private final Set<OnMixpanelTweaksUpdatedListener> mTweaksUpdatedListeners;

    private static final String SHARED_PREF_EDITS_FILE = "mixpanel.viewcrawler.changes";
    private static final String SHARED_PREF_CHANGES_KEY = "mixpanel.viewcrawler.changes";
    private static final String SHARED_PREF_BINDINGS_KEY = "mixpanel.viewcrawler.bindings";

    private static final int MESSAGE_INITIALIZE_CHANGES = 0;
    //连接到web,事件编辑页面
    private static final int MESSAGE_CONNECT_TO_EDITOR = 1;
    private static final int MESSAGE_SEND_STATE_FOR_EDITING = 2;
    private static final int MESSAGE_HANDLE_EDITOR_CHANGES_RECEIVED = 3;
    private static final int MESSAGE_SEND_DEVICE_INFO = 4;
    private static final int MESSAGE_EVENT_BINDINGS_RECEIVED = 5;
    private static final int MESSAGE_HANDLE_EDITOR_BINDINGS_RECEIVED = 6;
    private static final int MESSAGE_SEND_EVENT_TRACKED = 7;
    private static final int MESSAGE_HANDLE_EDITOR_CLOSED = 8;
    private static final int MESSAGE_VARIANTS_RECEIVED = 9;
    private static final int MESSAGE_HANDLE_EDITOR_CHANGES_CLEARED = 10;
    private static final int MESSAGE_HANDLE_EDITOR_TWEAKS_RECEIVED = 11;
    private static final int MESSAGE_SEND_LAYOUT_ERROR = 12;
    private static final int MESSAGE_PERSIST_VARIANTS_RECEIVED = 13;

    private static final int EMULATOR_CONNECT_ATTEMPT_INTERVAL_MILLIS = 1000 * 30;

    @SuppressWarnings("unused")
    private static final String LOGTAG = "MixpanelAPI.ViewCrawler";
}
