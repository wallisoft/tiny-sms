package com.wallisoft.tinysms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * BootReceiver
 * WorkManager tasks survive reboot natively in modern Android,
 * but this receiver re-enqueues if needed (belt and braces).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(
                GmailHelper.PREFS, Context.MODE_PRIVATE);
        boolean workerEnabled = prefs.getBoolean("worker_enabled", false);

        if (workerEnabled) {
            MainActivity.scheduleWorker(context);
        }
    }
}
