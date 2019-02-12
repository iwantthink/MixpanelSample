package com.hmt.mixpanelsample;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class GrayInnerService extends Service {
    public GrayInnerService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

    }
}
