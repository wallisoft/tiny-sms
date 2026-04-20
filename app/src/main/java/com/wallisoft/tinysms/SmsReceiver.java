package com.wallisoft.tinysms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * SmsReceiver
 * Handles incoming SMS broadcasts
 * Processes SMS_RECEIVED only (SMS_DELIVER also fires but is ignored)
 * Both broadcasts fire on Samsung regardless of default SMS app status
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // Only process SMS_RECEIVED - ignore SMS_DELIVER
        // Both fire on Samsung devices, SMS_RECEIVED is sufficient
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(action)) {
            return;
        }

        LogStore.get(context).append(
                "SMS broadcast: " + action);

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus   = (Object[]) bundle.get("pdus");
        String   format = bundle.getString("format");
        if (pdus == null || pdus.length == 0) return;

        // Combine multi-part SMS
        StringBuilder fullBody = new StringBuilder();
        String fromNumber = null;

        for (Object pdu : pdus) {
            try {
                SmsMessage msg = SmsMessage.createFromPdu(
                        (byte[]) pdu, format);
                if (msg == null) continue;
                if (fromNumber == null) {
                    fromNumber = msg.getOriginatingAddress();
                }
                if (msg.getMessageBody() != null) {
                    fullBody.append(msg.getMessageBody());
                }
            } catch (Exception e) {
                Log.w(TAG, "PDU parse: " + e.getMessage());
            }
        }

        if (fromNumber == null || fullBody.length() == 0) return;

        String body = fullBody.toString();

        // ── VALIDATE pattern ──────────────────────────────
        if (body.contains("VALIDATE-")) {
            handleValidation(context, fromNumber, body);
            return;
        }

        // ── Reply forwarding ──────────────────────────────
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(
                        GmailHelper.PREFS, Context.MODE_PRIVATE);
        boolean isPro   = prefs.getString(
                "licence_key", null) != null;
        boolean replyOn = prefs.getBoolean(
                "reply_enabled", false);

        if (!isPro || !replyOn) return;

        final String finalFrom = fromNumber;
        final String finalBody = body;

        new Thread(() -> {
            try {
                GmailHelper  gmail   = new GmailHelper(context);
                ReplyTracker tracker = ReplyTracker.get(context);
                String       replyTo = tracker.lookup(finalFrom);

                // If no reply mapping use gateway account
                if (replyTo == null || replyTo.isEmpty()) {
                    replyTo = prefs.getString(
                            GmailHelper.KEY_ACCOUNT, "");
                }

                if (replyTo.isEmpty()) return;

                boolean ok = gmail.sendReplyEmail(
                        replyTo, finalFrom, finalBody);

                LogStore.get(context).append(
                        "SMS REPLY ← " + finalFrom
                        + (ok ? " → forwarded to " + replyTo
                              : " → forward failed"));

            } catch (Exception e) {
                Log.e(TAG, "Forward failed: " + e.getMessage());
                LogStore.get(context).append(
                        "SMS forward error: " + e.getMessage());
            }
        }).start();
    }

    // ── Handle SIM validation SMS ─────────────────────────
    private void handleValidation(Context context,
                                   String from,
                                   String body) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern
                .compile("VALIDATE-(\\d+)-([a-f0-9]{16})",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(body);

        if (!m.find()) return;

        int    slot      = Integer.parseInt(m.group(1));
        String androidId = m.group(2);

        // Only validate OUR own device
        String myId = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        if (!androidId.equals(myId)) return;

        if (slot < 1 || slot > 2) return;

        LogStore.get(context).append(
                "SIM" + slot + " validated: " + from);

        new Thread(() ->
                new ApiHelper(context).reportSimValidation(
                        androidId, slot, from)).start();
    }
}
