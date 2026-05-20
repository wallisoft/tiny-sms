package com.wallisoft.tinysms;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * GmailHelper - DEPRECATED
 * All email functionality removed.
 * SMS jobs now triggered via FCM push from TinyWeb server.
 * Kept as stub to avoid refactoring remaining call sites.
 */
public class GmailHelper {

    public static final String PREFS       = "TinySMSPrefs";
    public static final String KEY_ACCOUNT = "gmail_account";

    public GmailHelper(Context ctx) {}

    public static class SmsJob {
        public String phoneNumber  = "";
        public String messageText  = "";
        public String replyToEmail = "";
    }

    public List<SmsJob> fetchPendingSmsEmails() {
        return new ArrayList<>();
    }

    public boolean sendReplyEmail(String to, String number, String body) {
        return false;
    }

    public boolean forwardInboundSms(String number, String body) {
        return false;
    }
}
