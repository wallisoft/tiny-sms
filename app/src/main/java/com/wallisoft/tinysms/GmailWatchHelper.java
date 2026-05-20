package com.wallisoft.tinysms;

import android.content.Context;

/**
 * GmailWatchHelper - DEPRECATED
 * Gmail Pub/Sub watch no longer used.
 * FCM handles all push notifications.
 * Kept as stub to avoid refactoring call sites.
 */
public class GmailWatchHelper {
    public GmailWatchHelper(Context ctx) {}
    public void setupWatch() {
        // No-op - FCM handles push
    }
    public void stopWatch() {
        // No-op
    }
}
