package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.services.gmail.model.ModifyMessageRequest;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * GmailHelper
 * Fetches pending SMS emails and sends reply emails.
 * Checks X-TinySMS-Device header for multi-device routing.
 */
public class GmailHelper {

    private static final String TAG         = "GmailHelper";
    public  static final String PREFS       = "TinySMSPrefs";
    public  static final String KEY_ACCOUNT = "gmail_account";
    private static final String USER        = "me";
    private static final String APP_NAME    = "TinySMS";

    private final Context context;
    private       Gmail   service;

    public GmailHelper(Context context) {
        this.context = context.getApplicationContext();
        this.service = buildService();
    }

    // -----------------------------------------------------------------------
    // Build Gmail service
    // -----------------------------------------------------------------------
    private Gmail buildService() {
        try {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
            if (account == null) return null;

            GoogleAccountCredential credential = GoogleAccountCredential
                    .usingOAuth2(context,
                            Collections.singletonList(GmailScopes.GMAIL_MODIFY));
            credential.setSelectedAccount(account.getAccount());

            return new Gmail.Builder(
                    AndroidHttp.newCompatibleTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential)
                    .setApplicationName(APP_NAME)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "buildService failed: " + e.getMessage());
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // SmsJob - represents a pending SMS to send
    // -----------------------------------------------------------------------
    public static class SmsJob {
        public String phoneNumber;
        public String messageText;
        public String replyToEmail;
        public String gmailMessageId;
    }

    // -----------------------------------------------------------------------
    // Fetch pending SMS emails
    // Filters by subject:sms and unread
    // Checks X-TinySMS-Device header for multi-device routing
    // -----------------------------------------------------------------------
    public List<SmsJob> fetchPendingSmsEmails() {
        List<SmsJob> jobs = new ArrayList<>();
        if (service == null) {
            LogStore.get(context).append("Gmail service not available.");
            return jobs;
        }

        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        String myDeviceId = prefs.getString("device_id", "");

        try {
            // Search for unread emails with subject "sms"
            ListMessagesResponse response = service.users().messages()
                    .list(USER)
                    .setQ("subject:sms is:unread")
                    .setMaxResults(10L)
                    .execute();

            List<Message> messages = response.getMessages();
            if (messages == null || messages.isEmpty()) {
                LogStore.get(context).append("No pending SMS emails.");
                return jobs;
            }

            for (Message msg : messages) {
                try {
                    // Get full message with headers
                    Message full = service.users().messages()
                            .get(USER, msg.getId())
                            .setFormat("full")
                            .execute();

                    // ── Check device header ───────────────────────────
                    String deviceHeader = getHeader(full, "X-TinySMS-Device");
                    if (deviceHeader != null && !deviceHeader.isEmpty()
                            && !myDeviceId.isEmpty()
                            && !deviceHeader.equals(myDeviceId)) {
                        // Not for this device - skip silently
                        Log.d(TAG, "Skipping email for device: " + deviceHeader);
                        continue;
                    }

                    // ── Parse body ────────────────────────────────────
                    String body = extractBody(full);
                    if (body == null || body.isEmpty()) continue;

                    // ── Extract sms:NUMBER ────────────────────────────
                    String phoneNumber = null;
                    String messageText = "";

                    String[] lines = body.split("\\r?\\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.toLowerCase().startsWith("sms:") ||
                            line.toLowerCase().startsWith("sms :")) {
                            phoneNumber = line.replaceFirst(
                                    "(?i)sms\\s*:\\s*", "")
                                    .replaceAll("[^0-9+]", "").trim();
                            // Message is remaining lines
                            StringBuilder sb = new StringBuilder();
                            for (int j = i + 1; j < lines.length; j++) {
                                if (lines[j].trim().startsWith(">")) break;
                                sb.append(lines[j]).append("\n");
                            }
                            messageText = sb.toString().trim();
                            break;
                        }
                    }

                    if (phoneNumber == null || phoneNumber.isEmpty()) continue;
                    if (messageText.isEmpty()) continue;

                    // ── Get reply-to address ──────────────────────────
                    String replyTo = getHeader(full, "X-TinySMS-From");
                    if (replyTo == null || replyTo.isEmpty()) {
                        replyTo = getHeader(full, "Reply-To");
                    }
                    if (replyTo == null || replyTo.isEmpty()) {
                        replyTo = getHeader(full, "From");
                    }

                    // ── Mark as read ──────────────────────────────────
                    ModifyMessageRequest markRead = new ModifyMessageRequest()
                            .setRemoveLabelIds(
                                    Collections.singletonList("UNREAD"));
                    service.users().messages()
                            .modify(USER, msg.getId(), markRead)
                            .execute();

                    // ── Build job ─────────────────────────────────────
                    SmsJob job = new SmsJob();
                    job.phoneNumber    = phoneNumber;
                    job.messageText    = messageText;
                    job.replyToEmail   = replyTo;
                    job.gmailMessageId = msg.getId();
                    jobs.add(job);

                    LogStore.get(context).append(
                            "Found SMS job → " + phoneNumber
                            + (deviceHeader != null ? " [device matched]" : ""));

                } catch (Exception e) {
                    Log.w(TAG, "Error processing message: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "fetchPendingSmsEmails failed: " + e.getMessage());
            LogStore.get(context).append("Gmail fetch error: " + e.getMessage());
        }

        return jobs;
    }

    // -----------------------------------------------------------------------
    // Send reply email back to original sender
    // -----------------------------------------------------------------------
    public boolean sendReplyEmail(String toEmail, String fromNumber,
                                   String replyText) {
        if (service == null) return false;
        try {
            Properties props = new Properties();
            Session session  = Session.getDefaultInstance(props, null);
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                           .getString(KEY_ACCOUNT, "me")));
            mime.addRecipient(javax.mail.Message.RecipientType.TO,
                    new InternetAddress(toEmail));
            mime.setSubject("Reply from " + fromNumber);
            mime.setText("SMS reply from " + fromNumber + ":\n\n" + replyText
                    + "\n\n---\nDelivered via TinySMS | tiny-web.uk");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            mime.writeTo(baos);
            String encoded = android.util.Base64.encodeToString(
                    baos.toByteArray(),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);

            Message message = new Message();
            message.setRaw(encoded);
            service.users().messages().send(USER, message).execute();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "sendReplyEmail failed: " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Forward inbound SMS to email
    // Called from SmsPoller when a new SMS arrives
    // -----------------------------------------------------------------------
    public boolean forwardInboundSms(String fromNumber, String smsBody) {
        if (service == null) return false;
        try {
            // Look up who to forward to from ReplyTracker
            ReplyTracker tracker = ReplyTracker.get(context);
            String forwardTo     = tracker.lookup(fromNumber);

            // If no mapping, forward to the account owner
            if (forwardTo == null || forwardTo.isEmpty()) {
                forwardTo = context.getSharedPreferences(
                        PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_ACCOUNT, null);
            }
            if (forwardTo == null) return false;

            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            String fromAccount = prefs.getString(KEY_ACCOUNT, "tinysms");

            Properties props = new Properties();
            Session session  = Session.getDefaultInstance(props, null);
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(fromAccount));
            mime.addRecipient(javax.mail.Message.RecipientType.TO,
                    new InternetAddress(forwardTo));
            mime.setSubject("SMS from " + fromNumber);
            mime.setText(
                    "Inbound SMS from: " + fromNumber + "\n\n"
                    + smsBody
                    + "\n\n---"
                    + "\nTo reply, email sms@tiny-web.uk"
                    + "\nSubject: sms"
                    + "\n\nsms:" + fromNumber
                    + "\nYour reply here"
                    + "\n\nTinySMS | tiny-web.uk");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            mime.writeTo(baos);
            String encoded = android.util.Base64.encodeToString(
                    baos.toByteArray(),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);

            Message message = new Message();
            message.setRaw(encoded);
            service.users().messages().send(USER, message).execute();

            LogStore.get(context).append(
                    "SMS IN ← " + fromNumber + " → forwarded to " + forwardTo);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "forwardInboundSms failed: " + e.getMessage());
            LogStore.get(context).append(
                    "Inbound forward failed: " + e.getMessage());
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Extract plain text body from Gmail message
    // -----------------------------------------------------------------------
    private String extractBody(Message message) {
        try {
            if (message.getPayload() == null) return "";
            return extractFromPart(message.getPayload());
        } catch (Exception e) {
            return "";
        }
    }

    private String extractFromPart(com.google.api.services.gmail.model.MessagePart part) {
        if (part == null) return "";

        String mimeType = part.getMimeType() != null ?
                part.getMimeType().toLowerCase() : "";

        // Direct text/plain
        if (mimeType.equals("text/plain") && part.getBody() != null
                && part.getBody().getData() != null) {
            byte[] decoded = android.util.Base64.decode(
                    part.getBody().getData(), android.util.Base64.URL_SAFE);
            return new String(decoded);
        }

        // Recurse into parts - prefer text/plain
        if (part.getParts() != null) {
            for (com.google.api.services.gmail.model.MessagePart child
                    : part.getParts()) {
                String childType = child.getMimeType() != null ?
                        child.getMimeType().toLowerCase() : "";
                if (childType.equals("text/plain")) {
                    String result = extractFromPart(child);
                    if (!result.isEmpty()) return result;
                }
            }
            // Fallback to HTML
            for (com.google.api.services.gmail.model.MessagePart child
                    : part.getParts()) {
                String result = extractFromPart(child);
                if (!result.isEmpty()) return result;
            }
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // Get header value by name
    // -----------------------------------------------------------------------
    private String getHeader(Message message, String name) {
        if (message.getPayload() == null
                || message.getPayload().getHeaders() == null) return null;
        for (MessagePartHeader h : message.getPayload().getHeaders()) {
            if (h.getName().equalsIgnoreCase(name)) return h.getValue();
        }
        return null;
    }
}
