package com.wallisoft.tinysms;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * SmsPoller - Option A
 * Reads the device SMS inbox via ContentProvider.
 * No default SMS handler required - just READ_SMS permission.
 * Tracks the last-seen SMS ID in SharedPrefs so we only process new messages.
 */
public class SmsPoller {

    private static final Uri    SMS_INBOX = Uri.parse("content://sms/inbox");
    private static final String PREFS     = "tinysms_smspoller";
    private static final String KEY_LAST  = "last_sms_id";

    private final Context context;

    public SmsPoller(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Returns any SMS replies received since last poll.
     * Each result is a SmsReply with sender number + message body.
     */
    public List<SmsReply> pollNewReplies() {
        List<SmsReply> results = new ArrayList<>();

        long lastId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                             .getLong(KEY_LAST, 0L);

        String   selection = "_id > ?";
        String[] selArgs   = { String.valueOf(lastId) };
        String   sortOrder = "_id ASC";

        try (Cursor cursor = context.getContentResolver().query(
                SMS_INBOX, new String[]{"_id", "address", "body"},
                selection, selArgs, sortOrder)) {

            if (cursor == null) return results;

            long maxId = lastId;
            while (cursor.moveToNext()) {
                long   id     = cursor.getLong(0);
                String number = cursor.getString(1);
                String body   = cursor.getString(2);
                if (id > maxId) maxId = id;
                results.add(new SmsReply(number, body));
            }

            // Persist the highest ID we've seen
            if (maxId > lastId) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                       .edit().putLong(KEY_LAST, maxId).apply();
            }

        } catch (Exception e) {
            LogStore.get(context).append("SmsPoller error: " + e.getMessage());
        }

        return results;
    }

    // -----------------------------------------------------------------------
    // Simple data class
    // -----------------------------------------------------------------------
    public static class SmsReply {
        public final String number;
        public final String body;

        public SmsReply(String number, String body) {
            this.number = number;
            this.body   = body;
        }
    }
}
