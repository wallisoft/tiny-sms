package com.wallisoft.tinysms;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SmsPoller
 * Uses Telephony.Sms.CONTENT_URI with type=1 (received)
 * Count-based detection avoids timestamp/format issues
 */
public class SmsPoller {

    private static final String TAG       = "SmsPoller";
    private static final Uri    SMS_URI   = Telephony.Sms.CONTENT_URI;
    private static final String PREFS     = GmailHelper.PREFS;
    private static final String KEY_COUNT = "last_sms_count";
    private static final String KEY_VAL   = "validated_ids";

    private final Context context;

    public SmsPoller(Context context) {
        this.context = context.getApplicationContext();
    }

    public static class SmsReply {
        public String number;
        public String body;
        public long   timestamp;
    }

    // ── Set baseline count without polling ───────────────
    public static void setBaseline(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Cursor cursor = cr.query(SMS_URI, new String[]{"_id"}, null, null, null);
            if (cursor != null) {
                int totalCount = cursor.getCount();
                SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                prefs.edit().putInt(KEY_COUNT, totalCount).apply();
                cursor.close();
                LogStore.get(ctx).append("SMS baseline reset to " + totalCount);
            }
        } catch (Exception e) {
            Log.e(TAG, "setBaseline: " + e.getMessage());
        }
    }

    // ── Poll for new replies and validate SIMs ────────────
    public List<SmsReply> pollNewReplies() {
        List<SmsReply> replies = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        // ── Step 1: Scan for VALIDATE (any age) ──────────
        scanForValidation(prefs);

        // ── Step 2: Count-based reply scan ───────────────
        try {
            ContentResolver cr = context.getContentResolver();

            // No type filter - get all SMS
            Cursor cursor = cr.query(
                    SMS_URI,
                    new String[]{"address", "body", "date", "type"},
                    null,
                    null,
                    "date DESC");

            if (cursor == null) {
                return replies;
            }

            int totalCount = cursor.getCount();
            int lastCount  = prefs.getInt(KEY_COUNT, -1);

            // First run - save count as baseline
            if (lastCount == -1) {
                prefs.edit().putInt(KEY_COUNT, totalCount).apply();
                cursor.close();
                return replies;
            }

            // Save new count
            prefs.edit().putInt(KEY_COUNT, totalCount).apply();

            int newCount = totalCount - lastCount;
            if (newCount <= 0) {
                cursor.close();
                return replies;
            }


            // Process newest ones
            int processed = 0;
            while (cursor.moveToNext() && processed < newCount) {
                String number = cursor.getString(0);
                String body   = cursor.getString(1);
                long   date   = cursor.getLong(2);

                // Skip validation SMS
                if (body != null && body.contains("VALIDATE-")) {
                    processed++;
                    continue;
                }

                LogStore.get(context).append(
                        "SMS reply from=" + number);

                SmsReply reply = new SmsReply();
                reply.number    = number;
                reply.body      = body;
                reply.timestamp = date;
                replies.add(reply);
                processed++;
            }
            cursor.close();

            if (!replies.isEmpty()) {
                LogStore.get(context).append(
                        "Inbound: " + replies.size()
                        + " new message(s)");
            }

        } catch (Exception e) {
            Log.e(TAG, "pollNewReplies: " + e.getMessage());
            LogStore.get(context).append(
                    "SMS poll error: " + e.getMessage());
        }

        return replies;
    }

    // ── Public validation-only scan ─────────────────────
    public void scanValidationOnly() {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        scanForValidation(prefs);
    }

    // ── Scan ALL SMS for VALIDATE pattern ─────────────────
    private void scanForValidation(SharedPreferences prefs) {
        try {
            String alreadyDone = prefs.getString(KEY_VAL, "");

            ContentResolver cr = context.getContentResolver();
            Cursor cursor = cr.query(
                    SMS_URI,
                    new String[]{"address", "body"},
                    "body LIKE ? AND type=1",
                    new String[]{"%VALIDATE-%"},
                    "date DESC");

            if (cursor == null) return;

            int found = cursor.getCount();
            if (found > 0) {
            }

            while (cursor.moveToNext()) {
                String number = cursor.getString(0);
                String body   = cursor.getString(1);
                if (body == null) continue;

                // Log type to understand Samsung storage
                try {
                    int typeCol = cursor.getColumnIndex("type");
                    if (typeCol >= 0) {
                        LogStore.get(context).append(
                            "VAL SMS type=" + cursor.getInt(typeCol)
                            + " from=" + number);
                    }
                } catch (Exception ignored) {}

                java.util.regex.Matcher m =
                        java.util.regex.Pattern
                        .compile("VALIDATE-(\\d+)-([a-f0-9]{16})",
                                java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(body);

                if (!m.find()) continue;

                int    slot      = Integer.parseInt(m.group(1));
                String androidId = m.group(2);

                String key = androidId + "-" + slot;
                if (alreadyDone.contains(key)) continue;
                if (slot < 1 || slot > 2) continue;

                // Only validate OUR own device_id
                String myId = android.provider.Settings.Secure
                        .getString(context.getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID);
                if (!androidId.equals(myId)) continue;

                LogStore.get(context).append(
                        "SIM" + slot + " validated: " + number);

                final String finalNum  = number;
                final int    finalSlot = slot;
                new Thread(() ->
                        new ApiHelper(context)
                                .reportSimValidation(
                                        androidId,
                                        finalSlot,
                                        finalNum)).start();

                alreadyDone = alreadyDone + key + ",";
                prefs.edit().putString(KEY_VAL, alreadyDone).apply();
            }
            cursor.close();

        } catch (Exception e) {
            Log.e(TAG, "scanForValidation: " + e.getMessage());
        }
    }
}
