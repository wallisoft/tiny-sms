package com.wallisoft.tinysms;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String WORK_TAG  = "tinysms_reply_poll";
    private static final int    POLL_MINS = 5;
    private static final String[] SMS_PERMS = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
    };

    // UI
    private Button       btnLink, btnCheck, btnClear, btnExport;
    private SwitchCompat switchWorker;
    private TextView     tvStatus, tvLog, tvProStatus, tvReplyStatus;
    private ScrollView   scrollLog;

    private SmsObserver smsObserver;

    // Google Sign-In
    private GoogleSignInClient             signInClient;
    private ActivityResultLauncher<Intent> signInLauncher;

    // Wake lock
    private PowerManager.WakeLock wakeLock;

    // Background
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         handler  = new Handler(Looper.getMainLooper());

    private final Runnable logRefresh = new Runnable() {
        @Override public void run() {
            refreshLog();
            handler.postDelayed(this, 2000);
        }
    };

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupWakeLock();
        setupGoogleSignIn();
        requestSmsPermissions();
        restoreState();
        wireListeners();

        // Refresh FCM token on start
        executor.execute(() -> new ApiHelper(this).refreshFcmToken());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire(3600000L);
        handler.post(logRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        handler.removeCallbacks(logRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // -----------------------------------------------------------------------
    // Wake lock
    // -----------------------------------------------------------------------
    private void setupWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "TinySMS:WakeLock");
        wakeLock.setReferenceCounted(false);
    }

    private void startSmsObserver() {
        if (smsObserver != null) return; // already running
        smsObserver = new SmsObserver(this, handler);
        // Watch ALL sms not just inbox - Samsung compatibility
        getContentResolver().registerContentObserver(
                android.net.Uri.parse("content://sms"),
                true,
                smsObserver);
        LogStore.get(this).append("SMS observer started.");
    }
     
    private void stopSmsObserver() {
        if (smsObserver == null) return;
        getContentResolver().unregisterContentObserver(smsObserver);
        smsObserver = null;
        LogStore.get(this).append("SMS observer stopped.");
    }
 

    // -----------------------------------------------------------------------
    // View binding
    // -----------------------------------------------------------------------
    private void bindViews() {
        btnLink       = findViewById(R.id.btnLinkGmail);
        btnCheck      = findViewById(R.id.btnCheckMail);
        btnClear      = findViewById(R.id.btnClearLog);
        btnExport     = findViewById(R.id.btnExportLog);
        switchWorker  = findViewById(R.id.switchWorker);
        tvStatus      = findViewById(R.id.tvAccountStatus);
        tvLog         = findViewById(R.id.tvLog);
        tvProStatus   = findViewById(R.id.tvProStatus);
        tvReplyStatus = findViewById(R.id.tvReplyStatus);
        scrollLog     = findViewById(R.id.scrollLog);
    }

    // -----------------------------------------------------------------------
    // Google Sign-In
    // -----------------------------------------------------------------------
    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(
                        "https://www.googleapis.com/auth/gmail.modify"))
                .build();
        signInClient   = GoogleSignIn.getClient(this, gso);
        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> GoogleSignIn.getSignedInAccountFromIntent(
                                result.getData())
                        .addOnSuccessListener(this::onSignInSuccess)
                        .addOnFailureListener(e -> {
                            LogStore.get(this).append(
                                    "Sign-in failed: " + e.getMessage());
                            toast("Sign-in failed: " + e.getMessage());
                        })
        );
    }

    private void onSignInSuccess(GoogleSignInAccount account) {
        String email = account.getEmail();
        SharedPreferences prefs = getSharedPreferences(
                GmailHelper.PREFS, MODE_PRIVATE);
        prefs.edit().putString(GmailHelper.KEY_ACCOUNT, email).apply();

        tvStatus.setText("Signed in: " + email);
        btnCheck.setEnabled(true);
        btnLink.setText("✓  Gmail Linked  (tap to unlink)");
        LogStore.get(this).append("Gmail linked: " + email);
        toast("Linked: " + email);

        // Register device with server
        String deviceId = getOrCreateDeviceId();
        executor.execute(() ->
                new ApiHelper(this).registerDevice(email));
    }

    // -----------------------------------------------------------------------
    // Device ID
    // -----------------------------------------------------------------------
    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(
                GmailHelper.PREFS, MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = "TSMS-" + java.util.UUID.randomUUID()
                    .toString().replace("-", "").substring(0, 16);
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    // -----------------------------------------------------------------------
    // Restore state
    // -----------------------------------------------------------------------
    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(
                GmailHelper.PREFS, MODE_PRIVATE);
        String  account  = prefs.getString(GmailHelper.KEY_ACCOUNT, null);
        boolean replyOn  = prefs.getBoolean("reply_enabled", false);
        String  licKey   = prefs.getString("licence_key", null);
        boolean isPro    = licKey != null && !licKey.isEmpty();

        if (account != null) {
            tvStatus.setText("Signed in: " + account);
            btnCheck.setEnabled(true);
            btnLink.setText("✓  Gmail Linked  (tap to unlink)");
        }

        updateProStatus(licKey);
        updateReplySwitch(isPro, replyOn);

        if (isPro && replyOn) {
            // Only set baseline if never set before
            SharedPreferences sp = getSharedPreferences(
                    GmailHelper.PREFS, MODE_PRIVATE);
            scheduleReplyWorker(this);
            startSmsObserver();
        }
    }

    private void updateProStatus(String licKey) {
        boolean isPro = licKey != null && !licKey.isEmpty();
        if (isPro) {
            tvProStatus.setText("⭐ Pro licence active  ·  tap to manage");
            tvProStatus.setTextColor(0xFF538d4e);
        } else {
            tvProStatus.setText("Free plan  ·  tap to enter licence key");
            tvProStatus.setTextColor(0xFF888888);
        }
    }

    private void updateReplySwitch(boolean isPro, boolean replyOn) {
        switchWorker.setEnabled(isPro);
        switchWorker.setChecked(isPro && replyOn);
        if (isPro) {
            tvReplyStatus.setText(replyOn
                    ? "Active  ·  polling every " + POLL_MINS + " min"
                    : "Tap to enable reply forwarding");
            tvReplyStatus.setTextColor(replyOn ? 0xFF538d4e : 0xFF556688);
        } else {
            tvReplyStatus.setText("Pro plan required  ·  tiny-sms.uk");
            tvReplyStatus.setTextColor(0xFF556688);
        }
    }

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    private void wireListeners() {

        btnLink.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(
                    GmailHelper.PREFS, MODE_PRIVATE);
            if (prefs.getString(GmailHelper.KEY_ACCOUNT, null) != null) {
                signInClient.signOut().addOnCompleteListener(t -> {
                    prefs.edit().remove(GmailHelper.KEY_ACCOUNT).apply();
                    tvStatus.setText("Not signed in");
                    btnCheck.setEnabled(false);
                    btnLink.setText("🔗  Link Gmail Account");
                    LogStore.get(this).append("Gmail unlinked.");
                });
            } else {
                signInLauncher.launch(signInClient.getSignInIntent());
            }
        });

        btnCheck.setOnClickListener(v -> {
            btnCheck.setEnabled(false);
            btnCheck.setText("Checking...");
            LogStore.get(this).append("Manual check triggered.");
            executor.execute(() -> {
                GmailHelper gmail = new GmailHelper(this);
                dispatchJobs(gmail.fetchPendingSmsEmails());

                // Always poll SMS inbox
                // Needed for SIM validation (free) and replies (Pro)
                processReplies(gmail,
                        new SmsPoller(this).pollNewReplies());

                handler.post(() -> {
                    btnCheck.setEnabled(true);
                    btnCheck.setText("📬  Check Mail Now");
                    refreshLog();
                });
            });
        });

        // Reply switch - Pro only
        switchWorker.setOnCheckedChangeListener((btn, checked) -> {
            SharedPreferences prefs = getSharedPreferences(
                    GmailHelper.PREFS, MODE_PRIVATE);
            boolean isPro = prefs.getString("licence_key", null) != null;

            if (!isPro) {
                // Should never happen since switch disabled for free
                switchWorker.setChecked(false);
                showLicenceDialog();
                return;
            }

            prefs.edit().putBoolean("reply_enabled", checked).apply();
            updateReplySwitch(true, checked);

            if (checked) {
                SmsPoller.setBaseline(this);
                checkDefaultSmsApp();
                scheduleReplyWorker(this);
                startSmsObserver();
                LogStore.get(this).append("SMS reply forwarding enabled.");
            } else {
                WorkManager.getInstance(this).cancelUniqueWork(WORK_TAG);
                stopSmsObserver();
                LogStore.get(this).append("SMS reply forwarding disabled.");
            }
        });

        btnClear.setOnClickListener(v -> {
            LogStore.get(this).clear();
            refreshLog();
        });

        btnExport.setOnClickListener(v -> showExportDialog());

        tvProStatus.setOnClickListener(v -> showLicenceDialog());

        // Tapping reply status when not Pro shows upgrade prompt
        tvReplyStatus.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(
                    GmailHelper.PREFS, MODE_PRIVATE);
            if (prefs.getString("licence_key", null) == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Pro Feature")
                        .setMessage(
                                "SMS Reply Forwarding is a TinySMS Pro feature.\n\n"
                                        + "When enabled, replies to your SMS messages are "
                                        + "automatically forwarded back to the original "
                                        + "email sender.\n\n"
                                        + "Visit tiny-sms.uk to upgrade.")
                        .setPositiveButton("View Plans", (d, w) -> {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://tiny-sms.uk")));
                        })
                        .setNegativeButton("Enter Key", (d, w) ->
                                showLicenceDialog())
                        .show();
            }
        });
    }

    // -----------------------------------------------------------------------
    // Licence dialog
    // -----------------------------------------------------------------------
