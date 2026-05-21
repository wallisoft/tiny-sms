package com.wallisoft.tinysms;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * MailCheckWorker
 * Simplified - no Gmail dependency.
 * FCM push handles all incoming triggers.
 * This worker just sends heartbeat + checks account status.
 */
public class MailCheckWorker extends Worker {

    private static final String TAG = "MailCheckWorker";

    public MailCheckWorker(@NonNull Context context,
                           @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        try {
            ApiHelper api = new ApiHelper(ctx);

            // Heartbeat - keeps device online in dashboard
            api.sendHeartbeat();

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "doWork failed: " + e.getMessage());
            return Result.retry();
        }
    }
}
