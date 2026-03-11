package com.wallisoft.tinysms;

import android.content.Context;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * MailCheckWorker
 * Runs every 5 minutes via WorkManager.
 *
 * Pass 1 - Outbound: fetch unread "sms" subject emails → send as SMS
 * Pass 2 - Inbound:  poll SMS inbox for new replies → email back to sender
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
        Context      ctx     = getApplicationContext();
        LogStore     log     = LogStore.get(ctx);
        GmailHelper  gmail   = new GmailHelper(ctx);
        ReplyTracker tracker = ReplyTracker.get(ctx);
        SmsPoller    poller  = new SmsPoller(ctx);

        log.append("Worker: running...");

        // ----------------------------------------------------------------
        // Pass 1: emails → SMS (outbound)
        // ----------------------------------------------------------------
        try {
            List<GmailHelper.SmsJob> jobs = gmail.fetchPendingSmsEmails();

            if (!jobs.isEmpty()) {
                SmsManager smsManager = SmsManager.getDefault();
                for (GmailHelper.SmsJob job : jobs) {
                    try {
                        ArrayList<String> parts = smsManager.divideMessage(job.messageText);
                        if (parts.size() == 1) {
                            smsManager.sendTextMessage(
                                    job.phoneNumber, null, job.messageText, null, null);
                        } else {
                            smsManager.sendMultipartTextMessage(
                                    job.phoneNumber, null, parts, null, null);
                        }
                        tracker.store(job.phoneNumber, job.replyToEmail);
                        log.append("SMS SENT → " + job.phoneNumber
                                + "  [" + job.messageText.length() + " chars]"
                                + "  reply→" + job.replyToEmail);
                    } catch (Exception e) {
                        log.append("SMS FAIL → " + job.phoneNumber + ": " + e.getMessage());
                    }
                }
            } else {
                log.append("Worker: no outbound emails.");
            }
        } catch (Exception e) {
            log.append("Worker outbound error: " + e.getMessage());
            Log.e(TAG, "Outbound error", e);
        }

        // ----------------------------------------------------------------
        // Pass 2: SMS inbox → email replies (inbound)
        // ----------------------------------------------------------------
        try {
            List<SmsPoller.SmsReply> replies = poller.pollNewReplies();
            for (SmsPoller.SmsReply reply : replies) {
                String emailAddr = tracker.lookup(reply.number);
                if (emailAddr == null) {
                    log.append("SMS IN (untracked) ← " + reply.number);
                    continue;
                }
                log.append("SMS IN ← " + reply.number + ": " + reply.body);
                boolean ok = gmail.sendReplyEmail(emailAddr, reply.number, reply.body);
                log.append(ok
                        ? "EMAIL SENT → " + emailAddr
                        : "EMAIL FAIL → " + emailAddr);
            }
        } catch (Exception e) {
            log.append("Worker inbound error: " + e.getMessage());
            Log.e(TAG, "Inbound error", e);
        }

        return Result.success();
    }
}
