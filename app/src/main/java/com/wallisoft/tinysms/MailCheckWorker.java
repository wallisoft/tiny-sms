package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * MailCheckWorker
 * Runs on schedule (5 min) and on FCM wake.
 * 1. Checks Gmail for pending outbound SMS
 * 2. Sends SMS via SmsManager
 * 3. Polls SMS inbox for inbound messages
 * 4. Forwards inbound SMS via Gmail
 * 5. Sends heartbeat to server
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
            GmailHelper gmail    = new GmailHelper(ctx);
            SmsPoller   poller   = new SmsPoller(ctx);
            ApiHelper   api      = new ApiHelper(ctx);

            // ── 1. Fetch and send outbound SMS ────────────
            List<GmailHelper.SmsJob> jobs = gmail.fetchPendingSmsEmails();
            for (GmailHelper.SmsJob job : jobs) {
                sendSms(ctx, gmail, api, job);
            }

            // ── 2. Poll inbound SMS and forward to email ──
            List<SmsPoller.SmsReply> replies = poller.pollNewReplies();
            for (SmsPoller.SmsReply reply : replies) {
                // Try reply tracker first (maps number → original sender)
                String forwardTo = ReplyTracker.get(ctx).lookup(reply.number);
                if (forwardTo != null) {
                    // Send reply back to original sender
                    boolean ok = gmail.sendReplyEmail(
                            forwardTo, reply.number, reply.body);
                    LogStore.get(ctx).append(
                            "SMS REPLY ← " + reply.number
                            + (ok ? " → forwarded" : " → forward failed"));
                } else {
                    // Unknown number - forward to gateway account owner
                    boolean ok = gmail.forwardInboundSms(
                            reply.number, reply.body);
                    LogStore.get(ctx).append(
                            "SMS IN ← " + reply.number
                            + (ok ? " → forwarded" : " → forward failed"));
                }
            }

            // ── 3. Heartbeat ──────────────────────────────
            api.sendHeartbeat();

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "doWork failed: " + e.getMessage());
            LogStore.get(ctx).append("Mail check error: " + e.getMessage());
            return Result.retry();
        }
    }

    // -----------------------------------------------------------------------
    // Send a single SMS job
    // -----------------------------------------------------------------------
    private void sendSms(Context ctx, GmailHelper gmail,
                          ApiHelper api, GmailHelper.SmsJob job) {
        try {
            SmsManager sms = SmsManager.getDefault();
            ArrayList<String> parts = sms.divideMessage(job.messageText);

            if (parts.size() == 1) {
                sms.sendTextMessage(
                        job.phoneNumber, null, job.messageText, null, null);
            } else {
                sms.sendMultipartTextMessage(
                        job.phoneNumber, null, parts, null, null);
            }

            // Store mapping for reply routing
            if (job.replyToEmail != null && !job.replyToEmail.isEmpty()) {
                ReplyTracker.get(ctx).store(job.phoneNumber, job.replyToEmail);
            }

            LogStore.get(ctx).append(
                    "SMS SENT → " + job.phoneNumber
                    + "  [" + job.messageText.length() + " chars]");

            // Confirm to server
            api.sendSmsConfirmation(
                    job.phoneNumber, job.messageText.length(),
                    job.replyToEmail);

        } catch (Exception e) {
            LogStore.get(ctx).append(
                    "SMS FAIL → " + job.phoneNumber + ": " + e.getMessage());
            Log.e(TAG, "sendSms failed: " + e.getMessage());
        }
    }
}
