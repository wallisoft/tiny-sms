package com.wallisoft.tinysms;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
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
 * Wraps all Gmail API operations:
 *   - building the service from a stored account name
 *   - fetching unread SMS-subject emails
 *   - marking messages read
 *   - sending reply emails back to sender
 */
public class GmailHelper {

    private static final String TAG = "GmailHelper";
    private static final String APP_NAME = "Tiny-SMS";
    public  static final String PREFS     = "tinysms_prefs";
    public  static final String KEY_ACCOUNT = "gmail_account";

    private final Context context;

    public GmailHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    // -----------------------------------------------------------------------
    // Build Gmail service
    // -----------------------------------------------------------------------
    public Gmail buildService() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String accountName = prefs.getString(KEY_ACCOUNT, null);
        if (accountName == null) return null;

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                context,
                Collections.singletonList(GmailScopes.GMAIL_MODIFY)
        );
        credential.setSelectedAccountName(accountName);

        return new Gmail.Builder(
                new NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                credential
        ).setApplicationName(APP_NAME).build();
    }

    // -----------------------------------------------------------------------
    // Fetch unread emails whose subject == "sms" (case-insensitive)
    // Returns list of SmsJob records ready to be dispatched
    // -----------------------------------------------------------------------
    public List<SmsJob> fetchPendingSmsEmails() {
        List<SmsJob> jobs = new ArrayList<>();
        try {
            Gmail service = buildService();
            if (service == null) return jobs;

            // Search for unread messages with subject "sms"
            ListMessagesResponse resp = service.users().messages()
                    .list("me")
                    .setQ("is:unread subject:sms")
                    .setMaxResults(20L)
                    .execute();

            if (resp.getMessages() == null) return jobs;

            for (Message msg : resp.getMessages()) {
                Message full = service.users().messages()
                        .get("me", msg.getId())
                        .setFormat("full")
                        .execute();

                SmsJob job = parseMessage(full);
                if (job != null) {
                    jobs.add(job);
                    // Mark as read immediately so we don't reprocess
                    markRead(service, msg.getId());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "fetchPendingSmsEmails error", e);
        }
        return jobs;
    }

    // -----------------------------------------------------------------------
    // Parse a Gmail message into an SmsJob
    // Expected format:  Subject: sms
    //                   Body first line: sms:01234 567890
    //                   Remaining body: the message text
    // -----------------------------------------------------------------------
    private SmsJob parseMessage(Message message) {
        try {
            String subject = "";
            String from    = "";

            for (MessagePartHeader h : message.getPayload().getHeaders()) {
                switch (h.getName().toLowerCase()) {
                    case "subject": subject = h.getValue(); break;
                    case "from":    from    = h.getValue(); break;
                }
            }

            if (!subject.trim().equalsIgnoreCase("sms")) return null;

            String body = extractBody(message.getPayload());
            if (body == null || body.isEmpty()) return null;

            // First line must be  sms:NUMBER
            String[] lines = body.trim().split("\n", 2);
            String firstLine = lines[0].trim();
            if (!firstLine.toLowerCase().startsWith("sms:")) return null;

            String number  = firstLine.substring(4).trim().replaceAll("\\s+", "");
            String msgText = lines.length > 1 ? lines[1].trim() : "";

            // Extract reply-to address
            String replyTo = parseEmailAddress(from);

            return new SmsJob(message.getId(), number, msgText, replyTo);

        } catch (Exception e) {
            Log.e(TAG, "parseMessage error", e);
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // Extract plain text body from message payload (handles multipart)
    // -----------------------------------------------------------------------
    private String extractBody(MessagePart part) {
        if (part == null) return "";

        String mimeType = part.getMimeType();

        if ("text/plain".equals(mimeType) && part.getBody() != null
                && part.getBody().getData() != null) {
            return new String(Base64.decode(
                    part.getBody().getData().replace('-', '+').replace('_', '/'),
                    Base64.DEFAULT));
        }

        if (part.getParts() != null) {
            for (MessagePart child : part.getParts()) {
                String result = extractBody(child);
                if (result != null && !result.isEmpty()) return result;
            }
        }
        return "";
    }

    // -----------------------------------------------------------------------
    // Mark a message as read
    // -----------------------------------------------------------------------
    private void markRead(Gmail service, String msgId) {
        try {
            ModifyMessageRequest req = new ModifyMessageRequest()
                    .setRemoveLabelIds(Collections.singletonList("UNREAD"));
            service.users().messages().modify("me", msgId, req).execute();
        } catch (Exception e) {
            Log.e(TAG, "markRead error", e);
        }
    }

    // -----------------------------------------------------------------------
    // Send a reply email (used when an SMS reply arrives)
    // -----------------------------------------------------------------------
    public boolean sendReplyEmail(String toAddress, String smsNumber,
                                   String replyText) {
        try {
            Gmail service = buildService();
            if (service == null) return false;

            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String fromAccount = prefs.getString(KEY_ACCOUNT, "me");

            Properties props = new Properties();
            Session session  = Session.getDefaultInstance(props, null);

            MimeMessage email = new MimeMessage(session);
            email.setFrom(new InternetAddress(fromAccount));
            email.addRecipient(javax.mail.Message.RecipientType.TO,
                    new InternetAddress(toAddress));
            email.setSubject("SMS reply from " + smsNumber);
            email.setText(replyText);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            email.writeTo(buffer);
            String encodedEmail = Base64.encodeToString(buffer.toByteArray(),
                    Base64.URL_SAFE | Base64.NO_WRAP);

            Message gmailMsg = new Message();
            gmailMsg.setRaw(encodedEmail);
            service.users().messages().send("me", gmailMsg).execute();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "sendReplyEmail error", e);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Utility: pull bare email address from "Name <addr>" format
    // -----------------------------------------------------------------------
    public static String parseEmailAddress(String from) {
        if (from == null) return "";
        int lt = from.indexOf('<');
        int gt = from.indexOf('>');
        if (lt >= 0 && gt > lt) return from.substring(lt + 1, gt).trim();
        return from.trim();
    }

    // -----------------------------------------------------------------------
    // Simple data class for one pending SMS dispatch
    // -----------------------------------------------------------------------
    public static class SmsJob {
        public final String messageId;
        public final String phoneNumber;
        public final String messageText;
        public final String replyToEmail;

        public SmsJob(String messageId, String phoneNumber,
                      String messageText, String replyToEmail) {
            this.messageId   = messageId;
            this.phoneNumber = phoneNumber;
            this.messageText = messageText;
            this.replyToEmail = replyToEmail;
        }
    }
}
