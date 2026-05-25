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
    private Button      btnLink, btnCheck, btnClear, 
                        btnExport, btnManageSenders;
 
    private SwitchCompat switchWorker;
    private TextView     tvStatus, tvLog, tvProStatus, tvReplyStatus;
    private TextView     tvStatSentToday, tvStatSentMonth,
                         tvStatRcvd, tvStatDevices, tvStatUptime;
    private long         startTime = System.currentTimeMillis();
    private ScrollView   scrollLog;

    // Wake lock
    private PowerManager.WakeLock wakeLock;

    // Background
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();
    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Runnable logRefresh = new Runnable() {
        @Override public void run() {
            refreshLog();
            handler.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupWakeLock();
        restoreState();
        wireListeners();
        executor.execute(() ->
                new ApiHelper(this).refreshFcmToken());
        requestPermissions(); // setupDeviceAuth() called from result callback

    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wakeLock != null && !wakeLock.isHeld())
            wakeLock.acquire(3600000L);
        handler.post(logRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        handler.removeCallbacks(logRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
    }

    private void setupWakeLock() {
        PowerManager pm = (PowerManager)
                getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK |
                PowerManager.ON_AFTER_RELEASE,
                "TinySMS:WakeLock");
        wakeLock.setReferenceCounted(false);
    }

    private void bindViews() {
        btnLink          = findViewById(R.id.btnLinkAccount);
        btnCheck         = findViewById(R.id.btnCheckMail);
        btnClear         = findViewById(R.id.btnClearLog);
        btnExport        = findViewById(R.id.btnExportLog);
        switchWorker     = findViewById(R.id.switchWorker);
        tvStatus         = findViewById(R.id.tvAccountStatus);
        tvLog            = findViewById(R.id.tvLog);
        tvProStatus      = findViewById(R.id.tvProStatus);
        tvStatSentToday  = findViewById(R.id.tvStatSentToday);
        tvStatSentMonth  = findViewById(R.id.tvStatSentMonth);
        tvStatRcvd       = findViewById(R.id.tvStatRcvd);
        tvStatDevices    = findViewById(R.id.tvStatDevices);
        tvStatUptime     = findViewById(R.id.tvStatUptime);
        tvReplyStatus    = findViewById(R.id.tvReplyStatus);
        scrollLog        = findViewById(R.id.scrollLog);
        btnManageSenders = findViewById(R.id.btnManageSenders);

    }

    private void setupDeviceAuth() {
        if (TinyWebAuth.isLinked(this)) {
            executor.execute(() -> {
                ApiHelper api = new ApiHelper(this);
                api.registerDevice();
                handler.post(this::restoreState);
            });
        } else {
            executor.execute(() -> {
                ApiHelper api = new ApiHelper(this);
                api.registerDevice();
                if (TinyWebAuth.isLinked(this)) {
                    handler.post(() -> {
                        restoreState();
                        String username = TinyWebAuth.getUsername(this);
                        showAccountCreatedDialog(username);
                    });
                } else {
                    handler.post(this::showLoginDialog);
                }
            });
        }
    }

    public void showLoginDialog() {
        android.widget.LinearLayout layout =
            new android.widget.LinearLayout(this);
        layout.setOrientation(
            android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);

        android.widget.EditText etUser =
            new android.widget.EditText(this);
        etUser.setHint("Username or email");
        layout.addView(etUser);

        android.widget.EditText etPass =
            new android.widget.EditText(this);
        etPass.setHint("Password");
        etPass.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(etPass);

        new AlertDialog.Builder(this)
            .setTitle("Sign in to TinyWeb")
            .setView(layout)
            .setPositiveButton("Sign In", (d, w) -> {
                String user = etUser.getText().toString().trim();
                String pass = etPass.getText().toString().trim();
                if (user.isEmpty() || pass.isEmpty()) {
                    toast("Please enter username and password");
                    return;
                }
                executor.execute(() -> {
                    new ApiHelper(this).registerDevice(user, pass);
                    handler.post(this::restoreState);
                });
            })
            .setNegativeButton("Create Account", (d, w) ->
                new TinyWebSignup(this, executor,
                    this::restoreState).show(
                    new AlertDialog.Builder(this)))
            .setNeutralButton("Cancel", null)
            .show();
    }

    private void showAccountCreatedDialog(String username) {
        new AlertDialog.Builder(this)
            .setTitle("Welcome to TinyWeb!")
            .setMessage("Account created!\n\nUsername: " + username + "\n\nManage at tiny-web.uk/dashboard")
            .setPositiveButton("Dashboard", (d, w) ->
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://tiny-web.uk/dashboard/"))))
            .setNegativeButton("OK", null)
            .show();
    }

    private void restoreState() {
        boolean linked  = TinyWebAuth.isLinked(this);
        boolean isPro   = TinyWebAuth.isPro(this);
        String  planDbg = TinyWebAuth.getPlan(this);
        LogStore.get(this).append("Plan: " + planDbg
            + " isPro:" + isPro
            + " user:" + TinyWebAuth.getUsername(this));
        String  username = TinyWebAuth.getUsername(this);
        String  userRef  = TinyWebAuth.getUserRef(this);
        SharedPreferences prefs = getSharedPreferences(
                TinyWebAuth.PREFS, MODE_PRIVATE);
        boolean replyOn = prefs.getBoolean("reply_enabled", false);

        if (linked && !username.isEmpty()) {
            tvStatus.setText("User: " + username +
                (userRef.isEmpty() ? "" : "  ·  " + userRef));
            btnCheck.setEnabled(true);
            btnManageSenders.setEnabled(true);
            btnLink.setText("✓  Connected  (tap to disconnect)");
        } else {
            tvStatus.setText("Not connected");
            btnLink.setText("Connect TinyWeb Account");
        }
        updateProStatus(isPro);
        updateReplySwitch(isPro, replyOn);
        if (isPro && replyOn) scheduleReplyWorker(this);
    }

    private void updateProStatus(boolean isPro) {
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
                    ? "Active · instant via SMS broadcast"
                    : "Tap to enable reply forwarding");
            tvReplyStatus.setTextColor(
                    replyOn ? 0xFF538d4e : 0xFF556688);
        } else {
            tvReplyStatus.setText("Pro plan required  ·  tiny-sms.uk");
            tvReplyStatus.setTextColor(0xFF556688);
        }
    }

    private void wireListeners() {
        btnLink.setOnClickListener(v -> {
            if (TinyWebAuth.isLinked(this)) {
                new AlertDialog.Builder(this)
                    .setTitle("Unlink device?")
                    .setMessage("This will disconnect this device from TinyWeb. " +
                                "You can reconnect any time.")
                    .setPositiveButton("Unlink", (d, w) -> {
                        executor.execute(() ->
                            new ApiHelper(this).unregisterDevice());
                        TinyWebAuth.clearAuth(this);
                        tvStatus.setText("Not connected");
                        btnCheck.setEnabled(false);
                        btnManageSenders.setEnabled(false);
                        btnLink.setText("Connect TinyWeb Account");
                        LogStore.get(this).append("Device unlinked.");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            } else {
                showLoginDialog();
            }
        });


        btnCheck.setOnClickListener(v -> {
            String email = TinyWebAuth.getUsername(this);
            // SnappyMail supports ?login= if configured — try it
            String url = email.contains("@")
                ? "https://tinymail.uk/?login=" + Uri.encode(email)
                : "https://tinymail.uk/";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            LogStore.get(this).append("Opening TinyMail webmail...");
            refreshLog();
        });

        btnManageSenders.setOnClickListener(v ->
            new WhitelistDialog(this, executor).show());

        switchWorker.setOnCheckedChangeListener((btn, checked) -> {
            SharedPreferences prefs = getSharedPreferences(
                    TinyWebAuth.PREFS, MODE_PRIVATE);
            boolean isPro = TinyWebAuth.isPro(this);
            if (!isPro) {
                switchWorker.setChecked(false);
                showLicenceDialog();
                return;
            }
            prefs.edit().putBoolean("reply_enabled", checked).apply();
            updateReplySwitch(isPro, checked);
            if (checked) {
                checkDefaultSmsApp();
                scheduleReplyWorker(this);
                LogStore.get(this).append("SMS reply forwarding enabled.");
            } else {
                WorkManager.getInstance(this).cancelUniqueWork(WORK_TAG);
                LogStore.get(this).append("SMS reply forwarding disabled.");
            }
        });

        btnClear.setOnClickListener(v -> {
            LogStore.get(this).clear();
            refreshLog();
        });

        btnExport.setOnClickListener(v -> showExportDialog());
        tvProStatus.setOnClickListener(v -> showLicenceDialog());

        tvReplyStatus.setOnClickListener(v -> {
            SharedPreferences prefs = getSharedPreferences(
                    TinyWebAuth.PREFS, MODE_PRIVATE);
            if (prefs.getString("licence_key", null) == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Pro Feature")
                        .setMessage("SMS Reply Forwarding is a TinySMS Pro feature.\n\n"
                                + "Visit tiny-web.uk to upgrade.")
                        .setPositiveButton("View Plans", (d, w) ->
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://tiny-sms.uk"))))
                        .setNegativeButton("Enter Key", (d, w) ->
                                showLicenceDialog())
                        .show();
            }
        });
    }

    private void checkDefaultSmsApp() {
        String defaultPkg = android.provider.Telephony.Sms.getDefaultSmsPackage(this);
        if (getPackageName().equals(defaultPkg)) {
            LogStore.get(this).append("TinySMS is default SMS app ✅");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enable Full SMS Reply Forwarding")
                .setMessage("For reliable SMS forwarding, TinySMS needs to be the default SMS app.")
                .setPositiveButton("Set as Default", (d, w) -> {
                    Intent intent = new Intent(android.provider.Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                    intent.putExtra(android.provider.Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
                    startActivity(intent);
                })
                .setNegativeButton("Not Now", null)
                .show();
    }


    private void showLicenceDialog() {
        String androidId = android.provider.Settings.Secure.getString(
                getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        boolean isPro   = TinyWebAuth.isPro(this);
        String  plan    = TinyWebAuth.getPlan(this);
        String  userRef = TinyWebAuth.getUserRef(this);

        if (isPro) {
            new AlertDialog.Builder(this)
                    .setTitle("⭐ Pro Licence Active")
                    .setMessage(
                        "Plan: " + plan +
                        (userRef.isEmpty() ? "" : "\nRef: " + userRef) +
                        "\n\nDevice ID:\n" + androidId)
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Refresh", (d, w) -> {
                        executor.execute(() -> {
                            new ApiHelper(this).registerDevice();
                            handler.post(this::restoreState);
                        });
                    })
                    .setNegativeButton("Unlink Plan", (d, w) -> {
                        getSharedPreferences(TinyWebAuth.PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(TinyWebAuth.KEY_PLAN, "free")
                            .putBoolean("reply_enabled", false)
                            .apply();
                        updateProStatus(false);
                        updateReplySwitch(false, false);
                        WorkManager.getInstance(this).cancelUniqueWork(WORK_TAG);
                        LogStore.get(this).append("Pro plan unlinked.");
                    })
                    .show();
        } else {
            // Try auto-fetch first, fall back to manual entry
            LicenceHelper.fetchLicence(this, new LicenceHelper.LicenceCallback() {
                @Override public void onFound(String licenceKey, String plan) {
                    handler.post(() -> {
                        getSharedPreferences(TinyWebAuth.PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(TinyWebAuth.KEY_PLAN, plan)
                            .apply();
                        updateProStatus(true);
                        updateReplySwitch(true, false);
                        LogStore.get(MainActivity.this).append("Pro licence auto-applied: " + plan);
                        new AlertDialog.Builder(MainActivity.this)
                            .setTitle("⭐ Pro Licence Found!")
                            .setMessage("Plan: " + plan + "\nDevice ID:\n" + androidId)
                            .setPositiveButton("Done", null)
                            .show();
                    });
                }
                @Override public void onNotFound(String url) {
                    handler.post(() -> showManualLicenceDialog(androidId, url));
                }
                @Override public void onError() {
                    handler.post(() -> showManualLicenceDialog(androidId,
                            "https://tiny-sms.uk"));
                }
            });
        }
    }

    private void showManualLicenceDialog(String androidId, String buyUrl) {
        new AlertDialog.Builder(this)
                .setTitle("Pro Licence Key")
                .setMessage("No Pro licence found for this account.\n\nDevice ID:\n" + androidId)
                .setPositiveButton("Get Pro", (d, w) ->
                    startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(buyUrl))))
                .setNegativeButton("Cancel", null)
                .show();
    }



    private void showManualLicenceDialog(SharedPreferences prefs, String current, String androidId, String buyUrl) {
        final EditText input = new EditText(this);
        input.setHint("Enter Pro licence key");
        input.setText(current);

        new AlertDialog.Builder(this)
                .setTitle("Pro Licence Key")
                .setMessage("Enter your licence key from tiny-sms.uk\n\nDevice ID:\n" + androidId)
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String key = input.getText().toString().trim();
                    prefs.edit().putString("licence_key", key).apply();
                    boolean isPro = !key.isEmpty();
                    updateProStatus(isPro);
                    updateReplySwitch(isPro, false);
                    if (isPro) {
                        executor.execute(() -> new ApiHelper(this).registerDevice());
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showExportDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Export Activity Log")
                .setMessage("Share the activity log.")
                .setPositiveButton("Export", (d, w) -> doExportLog())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doExportLog() {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.UK).format(new Date());
            File outFile = new File(getCacheDir(), "TinySMS_log_" + ts + ".txt");
            try (FileWriter fw = new FileWriter(outFile)) {
                fw.write(LogStore.get(this).read());
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", outFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Export via..."));
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void refreshLog() {
        tvLog.setText(LogStore.get(this).read());
        scrollLog.post(() ->
            scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
        updateStats();
    }

    private void updateStats() {
        // Count from log
        String log = LogStore.get(this).read();
        String[] lines = log.split("\n");
        int sentToday = 0, sentMonth = 0, rcvd = 0;
        String today = new java.text.SimpleDateFormat(
            "MM-dd", java.util.Locale.UK)
            .format(new java.util.Date());
        String month = new java.text.SimpleDateFormat(
            "MM", java.util.Locale.UK)
            .format(new java.util.Date());

        for (String line : lines) {
            if (line.contains("SMS SENT")) {
                sentMonth++;
                if (line.contains("[" + today) ||
                    line.contains(today)) sentToday++;
            }
            if (line.contains("SMS IN ←") ||
                line.contains("SMS REPLY")) rcvd++;
        }

        // Uptime
        long uptimeSecs =
            (System.currentTimeMillis() - startTime) / 1000;
        String uptime;
        if (uptimeSecs < 3600)
            uptime = (uptimeSecs/60) + "m";
        else if (uptimeSecs < 86400)
            uptime = (uptimeSecs/3600) + "h";
        else
            uptime = (uptimeSecs/86400) + "d";

        if (tvStatSentToday != null) {
            tvStatSentToday.setText(String.valueOf(sentToday));
            tvStatSentMonth.setText(String.valueOf(sentMonth));
            tvStatRcvd.setText(String.valueOf(rcvd));
            tvStatDevices.setText("1"); // local device
            tvStatUptime.setText(uptime);
        }
    }

    public static void scheduleReplyWorker(Context ctx) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(MailCheckWorker.class, POLL_MINS, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    private void requestPermissions() {
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
            // setupDeviceAuth() called from onRequestPermissionsResult
        } else {
            // Permissions already granted — go straight to device auth
            setupDeviceAuth();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,
                permissions, grantResults);
        if (requestCode != 1) return;
 
        // Check SMS permission granted
        boolean smsGranted = false;
        for (int i = 0; i < permissions.length; i++) {
            if (Manifest.permission.SEND_SMS.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                smsGranted = true;
                break;
            }
        }
 
        if (smsGranted) {
            LogStore.get(this).append("SMS permission granted.");
            // Now safe to setup device auth — validation SMS will work
            setupDeviceAuth();
        } else {
            LogStore.get(this).append(
                "SMS permission denied — some features unavailable.");
            // Still setup device auth but validation SMS won't fire
            setupDeviceAuth();
        }
    }
 
    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
