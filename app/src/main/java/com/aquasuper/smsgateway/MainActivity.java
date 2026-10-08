package com.aquasuper.smsgateway;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_SMS=10;
    private TextView status, keyView;
    private String apiKey;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        apiKey=getSharedPreferences("gateway",MODE_PRIVATE).getString("apiKey",null);
        if(apiKey==null){
            apiKey=UUID.randomUUID().toString().replace("-","").substring(0,16);
            getSharedPreferences("gateway",MODE_PRIVATE).edit().putString("apiKey",apiKey).apply();
        }
        buildUi();
        if(android.os.Build.VERSION.SDK_INT>=33 &&
           checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},12);
        }
        if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.SEND_SMS},REQ_SMS);
    }

    private void buildUi(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40,50,40,40);

        TextView title=new TextView(this);
        title.setText("Aqua Super SMS Gateway");
        title.setTextSize(24);
        box.addView(title);

        TextView info=new TextView(this);
        info.setText("This Android gateway sends SMS directly through the phone's default SMS SIM.\n\nAPI Key:");
        box.addView(info);

        keyView=new TextView(this);
        keyView.setText(apiKey);
        keyView.setTextSize(18);
        keyView.setPadding(0,10,0,10);
        box.addView(keyView);

        status=new TextView(this);
        status.setPadding(0,20,0,20);
        box.addView(status);

        Button start=new Button(this);
        start.setText("Start Gateway");
        box.addView(start);
        start.setOnClickListener(v->startGateway());

        Button stop=new Button(this);
        stop.setText("Stop Gateway");
        box.addView(stop);
        stop.setOnClickListener(v->stopGateway());

        Button openSettings=new Button(this);
        openSettings.setText("Open App Settings");
        box.addView(openSettings);
        openSettings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:"+getPackageName()))));

        TextView note=new TextView(this);
        note.setText("\nGateway address is shown in the notification while running.\nKeep the phone and laptop on the same Wi-Fi/network.\nSMS uses the phone's default SMS SIM.");
        box.addView(note);

        setContentView(box);
        status.setText("Stopped");
    }

    private void startGateway(){
        if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED){
            Toast.makeText(this,"Please allow SMS permission first.",Toast.LENGTH_LONG).show();
            return;
        }
        Intent i=new Intent(this,SmsGatewayService.class);
        if(android.os.Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        status.setText("Starting gateway…");
    }

    private void stopGateway(){
        stopService(new Intent(this,SmsGatewayService.class));
        status.setText("Stopped");
    }

    @Override protected void onResume(){
        super.onResume();
        if(status!=null && SmsGatewayService.isRunning) status.setText("Running");
    }
}