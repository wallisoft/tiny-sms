package com.wallisoft.tinysms;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class SmsObserver extends ContentObserver {

    private static final String TAG = "SmsObserver";
    private static final Uri    SMS_URI =
            Uri.parse("content://sms");
    private static final String KEY = "observer_sms_date";

    private final Context context;
    private final Handler mainHandler;
    private       boolean isProcessing = false; // ← guard flag

    public SmsObserver(Context context, Handler handler) {
        super(handler);
        this.context     = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        // Guard against recursive calls
        if (isProcessing) return;
        isProcessing = true;

        new Thread(() -> {
            try {
                Log.d(TAG, "DB changed: " + uri);
                checkForNewSms();
            } catch (Exception e) {
                Log.e(TAG, "onChange: " + e.getMessage());
            } finally {
                isProcessing = false;
            }
        }).start();
    }

    @Override
    public void onChange(boolean selfChange) {
        onChange(selfChange, null);
    }

    private void checkForNewSms() {
        try {
            android.content.ContentResolver cr =
                    context.getContentResolver();
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences(
                            GmailHelper.PREFS,
                            Context.MODE_PRIVATE);

            long lastDate = prefs.getLong(KEY, 0L);

            Cursor cursor = cr.query(
                    SMS_URI,
                    new String[]{"address", "body", "date", "type"},
                    "date > ?",
                    new String[]{String.valueOf(lastDate > 0 ? lastDate : 0)},
                    "date DESC");

            if (cursor == null) return;

            log("Observer: checking since " + lastDate);

            long newest = lastDate;
            int  found  = 0;

            while (cursor.moveToNext()) {
                String number = cursor.getString(0);
                String body   = cursor.getString(1);
                long   date   = cursor.getLong(2);
                int    type   = cursor.getInt(3);

                if (date <= lastDate) break; // ordered DESC so done
                if (date > newest) newest = date;
                found++;

                if (body == null) continue;

                log("SMS date=" + date + " type=" + type
                        + " from=" + number);

                if (body.contains("VALIDATE-")) {
                    handleValidation(number, body);
                    continue;
                }

                if (type == 1) {
                    forwardReply(number, body);
                }
            }
            cursor.close();

            if (newest > lastDate) {
                prefs.edit().putLong(KEY, newest).apply();
            }

        } catch (Exception e) {
            Log.e(TAG, "checkForNewSms: " + e.getMessage());
            log("Observer error: " + e.getMessage());
        }
    }

    private void forwardReply(String from, String body) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(
                        GmailHelper.PREFS, Context.MODE_PRIVATE);
        boolean isPro   = prefs.getString("licence_key", null) != null;
        boolean replyOn = prefs.getBoolean("reply_enabled", false);
        if (!isPro || !replyOn) return;

        new Thread(() -> {
            try {
                GmailHelper  gmail   = new GmailHelper(context);
                ReplyTracker tracker = ReplyTracker.get(context);
                String       replyTo = tracker.lookup(from);

                // If no reply mapping use gateway account email
                if (replyTo == null || replyTo.isEmpty()) {
                    replyTo = prefs.getString(
                            GmailHelper.KEY_ACCOUNT, "");
                }

                if (replyTo.isEmpty()) return;

                // Send actual SMS content to replyTo address
                boolean ok = gmail.sendReplyEmail(replyTo, from, body);
                log("SMS IN ← " + from
                        + (ok ? " → " + replyTo : " → failed"));
            } catch (Exception e) {
                Log.e(TAG, "forwardReply: " + e.getMessage());
            }
        }).start();
    }

    private void handleValidation(String from, String body) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern
                .compile("VALIDATE-(\\d+)-([a-f0-9]{16})",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(body);
        if (!m.find()) return;

        int    slot      = Integer.parseInt(m.group(1));
        String androidId = m.group(2);
        if (slot < 1 || slot > 2) return;

        log("SIM" + slot + " validated: " + from);
        new Thread(() ->
                new ApiHelper(context).reportSimValidation(
                        androidId, slot, from)).start();
    }

    private void log(String msg) {
        mainHandler.post(() -> {
            try { LogStore.get(context).append(msg); }
            catch (Exception ignored) {}
        });
    }
}
