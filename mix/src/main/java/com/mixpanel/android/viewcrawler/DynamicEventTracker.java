package com.mixpanel.android.viewcrawler;

import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.mixpanel.android.util.MPLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Handles translating events detected by ViewVisitors into events sent to Mixpanel
 * <p>
 * - Builds properties by interrogating view subtrees
 * <p>
 * - Possibly debounces events using the Handler given at construction
 * <p>
 * - Calls MixpanelAPI.track
 */
/* package */ class DynamicEventTracker implements ViewVisitor.OnEventListener {

    public DynamicEventTracker(MixpanelAPI mixpanel, Handler homeHandler) {
        mMixpanel = mixpanel;
        // 去抖动事件
        mDebouncedEvents = new HashMap<Signature, UnsentEvent>();
        mTask = new SendDebouncedTask();
        mHandler = homeHandler;
    }

    @Override
    public void OnEvent(View v, String eventName, boolean debounce) {
        // Will be called on the UI thread
        // 当前时间
        final long moment = System.currentTimeMillis();
        // 属性
        final JSONObject properties = new JSONObject();
        try {
            // 收集 指定View的 text信息,包括其子类(前提是控件 如果是textView类型)
            final String text = textPropertyFromView(v);
            properties.put("$text", text);
            // 表示数据来自 event_binding...
            properties.put("$from_binding", true);

            // We may call track much later, but we'll be tracking something
            // that happened right at moment.
            properties.put("time", moment / 1000);
        } catch (JSONException e) {
            MPLog.e(LOGTAG, "Can't format properties from view due to JSON issue", e);
        }
        // 去抖动
        if (debounce) {
            final Signature eventSignature = new Signature(v, eventName);
            // 未发送的事件的 信息 ...一个bean类
            final UnsentEvent event = new UnsentEvent(eventName, properties, moment);

            // No scheduling mTask without holding a lock on mDebouncedEvents,
            // so that we don't have a rogue thread spinning away when no events
            // are coming in.
            synchronized (mDebouncedEvents) {
                // 当集合为空时,会停止循环, 所以需要重新开启
                final boolean needsRestart = mDebouncedEvents.isEmpty();
                mDebouncedEvents.put(eventSignature, event);
                if (needsRestart) {
                    // 开启 SendDebouncedTask任务..
                    mHandler.postDelayed(mTask, DEBOUNCE_TIME_MILLIS);
                }
            }
        } else {
            // 直接将事件入队..
            mMixpanel.track(eventName, properties);
        }
    }

    // Attempts to send all tasks in mDebouncedEvents that have been waiting for
    // more than DEBOUNCE_TIME_MILLIS. Will reschedule itself as long as there
    // are more events waiting (but will *not* wait on an empty set)

    /**
     * 尝试将等待超过 DEBOUNCE_TIME_MILLIS 时间的事件 进行处理(即发送)
     * 将重新调度自己,只要有更多的事件等待 (但不会等待空集合)
     */
    private final class SendDebouncedTask implements Runnable {
        @Override
        public void run() {
            final long now = System.currentTimeMillis();
            synchronized (mDebouncedEvents) {
                final Iterator<Map.Entry<Signature, UnsentEvent>> iter = mDebouncedEvents.entrySet().iterator();
                while (iter.hasNext()) {
                    final Map.Entry<Signature, UnsentEvent> entry = iter.next();
                    final UnsentEvent val = entry.getValue();
                    if (now - val.timeSentMillis > DEBOUNCE_TIME_MILLIS) {
                        mMixpanel.track(val.eventName, val.properties);
                        iter.remove();
                    }
                }
                // 如果当前未处理结束..那么会等待半秒
                if (!mDebouncedEvents.isEmpty()) {
                    // In the average case, this is enough time to catch the next signal
                    mHandler.postDelayed(this, DEBOUNCE_TIME_MILLIS / 2);
                }
            } // synchronized
        }
    }

    /**
     * Recursively scans a view and it's children, looking for user-visible text to
     * provide as an event property.
     */
    private static String textPropertyFromView(View v) {
        String ret = null;
        // 控件是TextView,获取其文本
        if (v instanceof TextView) {
            final TextView textV = (TextView) v;
            final CharSequence retSequence = textV.getText();
            if (null != retSequence) {
                ret = retSequence.toString();
            }

            //控件是ViewGroup, 遍历子类,收集text 信息
            // 注意有上限  不超过 MAX_PROPERTY_LENGTH(128)
        } else if (v instanceof ViewGroup) {
            final StringBuilder builder = new StringBuilder();
            final ViewGroup vGroup = (ViewGroup) v;
            final int childCount = vGroup.getChildCount();
            boolean textSeen = false;
            for (int i = 0; i < childCount && builder.length() < MAX_PROPERTY_LENGTH; i++) {
                final View child = vGroup.getChildAt(i);
                final String childText = textPropertyFromView(child);
                if (null != childText && childText.length() > 0) {
                    if (textSeen) {
                        builder.append(", ");
                    }
                    builder.append(childText);
                    textSeen = true;
                }
            }

            if (builder.length() > MAX_PROPERTY_LENGTH) {
                ret = builder.substring(0, MAX_PROPERTY_LENGTH);
            } else if (textSeen) {
                ret = builder.toString();
            }
        }

        return ret;
    }

    // An event is the same from a debouncing perspective if it comes from the same view,
    // and has the same event name.
    // 同一个view上,拥有相同事件名称的事件 被当做同等事件
    //
    private static class Signature {
        public Signature(final View view, final String eventName) {
            mHashCode = view.hashCode() ^ eventName.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Signature) {
                return mHashCode == o.hashCode();
            }

            return false;
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }

        private final int mHashCode;
    }

    private static class UnsentEvent {
        public UnsentEvent(final String name, final JSONObject props, final long timeSent) {
            eventName = name;
            properties = props;
            timeSentMillis = timeSent;
        }

        /**
         * 事件生成的时间
         */
        public final long timeSentMillis;
        /**
         * 事件名称
         */
        public final String eventName;
        /**
         * 携带的属性
         */
        public final JSONObject properties;
    }

    private final MixpanelAPI mMixpanel;
    /**
     * 处理与编辑页面交互的handler
     *
     * ViewCrawlerHandler (运行在单独线程)
     *
     */
    private final Handler mHandler;
    private final Runnable mTask;

    /**
     * 待去抖动的事件...
     * 签名 <-> 事件信息
     * List of debounced events, All accesses must be synchronized
     */
    private final Map<Signature, UnsentEvent> mDebouncedEvents;

    private static final int MAX_PROPERTY_LENGTH = 128;
    private static final int DEBOUNCE_TIME_MILLIS = 1000; // 1 second delay before sending

    @SuppressWarnings("Unused")
    private static String LOGTAG = "MixpanelAPI.DynamicEventTracker";
}
