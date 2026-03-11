package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * LogStore
 * Singleton that holds a rolling text log (last 200 lines) in SharedPreferences
 * so both the UI and the background worker can append to it.
 */
public class LogStore {

    private static final String PREFS    = "tinysms_log";
    private static final String KEY_LOG  = "log";
    private static final int    MAX_CHARS = 8000;

    private static LogStore instance;
    private final SharedPreferences prefs;
    private final SimpleDateFormat  sdf = new SimpleDateFormat("HH:mm:ss", Locale.UK);

    private LogStore(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized LogStore get(Context ctx) {
        if (instance == null) instance = new LogStore(ctx);
        return instance;
    }

    public synchronized void append(String line) {
        String ts  = sdf.format(new Date());
        String entry = "[" + ts + "] " + line + "\n";
        String current = prefs.getString(KEY_LOG, "");
        String updated = current + entry;
        // Trim to keep under MAX_CHARS
        if (updated.length() > MAX_CHARS) {
            updated = updated.substring(updated.length() - MAX_CHARS);
            // Trim to next newline so we don't start mid-line
            int nl = updated.indexOf('\n');
            if (nl >= 0) updated = updated.substring(nl + 1);
        }
        prefs.edit().putString(KEY_LOG, updated).apply();
    }

    public synchronized String read() {
        return prefs.getString(KEY_LOG, "Ready.\n");
    }

    public synchronized void clear() {
        prefs.edit().putString(KEY_LOG, "Log cleared.\n").apply();
    }
}
