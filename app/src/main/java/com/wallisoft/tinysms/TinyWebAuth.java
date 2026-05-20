package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * TinyWebAuth - replaces GmailHelper auth
 * Stores username/password in EncryptedSharedPreferences
 * Device registered by android_id (passwordless after first auth)
 */
public class TinyWebAuth {

    public static final String PREFS       = "TinySMSPrefs";
    public static final String KEY_USERNAME = "tinyweb_username";
    public static final String KEY_USER_ID  = "tinyweb_user_id";
    public static final String KEY_USER_REF = "tinyweb_user_ref";
    public static final String KEY_PLAN     = "tinyweb_plan";
    public static final String KEY_LINKED   = "tinyweb_linked";

    // Legacy key kept for migration
    public static final String KEY_ACCOUNT  = "gmail_account";

    public static boolean isLinked(Context ctx) {
        return getPrefs(ctx).getBoolean(KEY_LINKED, false);
    }

    public static String getUsername(Context ctx) {
        return getPrefs(ctx).getString(KEY_USERNAME, "");
    }

    public static String getUserRef(Context ctx) {
        return getPrefs(ctx).getString(KEY_USER_REF, "");
    }

    public static String getPlan(Context ctx) {
        return getPrefs(ctx).getString(KEY_PLAN, "free");
    }

    public static int getUserId(Context ctx) {
        return getPrefs(ctx).getInt(KEY_USER_ID, 0);
    }

    public static void saveAuth(Context ctx, String username,
                                 int userId, String userRef,
                                 String plan) {
        getPrefs(ctx).edit()
            .putString(KEY_USERNAME, username)
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USER_REF, userRef)
            .putString(KEY_PLAN, plan)
            .putBoolean(KEY_LINKED, true)
            .apply();
    }

    public static void clearAuth(Context ctx) {
        getPrefs(ctx).edit()
            .remove(KEY_USERNAME)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_REF)
            .remove(KEY_PLAN)
            .putBoolean(KEY_LINKED, false)
            .apply();
    }

    public static boolean isPro(Context ctx) {
        String plan = getPlan(ctx);
        return plan != null && (plan.contains("pro") ||
                                plan.contains("org") ||
                                plan.contains("business"));
    }

    private static SharedPreferences getPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