// ── Replace showLicenceDialog() in MainActivity.java ──────

    private void showLicenceDialog() {
        SharedPreferences prefs = getSharedPreferences(
                GmailHelper.PREFS, MODE_PRIVATE);
        String current   = prefs.getString("licence_key", "");
        String androidId = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        boolean isPro = current != null && !current.isEmpty();

        if (isPro) {
            // ── Pro management dialog ─────────────────────────
            String masked = current.length() > 12
                    ? current.substring(0, 8) + "..."
                    + current.substring(current.length() - 4)
                    : current;

            new AlertDialog.Builder(this)
                    .setTitle("⭐ Pro Licence Active")
                    .setMessage(
                            "Licence: " + masked + "\n\n"
                                    + "Device ID:\n" + androidId + "\n\n"
                                    + "Manage your subscription at tiny-sms.uk")
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Clear", (d, w) ->
                            confirmClearLicence(prefs))
                    .setNegativeButton("Dashboard", (d, w) ->
                            startActivity(new Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse(
                                            "https://tiny-sms.uk/dashboard/"))))
                    .show();

        } else {
            // ── Auto-fetch licence from server ────────────────
            // Show brief loading toast
            toast("Checking for licence...");

            LicenceHelper.fetchLicence(this,
                    new LicenceHelper.LicenceCallback() {

                        @Override
                        public void onFound(String key, String plan) {
                            prefs.edit().putString("licence_key", key).apply();
                            handler.post(() -> {
                                updateProStatus(key);
                                updateReplySwitch(true, false);
                                LogStore.get(MainActivity.this).append(
                                        "Pro licence activated automatically!");
                                toast("⭐ Pro licence activated!");
                                // Re-register to update server
                                String account = prefs.getString(
                                        GmailHelper.KEY_ACCOUNT, "");
                                executor.execute(() ->
                                        new ApiHelper(MainActivity.this)
                                                .registerDevice(account));
                            });
                        }

                        @Override
                        public void onNotFound(String url) {
                            handler.post(() -> showManualLicenceDialog(
                                    prefs, "", androidId, url));
                        }

                        @Override
                        public void onError() {
                            handler.post(() -> showManualLicenceDialog(
                                    prefs, "", androidId,
                                    "https://tiny-sms.uk"));
                        }
                    });
        }
    }

    // ── Confirm licence removal ───────────────────────────────
    private void confirmClearLicence(SharedPreferences prefs) {
        new AlertDialog.Builder(this)
                .setTitle("Remove Pro Licence?")
                .setMessage("This will disable Pro features including "
                        + "SMS Reply Forwarding.")
                .setPositiveButton("Remove", (d, w) -> {
                    prefs.edit().remove("licence_key").apply();
                    prefs.edit().putBoolean("reply_enabled", false).apply();
                    updateProStatus(null);
                    updateReplySwitch(false, false);
                    WorkManager.getInstance(this)
                            .cancelUniqueWork(WORK_TAG);
                    LogStore.get(this).append("Pro licence removed.");
                    toast("Pro licence removed.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Manual licence entry ──────────────────────────────────
    private void showManualLicenceDialog(SharedPreferences prefs,
                                         String current,
                                         String androidId,
                                         String buyUrl) {
        final android.widget.EditText input =
                new android.widget.EditText(this);
        input.setHint("Enter Pro licence key");
        input.setText(current);
        input.setPadding(40, 20, 40, 20);

        new AlertDialog.Builder(this)
                .setTitle("Pro Licence Key")
                .setMessage(
                        "Enter your licence key from tiny-sms.uk\n\n"
                                + "Device ID:\n" + androidId)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String key = input.getText().toString().trim();
                    prefs.edit().putString("licence_key", key).apply();
                    boolean isPro   = !key.isEmpty();
                    boolean replyOn = prefs.getBoolean(
                            "reply_enabled", false);
                    updateProStatus(isPro ? key : null);
                    updateReplySwitch(isPro, replyOn);
                    LogStore.get(this).append(isPro
                            ? "Pro licence saved."
                            : "Pro licence removed.");
                    if (isPro) {
                        String account = prefs.getString(
                                GmailHelper.KEY_ACCOUNT, "");
                        executor.execute(() ->
                                new ApiHelper(this)
                                        .registerDevice(account));
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Get Pro", (d, w) ->
                        startActivity(new Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse(buyUrl))))
                .show();
    }
    // -----------------------------------------------------------------------
    // Export log
    // -----------------------------------------------------------------------
    private void showExportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Export Activity Log")
                .setMessage(
                        "Share the activity log.\n\n"
                                + "The log contains phone numbers and timestamps. "
                                + "Only share with authorised personnel.")
                .setPositiveButton("Export", (d, w) -> doExportLog())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doExportLog() {
        try {
            String logText  = LogStore.get(this).read();
            String ts       = new SimpleDateFormat("yyyyMMdd_HHmmss",
                    Locale.UK).format(new Date());
            String filename = "TinySMS_log_" + ts + ".txt";
            File outFile    = new File(getCacheDir(), filename);

            try (FileWriter fw = new FileWriter(outFile)) {
                fw.write("TinySMS Activity Log\n");
                fw.write("Exported: " + new Date() + "\n");
                fw.write("Device: " + Build.MODEL + "\n");
                fw.write("App: v" + BuildConfig.VERSION_NAME + "\n");
                fw.write("tiny-web.uk\n");
                fw.write("─".repeat(40) + "\n\n");
                fw.write(logText);
            }

            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", outFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "TinySMS Log " + ts);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Export log via..."));
            LogStore.get(this).append("Log exported.");
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // SMS dispatch
    // -----------------------------------------------------------------------
    private void dispatchJobs(List<GmailHelper.SmsJob> jobs) {
        if (jobs.isEmpty()) {
            LogStore.get(this).append("No pending SMS emails.");
            return;
        }
        android.telephony.SmsManager sms =
                android.telephony.SmsManager.getDefault();
        ReplyTracker tracker = ReplyTracker.get(this);
        ApiHelper    api     = new ApiHelper(this);

        for (GmailHelper.SmsJob job : jobs) {
            try {
                ArrayList<String> parts = sms.divideMessage(job.messageText);
                if (parts.size() == 1) {
                    sms.sendTextMessage(job.phoneNumber, null,
                            job.messageText, null, null);
                } else {
                    sms.sendMultipartTextMessage(job.phoneNumber, null,
                            parts, null, null);
                }
                tracker.store(job.phoneNumber, job.replyToEmail);
                LogStore.get(this).append(
                        "SMS SENT → " + job.phoneNumber
                                + "  [" + job.messageText.length() + " chars]");
                api.sendSmsConfirmation(job.phoneNumber,
                        job.messageText.length(), job.replyToEmail);
            } catch (Exception e) {
                LogStore.get(this).append(
                        "SMS FAIL → " + job.phoneNumber
                                + ": " + e.getMessage());
            }
        }
    }

    private void processReplies(GmailHelper gmail,
                                List<SmsPoller.SmsReply> replies) {
        ReplyTracker tracker = ReplyTracker.get(this);
        for (SmsPoller.SmsReply reply : replies) {
            String email = tracker.lookup(reply.number);
            if (email == null) {
                // Forward to account owner
                gmail.forwardInboundSms(reply.number, reply.body);
                continue;
            }
            boolean ok = gmail.sendReplyEmail(
                    email, reply.number, reply.body);
            LogStore.get(this).append(
                    "SMS REPLY ← " + reply.number
                            + (ok ? " → forwarded to " + email
                            : " → forward failed"));
        }
    }

    // -----------------------------------------------------------------------
    // Log refresh
    // -----------------------------------------------------------------------
    private void refreshLog() {
        tvLog.setText(LogStore.get(this).read());
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    // -----------------------------------------------------------------------
    // Schedule reply polling worker (Pro only)
    // -----------------------------------------------------------------------
    public static void scheduleReplyWorker(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                MailCheckWorker.class, POLL_MINS, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_TAG, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------
    private void requestSmsPermissions() {
        List<String> needed = new ArrayList<>();
        for (String p : SMS_PERMS) {
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this, needed.toArray(new String[0]), 1);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void checkDefaultSmsApp() {
        String defaultPkg = android.provider.Telephony.Sms
                .getDefaultSmsPackage(this);
        if (getPackageName().equals(defaultPkg)) {
            LogStore.get(this).append("TinySMS is default SMS app ✅");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enable Full SMS Reply Forwarding")
                .setMessage(
                        "For reliable SMS forwarding, TinySMS needs "
                                + "to be the default SMS app.\n\n"
                                + "Your messages will still appear in Samsung "
                                + "Messages as normal.\n\n"
                                + "You can change back at any time in:\n"
                                + "Settings → Apps → Default apps → SMS")
                .setPositiveButton("Set as Default", (d, w) -> {
                    Intent intent = new Intent(
                            android.provider.Telephony.Sms.Intents
                                    .ACTION_CHANGE_DEFAULT);
                    intent.putExtra(
                            android.provider.Telephony.Sms.Intents
                                    .EXTRA_PACKAGE_NAME,
                            getPackageName());
                    startActivity(intent);
                })
                .setNegativeButton("Not Now", null)
                .show();
    }
}
