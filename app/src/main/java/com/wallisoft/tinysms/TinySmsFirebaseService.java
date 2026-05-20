package com.wallisoft.tinysms;

import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * TinySmsFirebaseService
 * Handles incoming FCM push notifications.
 *
 * send_sms: payload contains number + message → send directly
 * check_mail: legacy wake → trigger heartbeat only
 */
public class TinySmsFirebaseService extends FirebaseMessagingService {

    private static final String TAG = "TinySMSFCM";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        Log.d(TAG, "FCM received: " + message.getData());
        Map<String, String> data = message.getData();
        String type = data.get("type");

        if ("send_sms".equals(type)) {
            // New architecture - send directly from FCM payload
            String toNumber  = data.get("to");
            String msgText   = data.get("msg");
            String replyTo   = data.get("reply_to");
            String messageId = data.get("message_id");

            if (toNumber != null && !toNumber.isEmpty()
                    && msgText != null && !msgText.isEmpty()) {
                sendSmsNow(toNumber, msgText, replyTo, messageId);
            } else {
                Log.w(TAG, "send_sms missing to/msg fields");
                LogStore.get(this).append(
                    "FCM send_sms: missing fields");
            }

        } else if ("check_mail".equals(type)) {
            // Legacy wake - just heartbeat
            triggerHeartbeat();
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "FCM token refreshed");
        SharedPreferences prefs = getSharedPreferences(
                TinyWebAuth.PREFS, MODE_PRIVATE);
        prefs.edit().putString("fcm_token", token).apply();
        LogStore.get(this).append("FCM token updated.");
        // Re-register device with new token
        new Thread(() ->
            new ApiHelper(this).registerDevice()
        ).start();
    }

    // ── Send SMS directly from FCM payload ────────────────
    private void sendSmsNow(String toNumber, String msgText,
                             String replyTo, String messageId) {
        new Thread(() -> {
            try {
                SmsManager sms = SmsManager.getDefault();
                ArrayList<String> parts = sms.divideMessage(msgText);

                if (parts.size() == 1) {
                    sms.sendTextMessage(
                        toNumber, null, msgText, null, null);
                } else {
                    sms.sendMultipartTextMessage(
                        toNumber, null, parts, null, null);
                }

                LogStore.get(this).append(
                    "SMS SENT → " + toNumber +
                    "  [" + msgText.length() + " chars]");

                // Store reply mapping if replyTo provided
                if (replyTo != null && !replyTo.isEmpty()) {
                    ReplyTracker.get(this).store(toNumber, replyTo);
                }

                // Confirm to server
                ApiHelper api = new ApiHelper(this);
                api.sendSmsConfirmation(toNumber,
                    msgText.length(), replyTo);

            } catch (Exception e) {
                LogStore.get(this).append(
                    "SMS FAIL → " + toNumber +
                    ": " + e.getMessage());
                Log.e(TAG, "sendSmsNow failed: " + e.getMessage());
            }
        }).start();
    }

    // ── Heartbeat only ────────────────────────────────────
    private void triggerHeartbeat() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                MailCheckWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        BackoffPolicy.LINEAR,
                        10, TimeUnit.SECONDS)
                .addTag("tinysms_heartbeat")
                .build();
        WorkManager.getInstance(this)
                .enqueueUniqueWork(
                        "tinysms_heartbeat",
                        androidx.work.ExistingWorkPolicy.KEEP,
                        request);
    }
}
