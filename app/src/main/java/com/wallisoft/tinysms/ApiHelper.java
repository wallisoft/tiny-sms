package com.wallisoft.tinysms;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ApiHelper {

    private static final String TAG     = "ApiHelper";
    private static final String API_URL = "https://tiny-web.uk/api/device.php";
    private static final String PREFS   = GmailHelper.PREFS;
    private static final String KEY_LIC = "licence_key";
    private static final String KEY_FCM = "fcm_token";
    private static final int    TIMEOUT = 8000;

    private final Context context;

    public ApiHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressWarnings("unused")
    public boolean isPro() {
        String key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getString(KEY_LIC, null);
        return key != null && !key.isEmpty();
    }

    // ── Get stable android_id ─────────────────────────────
    private String getAndroidId() {
        return Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ANDROID_ID);
    }

    // ── Register device ───────────────────────────────────
    public void registerDevice(String gmailAccount) {
        try {
            JSONObject json = new JSONObject();
            json.put("event",       "register");
            json.put("android_id",  getAndroidId());
            json.put("account",     gmailAccount);
            json.put("model",       Build.MODEL);
            json.put("android_ver", String.valueOf(Build.VERSION.SDK_INT));
            json.put("version",     BuildConfig.VERSION_NAME);
            json.put("battery",     getBatteryLevel());
            json.put("sig",         getSignalBars());
            json.put("fcm_token",   getFcmToken());
            try {
                json.put("sim1_number",  getSimNumber(0));
                json.put("sim2_number",  getSimNumber(1));
                json.put("sim1_carrier", getCarrier(0));
                json.put("sim2_carrier", getCarrier(1));
            } catch (Exception simEx) {
                Log.w(TAG, "SIM info: " + simEx.getMessage());
            }

            // POST and read response for validate_number
            String response = postWithResponse(json);
            LogStore.get(context).append(
                    "Device registered: " + Build.MODEL);

            // Server supplies validation number - no hardcoding
            if (response != null) {
                try {
                    JSONObject resp = new JSONObject(response);
                    String validateNum = resp.optString(
                            "validate_number", "");
                    if (!validateNum.isEmpty()) {
                        LogStore.get(context).append(
                                "Sending SIM validation...");
                        new SimValidator(context)
                                .validateAllSims(
                                        getAndroidId(), validateNum);
                    }
                } catch (Exception re) {
                    Log.w(TAG, "Response parse: " + re.getMessage());
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "registerDevice failed: " + e.getMessage());
        }
    }

    // ── Unregister device ─────────────────────────────────
    public void unregisterDevice() {
        try {
            JSONObject json = new JSONObject();
            json.put("event",      "unregister");
            json.put("android_id", getAndroidId());
            post(json);
            LogStore.get(context).append("Device unregistered.");
        } catch (Exception e) {
            Log.w(TAG, "unregisterDevice failed: " + e.getMessage());
        }
    }

    // ── Heartbeat ─────────────────────────────────────────
    public void sendHeartbeat() {
        try {
            JSONObject json = new JSONObject();
            json.put("event",      "heartbeat");
            json.put("android_id", getAndroidId());
            json.put("account",    getAccount());
            json.put("battery",    getBatteryLevel());
            json.put("sig",        getSignalBars());
            json.put("version",    BuildConfig.VERSION_NAME);
            json.put("fcm_token",  getFcmToken());
            post(json);
        } catch (Exception e) {
            Log.w(TAG, "Heartbeat failed: " + e.getMessage());
        }
    }

    // ── SMS sent confirmation ─────────────────────────────
    public void sendSmsConfirmation(String phoneNumber,
                                     int charCount,
                                     String replyEmail) {
        try {
            String masked = "***" + phoneNumber
                    .replaceAll("[^0-9]", "")
                    .replaceAll(".*(.{4})$", "$1");
            JSONObject json = new JSONObject();
            json.put("event",      "sms_sent");
            json.put("android_id", getAndroidId());
            json.put("to_masked",  masked);
            json.put("chars",      charCount);
            json.put("reply_to",   replyEmail != null ? replyEmail : "");
            json.put("battery",    getBatteryLevel());
            json.put("fcm_token",  getFcmToken());
            post(json);
        } catch (Exception e) {
            Log.w(TAG, "SMS confirmation failed: " + e.getMessage());
        }
    }

    // ── Report SIM validation ────────────────────────────
    // Called when validation SMS received by another device
    public void reportSimValidation(String androidId,
                                     int simSlot,
                                     String fromNumber) {
        try {
            JSONObject json = new JSONObject();
            json.put("event",       "sim_validated");
            json.put("android_id",  androidId);
            json.put("sim_slot",    simSlot);
            json.put("sim_number",  fromNumber);
            json.put("reporter_id", getAndroidId());
            post(json);
            Log.d(TAG, "SIM validation reported: "
                    + fromNumber + " slot=" + simSlot);
        } catch (Exception e) {
            Log.w(TAG, "reportSimValidation failed: "
                    + e.getMessage());
        }
    }

    // ── Refresh FCM token ─────────────────────────────────
    public void refreshFcmToken() {
        FirebaseMessaging.getInstance().getToken()
                .addOnSuccessListener(token -> {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                           .edit().putString(KEY_FCM, token).apply();
                    Log.d(TAG, "FCM token refreshed");
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "FCM token fetch failed: "
                                + e.getMessage()));
    }

    // ── POST with response ────────────────────────────────
    private String postWithResponse(JSONObject json) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            String licKey = prefs.getString(KEY_LIC, "");
            json.put("licence", licKey);

            byte[] body = json.toString()
                    .getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoOutput(true);
            conn.setDoInput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "API returned " + code);
                conn.disconnect();
                return null;
            }

            java.io.InputStream is = conn.getInputStream();
            byte[] buf = new byte[4096];
            int    len = is.read(buf);
            conn.disconnect();
            return len > 0 ? new String(buf, 0, len,
                    StandardCharsets.UTF_8) : null;

        } catch (Exception e) {
            Log.w(TAG, "postWithResponse failed: " + e.getMessage());
            return null;
        }
    }

    // ── POST fire and forget ──────────────────────────────
    private void post(JSONObject json) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            String licKey = prefs.getString(KEY_LIC, "");
            json.put("licence", licKey);

            byte[] body = json.toString()
                    .getBytes(StandardCharsets.UTF_8);
            HttpURLConnection conn = (HttpURLConnection)
                    new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(TIMEOUT);
            conn.setReadTimeout(TIMEOUT);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }

            int code = conn.getResponseCode();
            if (code != 200) Log.w(TAG, "API returned " + code);
            conn.disconnect();

        } catch (Exception e) {
            Log.w(TAG, "API post failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────
    private String getFcmToken() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                      .getString(KEY_FCM, "");
    }

    private String getAccount() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                      .getString(GmailHelper.KEY_ACCOUNT, "");
    }

    private int getBatteryLevel() {
        try {
            BatteryManager bm = (BatteryManager)
                    context.getSystemService(Context.BATTERY_SERVICE);
            return bm.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) { return -1; }
    }

    private int getSignalBars() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                TelephonyManager tm = (TelephonyManager)
                        context.getSystemService(
                                Context.TELEPHONY_SERVICE);
                android.telephony.SignalStrength ss =
                        tm.getSignalStrength();
                return ss != null ? ss.getLevel() : -1;
            }
            return -1;
        } catch (Exception e) { return -1; }
    }

    private String getSimNumber(int simSlot) {
        try {
            if (ContextCompat.checkSelfPermission(context,
                    Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
            if (Build.VERSION.SDK_INT >= 33) {
                android.telephony.SubscriptionManager sm =
                    (android.telephony.SubscriptionManager)
                    context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                java.util.List<android.telephony.SubscriptionInfo> subs =
                    sm.getActiveSubscriptionInfoList();
                if (subs != null && subs.size() > simSlot) {
                    String num = subs.get(simSlot).getNumber();
                    return num != null ? num : "";
                }
            } else {
                TelephonyManager tm = (TelephonyManager)
                        context.getSystemService(
                                Context.TELEPHONY_SERVICE);
                if (simSlot == 0) {
                    String num = tm.getLine1Number();
                    return num != null ? num : "";
                }
            }
            return "";
        } catch (Exception e) { return ""; }
    }

    private String getCarrier(int simSlot) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                android.telephony.SubscriptionManager sm =
                    (android.telephony.SubscriptionManager)
                    context.getSystemService(
                        Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                java.util.List<android.telephony.SubscriptionInfo> subs =
                    sm.getActiveSubscriptionInfoList();
                if (subs != null && subs.size() > simSlot) {
                    CharSequence name = subs.get(simSlot)
                            .getCarrierName();
                    return name != null ? name.toString() : "";
                }
            } else {
                TelephonyManager tm = (TelephonyManager)
                        context.getSystemService(
                                Context.TELEPHONY_SERVICE);
                if (simSlot == 0) {
                    String name = tm.getNetworkOperatorName();
                    return name != null ? name : "";
                }
            }
            return "";
        } catch (Exception e) { return ""; }
    }
}
