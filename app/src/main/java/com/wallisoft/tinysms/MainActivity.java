package com.wallisoft.tinysms;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
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

    private static final String WORK_TAG    = "tinysms_mail_check";
    private static final int    POLL_MINS   = 5;
    private static final int    REQ_SMS     = 1;
    private static final int    REQ_STORAGE = 2;

    private static final String[] SMS_PERMS = {
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS
    };

    // UI
    private Button       btnLink, btnCheck, btnClear, btnExport;
    private SwitchCompat switchWorker;
    private TextView     tvStatus, tvLog;
    private ScrollView   scrollLog;

    // Google Sign-In
    private GoogleSignInClient            signInClient;
    private ActivityResultLauncher<Intent> signInLauncher;

    // Background
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         handler  = new Handler(Looper.getMainLooper());

    private final Runnable logRefresh = new Runnable() {
        @Override public void run() {
            refreshLog();
            handler.postDelayed(this, 3000);
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
        setupGoogleSignIn();
        requestSmsPermissions();
        restoreState();
        wireListeners();
    }

    @Override protected void onResume() { super.onResume(); handler.post(logRefresh); }
    @Override protected void onPause()  { super.onPause();  handler.removeCallbacks(logRefresh); }

    // -----------------------------------------------------------------------
    // View binding
    // -----------------------------------------------------------------------
    private void bindViews() {
        btnLink      = findViewById(R.id.btnLinkGmail);
        btnCheck     = findViewById(R.id.btnCheckMail);
        btnClear     = findViewById(R.id.btnClearLog);
        btnExport    = findViewById(R.id.btnExportLog);
        switchWorker = findViewById(R.id.switchWorker);
        tvStatus     = findViewById(R.id.tvAccountStatus);
        tvLog        = findViewById(R.id.tvLog);
        scrollLog    = findViewById(R.id.scrollLog);
    }

    // -----------------------------------------------------------------------
    // Google Sign-In
    // -----------------------------------------------------------------------
    private void setupGoogleSignIn() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope("https://www.googleapis.com/auth/gmail.modify"))
                .build();

        signInClient = GoogleSignIn.getClient(this, gso);

        signInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> GoogleSignIn.getSignedInAccountFromIntent(result.getData())
                        .addOnSuccessListener(this::onSignInSuccess)
                        .addOnFailureListener(e -> {
                            LogStore.get(this).append("Sign-in failed: " + e.getMessage());
                            toast("Sign-in failed: " + e.getMessage());
                        })
        );
    }

    private void onSignInSuccess(GoogleSignInAccount account) {
        String email = account.getEmail();
        getSharedPreferences(GmailHelper.PREFS, MODE_PRIVATE)
                .edit().putString(GmailHelper.KEY_ACCOUNT, email).apply();
        tvStatus.setText("Signed in: " + email);
        btnCheck.setEnabled(true);
        btnLink.setText("✓  Gmail Linked  (tap to unlink)");
        LogStore.get(this).append("Gmail linked: " + email);
        toast("Linked: " + email);
    }

    // -----------------------------------------------------------------------
    // Restore persisted state
    // -----------------------------------------------------------------------
    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(GmailHelper.PREFS, MODE_PRIVATE);
        String  account  = prefs.getString(GmailHelper.KEY_ACCOUNT, null);
        boolean workerOn = prefs.getBoolean("worker_enabled", false);

        if (account != null) {
            tvStatus.setText("Signed in: " + account);
            btnCheck.setEnabled(true);
            btnLink.setText("✓  Gmail Linked  (tap to unlink)");
        }
        switchWorker.setChecked(workerOn);
    }

    // -----------------------------------------------------------------------
    // Listeners
    // -----------------------------------------------------------------------
    private void wireListeners() {

        btnLink.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(GmailHelper.PREFS, MODE_PRIVATE);
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
                List<GmailHelper.SmsJob> jobs = gmail.fetchPendingSmsEmails();
                dispatchJobs(jobs);

                // Inbound poll too
                SmsPoller poller = new SmsPoller(this);
                List<SmsPoller.SmsReply> replies = poller.pollNewReplies();
                processReplies(gmail, replies);

                handler.post(() -> {
                    btnCheck.setEnabled(true);
                    btnCheck.setText("📬  Check Mail Now");
                    refreshLog();
                });
            });
        });

        switchWorker.setOnCheckedChangeListener((btn, checked) -> {
            getSharedPreferences(GmailHelper.PREFS, MODE_PRIVATE)
                    .edit().putBoolean("worker_enabled", checked).apply();
            if (checked) {
                scheduleWorker(this);
                LogStore.get(this).append("Auto-check enabled (" + POLL_MINS + " min).");
            } else {
                WorkManager.getInstance(this).cancelUniqueWork(WORK_TAG);
                LogStore.get(this).append("Auto-check disabled.");
            }
        });

        btnClear.setOnClickListener(v -> {
            LogStore.get(this).clear();
            refreshLog();
        });

        // ----------------------------------------------------------------
        // Export log - show permission rationale dialog first, then share
        // ----------------------------------------------------------------
        btnExport.setOnClickListener(v -> showExportDialog());
    }

    // -----------------------------------------------------------------------
    // Export log - user must explicitly approve via dialog
    // -----------------------------------------------------------------------
    private void showExportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Export Activity Log")
                .setMessage(
                    "This will create a plain text file of the activity log and open " +
                    "the Android share sheet so you can send it by email, save to " +
                    "Downloads, or share via any app.\n\n" +
                    "The log contains SMS phone numbers, timestamps, and routing " +
                    "information. Only share it with authorised personnel.\n\n" +
                    "Do you want to continue?")
                .setPositiveButton("Export", (d, w) -> doExportLog())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doExportLog() {
        try {
            String logText = LogStore.get(this).read();
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(new Date());
            String filename = "TinySMS_log_" + ts + ".txt";

            // Write to app cache dir - no storage permission needed
            File outFile = new File(getCacheDir(), filename);
            try (FileWriter fw = new FileWriter(outFile)) {
                fw.write("Tiny-SMS Activity Log\n");
                fw.write("Exported: " + new Date() + "\n");
                fw.write("Device: " + Build.MODEL + "\n");
                fw.write("App version: 1.0\n");
                fw.write("tiny-web.uk\n");
                fw.write("─".repeat(40) + "\n\n");
                fw.write(logText);
            }

            // Share via FileProvider - no WRITE_EXTERNAL_STORAGE needed
            Uri uri = FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    outFile);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Tiny-SMS Log " + ts);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Export log via..."));

            LogStore.get(this).append("Log exported: " + filename);

        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
            LogStore.get(this).append("Export error: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // SMS dispatch helpers
    // -----------------------------------------------------------------------
    private void dispatchJobs(List<GmailHelper.SmsJob> jobs) {
        if (jobs.isEmpty()) {
            LogStore.get(this).append("No pending SMS emails found.");
            return;
        }
        android.telephony.SmsManager sms = android.telephony.SmsManager.getDefault();
        ReplyTracker tracker = ReplyTracker.get(this);
        for (GmailHelper.SmsJob job : jobs) {
            try {
                ArrayList<String> parts = sms.divideMessage(job.messageText);
                if (parts.size() == 1) {
                    sms.sendTextMessage(job.phoneNumber, null, job.messageText, null, null);
                } else {
                    sms.sendMultipartTextMessage(job.phoneNumber, null, parts, null, null);
                }
                tracker.store(job.phoneNumber, job.replyToEmail);
                LogStore.get(this).append("SMS SENT → " + job.phoneNumber
                        + "  [" + job.messageText.length() + " chars]");
            } catch (Exception e) {
                LogStore.get(this).append("SMS FAIL → " + job.phoneNumber + ": " + e.getMessage());
            }
        }
    }

    private void processReplies(GmailHelper gmail, List<SmsPoller.SmsReply> replies) {
        ReplyTracker tracker = ReplyTracker.get(this);
        for (SmsPoller.SmsReply reply : replies) {
            String email = tracker.lookup(reply.number);
            if (email == null) continue;
            LogStore.get(this).append("SMS IN ← " + reply.number + ": " + reply.body);
            boolean ok = gmail.sendReplyEmail(email, reply.number, reply.body);
            LogStore.get(this).append(ok ? "EMAIL SENT → " + email : "EMAIL FAIL → " + email);
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
    // Schedule worker (also called from BootReceiver)
    // -----------------------------------------------------------------------
    public static void scheduleWorker(Context ctx) {
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
        boolean allGranted = true;
        for (String p : SMS_PERMS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false; break;
            }
        }
        if (!allGranted) ActivityCompat.requestPermissions(this, SMS_PERMS, REQ_SMS);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
