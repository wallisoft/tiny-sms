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
    private Button       btnLink, btnCheck, btnClear, btnExport;
    private SwitchCompat switchWorker;
    private TextView     tvStatus, tvLog, tvProStatus, tvReplyStatus;
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
        setupDeviceAuth();
        requestPermissions();
        restoreState();
        wireListeners();
        executor.execute(() ->
                new ApiHelper(this).refreshFcmToken());
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
        btnLink       = findViewById(R.id.btnLinkAccount);
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
        String  username = TinyWebAuth.getUsername(this);
        String  userRef  = TinyWebAuth.getUserRef(this);
        SharedPreferences prefs = getSharedPreferences(
                TinyWebAuth.PREFS, MODE_PRIVATE);
        boolean replyOn = prefs.getBoolean("reply_enabled", false);

        if (linked && !username.isEmpty()) {
            tvStatus.setText("User: " + username +
                (userRef.isEmpty() ? "" : "  ·  " + userRef));
            btnCheck.setEnabled(true);
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
            startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://tiny-web.uk/dashboard/")));
            LogStore.get(this).append("Opening TinyWeb console...");
            refreshLog();
        });

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
        SharedPreferences prefs = getSharedPreferences(TinyWebAuth.PREFS, MODE_PRIVATE);
        String current   = prefs.getString("licence_key", "");
        String androidId = android.provider.Settings.Secure.getString(getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID);
        boolean isPro = current != null && !current.isEmpty();

        if (isPro) {
            new AlertDialog.Builder(this)
                    .setTitle("⭐ Pro Licence Active")
                    .setMessage("Licence: " + current + "\n\nDevice ID:\n" + androidId)
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Clear", (d, w) -> {
                        prefs.edit().remove("licence_key").putBoolean("reply_enabled", false).apply();
                        updateProStatus(false);
                        updateReplySwitch(false, false);
                        WorkManager.getInstance(this).cancelUniqueWork(WORK_TAG);
                        LogStore.get(this).append("Pro licence removed.");
                    })
                    .show();
        } else {
            showManualLicenceDialog(prefs, "", androidId, "https://tiny-sms.uk");
        }
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
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
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
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), 1);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
