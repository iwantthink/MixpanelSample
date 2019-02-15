package com.hmt.mixpanelsample;

import android.app.Activity;
import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

public class SeconedActivity extends AppCompatActivity {


    public static void start(Activity activity) {
        Intent intent = new Intent(activity, SeconedActivity.class);
        activity.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seconed);

        findViewById(R.id.btn_ssss).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(SeconedActivity.this, "sss", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
