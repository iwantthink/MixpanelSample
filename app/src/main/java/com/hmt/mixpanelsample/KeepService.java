package com.hmt.mixpanelsample;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import com.hmt.analytics.HMTAgent;

import org.json.JSONException;

public class KeepService extends Service {
    private static final int GRAY_SERVICE_ID = 123456;

    public KeepService() {
    }


    @Override
    public void onCreate() {
        super.onCreate();
        log("KeepService is created");
        log(Thread.currentThread().getName() + "");
        HMTAgent.enableDebug(true);
        HMTAgent.Initialize(getApplicationContext(),
                0);

        new Thread(new Runnable() {
            @Override
            public void run() {
                for (; ; ) {
                    try {
                        Thread.sleep(30000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    log("间隔 30 秒钟,执行updateOnlineConfig");
                    try {

                        HMTAgent.testConfig(
                                getApplicationContext(),
                                json);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                }
            }
        }).start();
    }

    @Override
    public int onStartCommand(Intent service, int flags, int startId) {
        log("onStartCommand , flags = " + flags + ", startID = " + startId);
        //设置service为前台服务，提高优先级
        if (Build.VERSION.SDK_INT < 18) {
            //Android4.3以下 ，此方法能有效隐藏Notification上的图标
            startForeground(GRAY_SERVICE_ID, new Notification());
        } else if (Build.VERSION.SDK_INT > 18 && Build.VERSION.SDK_INT < 25) {
            //Android4.3 - Android7.0，此方法能有效隐藏Notification上的图标
            Intent innerIntent = new Intent(this, GrayInnerService.class);
            startService(innerIntent);
            startForeground(GRAY_SERVICE_ID, new Notification());
        } else {
            //Android7.1 google修复了此漏洞，暂无解决方法（现状：Android7.1以上app启动后通知栏会出现一条"正在运行"的通知消息）
            startForeground(GRAY_SERVICE_ID, new Notification());
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        log("KeepService is onDestroied");
    }

    public static void log(String msg) {
        Log.e("KeepService", msg);

    }

    static final String json = "{\"id\":332,\"appKey\":\"UA-irstest-android\",\"adActionTime\":null,\"cip\":null,\"sip\":null,\"sendSwitch\":\"1\",\"sendUrl\":\"\",\"deliveryType\":{\"code\":\"1\"},\"tk\":\"4896bd26c090ed76b97d45a38f02b684f950f00aeafabb9ccf6ef4e681b9cb95092d3167cf8df014b945e52cf9abb13662afdd24b76c52b4ecc6a2aa3427d41df27edfb796a5a312e0bbcefb453ab2bb3d2f9ac60f8e6442d410b47edc3e289260d90b9617cae16f41b31768c9d3ccf8d11bb720028114015a5e60e818580755028bf96645d9fd7023d7e2f1d20942fccd3975967056f12f0faefd1f216d00b6a81c2e8e770c55ec734daaec6c539b46e782e43fe04c052b98d46d630db3ea370636b908167def8a739e6f28a452fe62c6a35a25225acb1186c1c65459539d1c39c4c7b8a213a31f508c6f2399cda626593fa33c8630be34e4de1760b260aef9db69b24b27d303ab815c9c63e2ad4463fcdf125754b7ae268b7bcbc2493ff5b014b869843546d4aec99d3c60c439dc19158d4d06ed5b008b7d279de3e72c61fd23badb1fb33b4860a8da18d5415ddc031e3507f2daaf40d1e780e02ecb4ffd08ef72875d61e63f97037c3070469cad86dc7fe6e70e1cb025a718587f545df174\"}";
}
