package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ReplyTracker
 * Stores a mapping of  phoneNumber -> senderEmailAddress
 * so that when an SMS reply arrives, we know who to email back.
 * Uses SharedPreferences so the data survives across worker invocations.
 * Mappings expire after 7 days (stored as  email|timestamp).
 */
public class ReplyTracker {

    private static final String PREFS   = "tinysms_replies";
    private static final long   EXPIRE  = 7L * 24 * 60 * 60 * 1000; // 7 days

    private static ReplyTracker instance;
    private final SharedPreferences prefs;

    private ReplyTracker(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized ReplyTracker get(Context ctx) {
        if (instance == null) instance = new ReplyTracker(ctx);
        return instance;
    }

    /** Store phone -> email with a timestamp */
    public void store(String phone, String email) {
        String norm = normalise(phone);
        prefs.edit()
             .putString(norm, email + "|" + System.currentTimeMillis())
             .apply();
    }

    /**
     * Look up the email address for a phone number.
     * Returns null if not found or expired.
     */
    public String lookup(String phone) {
        String norm  = normalise(phone);
        String value = prefs.getString(norm, null);
        if (value == null) return null;

        String[] parts = value.split("\\|");
        if (parts.length < 2) return parts[0];

        long ts = Long.parseLong(parts[1]);
        if (System.currentTimeMillis() - ts > EXPIRE) {
            prefs.edit().remove(norm).apply();
            return null;
        }
        return parts[0];
    }

    /** Strip spaces and normalise UK numbers to +44 */
    private String normalise(String phone) {
        String n = phone.replaceAll("[\\s\\-()]", "");
        if (n.startsWith("07") && n.length() == 11) {
            n = "+44" + n.substring(1);
        }
        return n;
    }
}
