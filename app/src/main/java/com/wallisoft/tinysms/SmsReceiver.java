package com.wallisoft.tinysms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

/**
 * SmsReceiver
 * Fires instantly when SMS arrives via broadcast
 * Handles both VALIDATE pattern and reply forwarding
 * Fallback: SmsPoller catches anything missed here
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        android.util.Log.e("SMSRECEIVER", 
            "onReceive called! action=" + intent.getAction());
        LogStore.get(context).append(
            "SMS broadcast: " + intent.getAction());
        String action = intent.getAction();
        if (!android.provider.Telephony.Sms.Intents
                .SMS_RECEIVED_ACTION.equals(action) &&
            !"android.provider.Telephony.SMS_DELIVER".equals(action)) {
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus  = (Object[]) bundle.get("pdus");
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
        Log.d(TAG, "SMS from: " + fromNumber);

        // ── VALIDATE pattern ──────────────────────────────
        if (body.contains("VALIDATE-")) {
            handleValidation(context, fromNumber, body);
            return; // Don't forward validation SMS
        }

        // ── Reply forwarding ──────────────────────────────
        // Check if reply forwarding is enabled
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(
                        GmailHelper.PREFS, Context.MODE_PRIVATE);
        boolean replyEnabled = prefs.getBoolean(
                "reply_enabled", false);
        String  licKey       = prefs.getString("licence_key", null);
        boolean isPro        = licKey != null && !licKey.isEmpty();

        if (!isPro || !replyEnabled) return;

        // Forward via Gmail on background thread
        String finalFrom = fromNumber;
        new Thread(() -> {
            try {
                GmailHelper gmail = new GmailHelper(context);
                ReplyTracker tracker = ReplyTracker.get(context);

                // Check if we have a reply-to mapping
                String replyTo = tracker.lookup(finalFrom);

                if (replyTo != null && !replyTo.isEmpty()) {
                    // Known sender - reply to original email sender
                    boolean ok = gmail.sendReplyEmail(
                            replyTo, finalFrom, body);
                    LogStore.get(context).append(
                            "SMS REPLY ← " + finalFrom
                                    + (ok ? " → forwarded to " + replyTo
                                    : " → forward failed"));
                } else {
                    // Unknown sender - forward to gateway account
                    boolean ok = gmail.forwardInboundSms(
                            finalFrom, body);
                    LogStore.get(context).append(
                            "SMS IN ← " + finalFrom
                                    + (ok ? " → forwarded"
                                    : " → forward failed"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Forward failed: " + e.getMessage());
                LogStore.get(context).append(
                        "Forward error: " + e.getMessage());
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

        if (slot < 1 || slot > 2) return;

        LogStore.get(context).append(
                "SIM" + slot + " validated: " + from);

        new Thread(() ->
                new ApiHelper(context).reportSimValidation(
                        androidId, slot, from)).start();
    }
}