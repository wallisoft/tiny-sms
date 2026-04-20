package com.wallisoft.tinysms;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.WatchRequest;
import com.google.api.services.gmail.model.WatchResponse;

import java.util.Arrays;
import java.util.Collections;

/**
 * GmailWatchHelper
 * Sets up Gmail push notifications via Google Cloud Pub/Sub
 * Called after Gmail link - provides instant FCM on new email
 * No polling needed - Google pushes to our server instantly
 */
public class GmailWatchHelper {

    private static final String TAG       = "GmailWatch";
    private static final String TOPIC     =
            "projects/tiny-sms/topics/gmail-push";
    private static final String APP_NAME  = "TinySMS";

    private final Context context;

    public GmailWatchHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Setup Gmail watch ─────────────────────────────────
    // Call once after Gmail sign-in
    // Watch expires after 7 days - renew via cron server-side
    public boolean setupWatch() {
        try {
            GoogleSignInAccount account =
                    GoogleSignIn.getLastSignedInAccount(context);
            if (account == null) {
                Log.w(TAG, "No signed in account");
                return false;
            }

            GoogleAccountCredential credential =
                    GoogleAccountCredential.usingOAuth2(
                            context,
                            Collections.singletonList(
                                    "https://www.googleapis.com/auth/gmail.modify"));
            credential.setSelectedAccount(account.getAccount());

            Gmail gmail = new Gmail.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential)
                    .setApplicationName(APP_NAME)
                    .build();

            LogStore.get(context).append(
                    "Watch topic: " + TOPIC);

            // Watch INBOX for new messages
            WatchRequest request = new WatchRequest()
                    .setTopicName(TOPIC)
                    .setLabelIds(Arrays.asList("INBOX"));

            WatchResponse response = gmail.users()
                    .watch("me", request)
                    .execute();

            Log.d(TAG, "Watch setup: historyId="
                    + response.getHistoryId()
                    + " expiry=" + response.getExpiration());

            LogStore.get(context).append(
                    "Gmail push notifications enabled ✅");

            // Save expiry to know when to renew
            context.getSharedPreferences(
                    GmailHelper.PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong("gmail_watch_expiry",
                            response.getExpiration())
                    .apply();

            return true;

        } catch (Exception e) {
            Log.e(TAG, "setupWatch failed: " + e.getMessage());
            LogStore.get(context).append(
                    "Gmail push setup failed: " + e.getMessage());
            return false;
        }
    }

    // ── Stop watch ────────────────────────────────────────
    public void stopWatch() {
        try {
            GoogleSignInAccount account =
                    GoogleSignIn.getLastSignedInAccount(context);
            if (account == null) return;

            GoogleAccountCredential credential =
                    GoogleAccountCredential.usingOAuth2(
                            context,
                            Collections.singletonList(
                                    "https://www.googleapis.com/auth/gmail.modify"));
            credential.setSelectedAccount(account.getAccount());

            Gmail gmail = new Gmail.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential)
                    .setApplicationName(APP_NAME)
                    .build();

            gmail.users().stop("me").execute();
            LogStore.get(context).append("Gmail watch stopped.");

        } catch (Exception e) {
            Log.e(TAG, "stopWatch failed: " + e.getMessage());
        }
    }
}
