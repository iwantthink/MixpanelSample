package com.hmt.mixpanelsample;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.mixpanel.android.mpmetrics.MixpanelAPI;

public class MainActivity extends AppCompatActivity {

    public static final String MIXPANEL_TOKEN = "3b7d8a554de588c7358ef9552eaeb852";
//    public static final String MIXPANEL_TOKEN = "efecc1169050774fbff2ce156010d8c1";

    Context mContext;

    MixpanelAPI mMixpanelAPI;

    Button mBtnToast;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = this;


//        MPLog.setLevel(0);
        mMixpanelAPI = MixpanelAPI.getInstance(mContext, MIXPANEL_TOKEN);

        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                mMixpanelAPI.track("click me");
//                mMixpanelAPI.flush();
//                try {
//                    Toast.makeText(mContext, futureTask.get(), Toast.LENGTH_SHORT).show();
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                } catch (ExecutionException e) {
//                    e.printStackTrace();
//                }

                mMixpanelAPI.connectEditor();

            }
        });

        mBtnToast = findViewById(R.id.btn_toast);
        mBtnToast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(mContext, "clicked ", Toast.LENGTH_SHORT).show();

//                Executors.newSingleThreadExecutor().execute(futureTask);

            }
        });

//        mBtnToast.setAccessibilityDelegate(new View.AccessibilityDelegate() {
//            @Override
//            public void sendAccessibilityEvent(View host, int eventType) {
//                super.sendAccessibilityEvent(host, eventType);
//                Log.d("MainActivity", "eventType:" + eventType);
//                Log.d("MainActivity", host.getClass().getCanonicalName());
////                AccessibilityEvent.TYPE_VIEW_CLICKED
//            }
//        });


    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mMixpanelAPI.flush();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return super.onOptionsItemSelected(item);
    }
}
