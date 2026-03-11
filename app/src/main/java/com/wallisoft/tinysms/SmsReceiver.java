package com.wallisoft.tinysms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SmsReceiver
 * Listens for incoming SMS messages.
 * If the sender's number is in ReplyTracker, forwards the reply back via Gmail.
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final String TAG    = "SmsReceiver";
    private static final String PDUS   = "pdus";
    private static final String FORMAT = "format";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;

        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus   = (Object[]) bundle.get(PDUS);
        String   format = bundle.getString(FORMAT);
        if (pdus == null) return;

        // Reconstruct full message (may arrive in multiple PDUs)
        StringBuilder body = new StringBuilder();
        String sender = null;

        for (Object pdu : pdus) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu, format);
            if (sender == null) sender = sms.getOriginatingAddress();
            body.append(sms.getMessageBody());
        }

        final String fromNumber = sender;
        final String msgBody    = body.toString();

        LogStore.get(context).append("SMS IN ← " + fromNumber + ": " + msgBody);

        // Check if we have an email to reply to
        String emailAddr = ReplyTracker.get(context).lookup(fromNumber);
        if (emailAddr == null) {
            Log.d(TAG, "No reply address for " + fromNumber + " - ignoring");
            return;
        }

        final String replyTo = emailAddr;
        final Context appCtx = context.getApplicationContext();

        // Do network IO off the main thread
        executor.execute(() -> {
            GmailHelper gmail = new GmailHelper(appCtx);
            boolean ok = gmail.sendReplyEmail(replyTo, fromNumber, msgBody);
            LogStore.get(appCtx).append(
                    ok ? "EMAIL SENT → " + replyTo + " (reply from " + fromNumber + ")"
                       : "EMAIL FAIL → " + replyTo
            );
        });
    }
}
