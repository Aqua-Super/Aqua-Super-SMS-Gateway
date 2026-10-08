package com.aquasuper.smsgateway;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.SmsManager;

public class RespondViaMessageService extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && Intent.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            Uri data = intent.getData();
            String phone = data == null ? "" : data.getSchemeSpecificPart();
            String body = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (phone != null && !phone.isEmpty() && body != null && !body.isEmpty()) {
                try {
                    SmsManager.getDefault().sendTextMessage(phone, null, body, null, null);
                } catch (Exception ignored) {
                }
            }
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
