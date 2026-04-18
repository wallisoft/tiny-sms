package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * LicenceHelper
 * Fetches Pro licence key from server automatically
 * Called when user taps Pro status / Get Pro
 */
public class LicenceHelper {

    private static final String TAG     = "LicenceHelper";
    private static final String API_URL =
            "https://tiny-web.uk/api/licence.php";

    public interface LicenceCallback {
        void onFound(String licenceKey, String plan);
        void onNotFound(String url);
        void onError();
    }

    // ── Fetch licence from server ─────────────────────────
    public static void fetchLicence(Context context,
                                     LicenceCallback callback) {
        new Thread(() -> {
            try {
                String androidId = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ANDROID_ID);
                String gmail = context.getSharedPreferences(
                        GmailHelper.PREFS, Context.MODE_PRIVATE)
                        .getString(GmailHelper.KEY_ACCOUNT, "");

                JSONObject json = new JSONObject();
                json.put("android_id", androidId);
                json.put("account",    gmail);

                byte[] body = json.toString()
                        .getBytes(StandardCharsets.UTF_8);
                HttpURLConnection conn = (HttpURLConnection)
                        new URL(API_URL).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty(
                        "Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setDoInput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    callback.onError();
                    return;
                }

                java.io.InputStream is = conn.getInputStream();
                byte[] buf = new byte[4096];
                int    len = is.read(buf);
                conn.disconnect();

                if (len <= 0) { callback.onError(); return; }

                JSONObject resp = new JSONObject(
                        new String(buf, 0, len,
                                StandardCharsets.UTF_8));

                if (resp.optBoolean("found", false)) {
                    callback.onFound(
                            resp.getString("licence_key"),
                            resp.optString("plan", "sms-pro"));
                } else {
                    callback.onNotFound(
                            resp.optString("url",
                                    "https://tiny-sms.uk"));
                }

            } catch (Exception e) {
                Log.w(TAG, "fetchLicence failed: " + e.getMessage());
                callback.onError();
            }
        }).start();
    }
}
