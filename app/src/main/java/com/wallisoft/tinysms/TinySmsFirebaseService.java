package com.wallisoft.tinysms;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.concurrent.TimeUnit;

/**
 * TinySmsFirebaseService
 * Handles incoming FCM push notifications.
 * When a check_mail push arrives, triggers an immediate
 * one-time WorkManager job to process pending emails.
 */
public class TinySmsFirebaseService extends FirebaseMessagingService {

    private static final String TAG = "TinySMSFCM";

    // -----------------------------------------------------------------------
    // Called when FCM push arrives
    // -----------------------------------------------------------------------
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Log.d(TAG, "FCM received: " + message.getData());

        String type = message.getData().get("type");
        if ("check_mail".equals(type)) {
            triggerImmediateCheck();
        }
    }

    // -----------------------------------------------------------------------
    // Called when FCM token is refreshed
    // Store locally and update server on next heartbeat
    // -----------------------------------------------------------------------
    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "FCM token refreshed");
        SharedPreferences prefs = getSharedPreferences(
                GmailHelper.PREFS, MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();
        LogStore.get(this).append("FCM token updated.");

        // Trigger registration update to send new token to server
        ApiHelper api = new ApiHelper(this);
        if (api.isPro()) {
            new Thread(() -> api.sendHeartbeat()).start();
        }
    }

    // -----------------------------------------------------------------------
    // Trigger an immediate one-time mail check via WorkManager
    // Uses expedited job so Android won't defer it
    // -----------------------------------------------------------------------
    private void triggerImmediateCheck() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                MailCheckWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        10, TimeUnit.SECONDS)
                .addTag("tinysms_immediate")
                .build();

        WorkManager.getInstance(this)
                .enqueueUniqueWork(
                        "tinysms_check",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        request);
    }
}
