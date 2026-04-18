package com.wallisoft.tinysms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * BootReceiver
 * Restarts reply polling worker after device reboot.
 * Also refreshes FCM token registration.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        Log.d(TAG, "Boot completed - restarting TinySMS");

        SharedPreferences prefs = context.getSharedPreferences(
                GmailHelper.PREFS, Context.MODE_PRIVATE);

        // Restart reply polling if it was enabled
        boolean replyEnabled = prefs.getBoolean("reply_enabled", false);
        String  licKey       = prefs.getString("licence_key", null);
        boolean isPro        = licKey != null && !licKey.isEmpty();

        if (isPro && replyEnabled) {
            MainActivity.scheduleReplyWorker(context);
            Log.d(TAG, "Reply worker restarted");
        }

        // Refresh FCM token on boot
        new Thread(() -> {
            new ApiHelper(context).refreshFcmToken();
            new ApiHelper(context).sendHeartbeat();
        }).start();

        LogStore.get(context).append("Device rebooted - TinySMS restarted.");
    }
}
