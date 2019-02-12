package com.hmt.mixpanelsample;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import com.mixpanel.android.mpmetrics.MixpanelAPI;
import com.mixpanel.android.util.MPLog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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
        MPLog.setLevel(0);
        mMixpanelAPI = MixpanelAPI.getInstance(mContext, MIXPANEL_TOKEN);

        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mMixpanelAPI.connectEditor();

            }
        });

        mBtnToast = findViewById(R.id.btn_toast);
        mBtnToast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                Toast.makeText(mContext, "clicked ", Toast.LENGTH_SHORT).show();

//                WindowManager manager = getWindowManager();
//                DisplayMetrics metrics = new DisplayMetrics();
//                manager.getDefaultDisplay().getMetrics(metrics);
//                int width = metrics.widthPixels;
//                int height = metrics.heightPixels;
//                Log.d("MainActivity", "width:" + width);
//                Log.d("MainActivity", "height:" + height);
//
//                View rootView = getWindow().getDecorView().getRootView();
//                float x = rootView.getX();
//                float y = rootView.getY();
//                Log.d("MainActivity", "x:" + x);
//                Log.d("MainActivity", "y:" + y);
//                Log.d("MainActivity", "rootView.getWidth():" + rootView.getWidth());
//                Log.d("MainActivity", "rootView.getHeight():" + rootView.getHeight());


//                mBtnToast.setBackgroundColor(Color.RED);

//                startActivity(new Intent(mContext, TestEListView.class));

//                reflectDisplay(mContext);

                View content = findViewById(android.R.id.content);
                while (content.getParent() != null) {
                    Log.d("MainActivity", content.getClass().getSimpleName());
                    if (content.getClass().getSimpleName().equals("DecorView")) {
                        content.setBackgroundColor(Color.RED);
                        ViewGroup viewGroup = (ViewGroup) content;
                        Log.d("MainActivity", "viewGroup.getChildCount():" + viewGroup.getChildCount());

                        for (int i = 0; i < viewGroup.getChildCount(); i++) {
                            View view = viewGroup.getChildAt(i);
                            Log.d("MainActivity", "view.getWidth():" + view.getWidth());
                            Log.d("MainActivity", "view.getHeight():" + view.getHeight());
                            Log.d("MainActivity", "view.getX():" + view.getX());
                            Log.d("MainActivity", "view.getY():" + view.getY());
                            Log.d("MainActivity", "view.getTranslationX():" + view.getTranslationX());
                            Log.d("MainActivity", "view.getTranslationY():" + view.getTranslationY());
                        }
                    }
                    if (content.getParent() instanceof View) {
                        content = (View) content.getParent();
                    } else {
                        break;
                    }
                }

                Log.d("MainActivity", content.getParent().getClass().getSimpleName());

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

    private void reflectDisplay(Context context) {
        try {
            Class activityThreadClazz = Class.forName("android.app.ActivityThread");
            Method currentActivityThreadMethod = activityThreadClazz.getDeclaredMethod("currentActivityThread");
            currentActivityThreadMethod.setAccessible(true);
            Object currentActivityThread = currentActivityThreadMethod.invoke(null);

            Method getSystemContextMethod = activityThreadClazz.getDeclaredMethod("getSystemContext");
            getSystemContextMethod.setAccessible(true);
            Object sContext = getSystemContextMethod.invoke(currentActivityThread, new Object[]{});
            if (sContext != null) {
                Method displayMethod = sContext.getClass().getDeclaredMethod("getDisplay", new Class[]{});
                displayMethod.setAccessible(true);
                Display display = (Display) displayMethod.invoke(sContext, new Object[]{});
                Point point = new Point();
                display.getRealSize(point);
                Log.d("MainActivity", "point.x:" + point.x);
                Log.d("MainActivity", "point.y:" + point.y);


                DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
                Log.d("MainActivity", "metrics.widthPixels:" + metrics.widthPixels);
                Log.d("MainActivity", "metrics.heightPixels:" + metrics.heightPixels);


            } else {
                Toast.makeText(context, "scontext is null", Toast.LENGTH_SHORT).show();

            }


        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

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
