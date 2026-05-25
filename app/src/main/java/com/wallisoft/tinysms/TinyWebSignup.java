package com.wallisoft.tinysms;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * TinyWebSignup
 * In-app account creation - no browser needed.
 * Username + password only.
 * Phone pre-filled from SIM (auto-validated).
 * Live validation: red border until username available (>3 chars)
 * and password >= 6 chars. Create button disabled until both valid.
 */
public class TinyWebSignup {

    private static final String API_URL =
        "https://accounts.tiny-web.uk/api/register.php";

    private final Context         ctx;
    private final ExecutorService exec;
    private final Handler         handler;
    private final Runnable        onSuccess;

    private EditText etUsername, etPassword;
    private TextView tvUserStatus, tvPassStatus, tvPhone, tvError;
    private Button   btnCreate;

    private String  detectedPhone = "";
    private boolean usernameOk    = false;
    private boolean passwordOk    = false;

    public TinyWebSignup(Context ctx,
                         ExecutorService exec,
                         Runnable onSuccess) {
        this.ctx       = ctx;
        this.exec      = exec;
        this.handler   = new Handler(Looper.getMainLooper());
        this.onSuccess = onSuccess;
    }

    public void show(AlertDialog.Builder ignored) {
        android.widget.LinearLayout layout =
            new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 8);

        // Title
        TextView tvTitle = new TextView(ctx);
        tvTitle.setText("Create TinyWeb Account");
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 24);
        layout.addView(tvTitle);

        // Phone (pre-filled, read-only)
        tvPhone = new TextView(ctx);
        tvPhone.setTextSize(13);
        tvPhone.setPadding(0, 0, 0, 16);
        detectedPhone = getSimNumber();
        tvPhone.setText("SIM: " + (detectedPhone.isEmpty()
            ? "will be detected on first SMS"
            : detectedPhone + "  \u2705 auto-verified"));
        tvPhone.setTextColor(0xFF538d4e);
        layout.addView(tvPhone);

        // Username field
        etUsername = new EditText(ctx);
        etUsername.setHint("Username (min 4 chars, a-z 0-9 _)");
        etUsername.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etUsername.setPadding(16, 12, 16, 12);
        setFieldBorder(etUsername, BorderState.NEUTRAL);
        layout.addView(etUsername);

        // Username status
        tvUserStatus = new TextView(ctx);
        tvUserStatus.setTextSize(12);
        tvUserStatus.setPadding(4, 4, 0, 12);
        layout.addView(tvUserStatus);

        // Password field
        etPassword = new EditText(ctx);
        etPassword.setHint("Password (min 6 chars)");
        etPassword.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setPadding(16, 12, 16, 12);
        setFieldBorder(etPassword, BorderState.NEUTRAL);
        layout.addView(etPassword);

        // Password status
        tvPassStatus = new TextView(ctx);
        tvPassStatus.setTextSize(12);
        tvPassStatus.setPadding(4, 4, 0, 12);
        layout.addView(tvPassStatus);

        // Error
        tvError = new TextView(ctx);
        tvError.setTextSize(13);
        tvError.setTextColor(0xFFd63b28);
        tvError.setPadding(0, 4, 0, 0);
        layout.addView(tvError);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setView(layout)
            .setPositiveButton("Create Account", null)
            .setNegativeButton("Sign In Instead", (d, w) ->
                ((MainActivity) ctx).showLoginDialog())
            .create();
        dialog.show();

        btnCreate = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        btnCreate.setEnabled(false);

        // Override positive button — validate before submitting
        btnCreate.setOnClickListener(v -> {
            String user = etUsername.getText().toString().trim();
            String pass = etPassword.getText().toString().trim();
            if (!usernameOk) {
                tvError.setText("Please choose a valid, available username");
                return;
            }
            if (!passwordOk) {
                tvError.setText("Password must be at least 6 characters");
                return;
            }
            doRegister(dialog, user, pass);
        });

        // ── Username watcher ─────────────────────────────────
        etUsername.addTextChangedListener(new TextWatcher() {
            private final Handler h = new Handler(Looper.getMainLooper());
            private Runnable pending;

            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override public void afterTextChanged(Editable s) {
                // Sanitise: lowercase alphanumeric + underscore only
                String raw = s.toString();
                String clean = raw.toLowerCase().replaceAll("[^a-z0-9_]", "");
                if (!clean.equals(raw)) {
                    etUsername.setText(clean);
                    etUsername.setSelection(clean.length());
                    return;
                }

                usernameOk = false;
                updateCreateBtn();

                if (clean.length() < 4) {
                    tvUserStatus.setText(clean.isEmpty() ? ""
                        : "At least 4 characters required");
                    tvUserStatus.setTextColor(0xFFd63b28);
                    setFieldBorder(etUsername, clean.isEmpty()
                        ? BorderState.NEUTRAL : BorderState.BAD);
                    return;
                }

                // Debounce availability check
                tvUserStatus.setText("Checking\u2026");
                tvUserStatus.setTextColor(0xFF9ca3af);
                setFieldBorder(etUsername, BorderState.NEUTRAL);
                if (pending != null) h.removeCallbacks(pending);
                pending = () -> checkUsername(clean);
                h.postDelayed(pending, 450);
            }
        });

        // ── Password watcher ─────────────────────────────────
        etPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override public void afterTextChanged(Editable s) {
                int len = s.toString().length();
                if (len == 0) {
                    passwordOk = false;
                    setFieldBorder(etPassword, BorderState.NEUTRAL);
                    tvPassStatus.setText("");
                } else if (len < 6) {
                    passwordOk = false;
                    setFieldBorder(etPassword, BorderState.BAD);
                    tvPassStatus.setText((6 - len) + " more character"
                        + (6 - len == 1 ? "" : "s") + " needed");
                    tvPassStatus.setTextColor(0xFFd63b28);
                } else {
                    passwordOk = true;
                    setFieldBorder(etPassword, BorderState.GOOD);
                    tvPassStatus.setText("Password looks good");
                    tvPassStatus.setTextColor(0xFF538d4e);
                }
                updateCreateBtn();
            }
        });
    }

    // ── Border states ─────────────────────────────────────────────────────────
    private enum BorderState { NEUTRAL, GOOD, BAD }

    private void setFieldBorder(EditText field, BorderState state) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(8f);
        int stroke, fill;
        switch (state) {
            case GOOD:
                stroke = 0xFF538d4e;
                fill   = 0x0A538d4e;
                break;
            case BAD:
                stroke = 0xFFd63b28;
                fill   = 0x0Ad63b28;
                break;
            default:
                stroke = 0xFF444466;
                fill   = 0x00000000;
                break;
        }
        gd.setStroke(2, stroke);
        gd.setColor(fill);
        field.setBackground(gd);
        field.setPadding(16, 12, 16, 12);
    }

    private void updateCreateBtn() {
        if (btnCreate != null)
            btnCreate.setEnabled(usernameOk && passwordOk);
    }

    // ── Username availability check ───────────────────────────────────────────
    private void checkUsername(String username) {
        exec.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("action",   "check_username");
                json.put("username", username);

                java.net.URL url = new java.net.URL(API_URL);
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.getOutputStream().write(
                    json.toString().getBytes("UTF-8"));

                java.io.InputStream is = conn.getResponseCode() < 400
                    ? conn.getInputStream() : conn.getErrorStream();
                java.util.Scanner sc =
                    new java.util.Scanner(is).useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next() : "";
                JSONObject r = new JSONObject(resp);
                boolean available = r.optBoolean("available", false);

                handler.post(() -> {
                    usernameOk = available;
                    setFieldBorder(etUsername,
                        available ? BorderState.GOOD : BorderState.BAD);
                    tvUserStatus.setText(available
                        ? "\u2713 " + username + " is available"
                        : "\u2717 " + username + " is already taken");
                    tvUserStatus.setTextColor(
                        available ? 0xFF538d4e : 0xFFd63b28);
                    updateCreateBtn();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    tvUserStatus.setText("Could not check — try again");
                    tvUserStatus.setTextColor(0xFF9ca3af);
                    setFieldBorder(etUsername, BorderState.NEUTRAL);
                });
            }
        });
    }

    // ── Register ──────────────────────────────────────────────────────────────
    private void doRegister(android.app.Dialog dialog,
                             String username, String password) {
        tvError.setText("Creating account\u2026");
        btnCreate.setEnabled(false);
        exec.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("action",     "register");
                json.put("username",   username);
                json.put("password",   password);
                json.put("phone",      detectedPhone);
                json.put("android_id",
                    android.provider.Settings.Secure.getString(
                        ctx.getContentResolver(),
                        android.provider.Settings.Secure.ANDROID_ID));

                java.net.URL url = new java.net.URL(API_URL);
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.getOutputStream().write(
                    json.toString().getBytes("UTF-8"));

                java.io.InputStream is = conn.getResponseCode() < 400
                    ? conn.getInputStream() : conn.getErrorStream();
                java.util.Scanner sc =
                    new java.util.Scanner(is).useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next() : "";
                JSONObject r = new JSONObject(resp);

                if (r.optBoolean("ok", false)) {
                    String uname = r.optString("username", username);
                    int    uid   = r.optInt("user_id", 0);
                    String uref  = r.optString("user_ref", "");
                    String plan  = r.optString("plan", "free");
                    TinyWebAuth.saveAuth(ctx, uname, uid, uref, plan);

                    // Register with credentials so device links immediately
                    // without requiring sign out / sign in
                    new ApiHelper(ctx).registerDevice(username, password);


                    handler.post(() -> {
                        dialog.dismiss();
                        LogStore.get(ctx).append("Account created: " + uname);
                        onSuccess.run();
                        // Restart app for clean state
                        android.content.Intent intent =
                            ctx.getPackageManager()
                               .getLaunchIntentForPackage(ctx.getPackageName());
                        if (intent != null) {
                            intent.addFlags(
                                android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(intent);
                        }
                    });

                } else {
                    String err = r.optString("error", "Registration failed");
                    handler.post(() -> {
                        tvError.setText(err);
                        btnCreate.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                handler.post(() -> {
                    tvError.setText("Error: " + e.getMessage());
                    btnCreate.setEnabled(true);
                });
            }
        });
    }

    // ── SIM number ────────────────────────────────────────────────────────────
    private String getSimNumber() {
        try {
            SubscriptionManager sm = (SubscriptionManager)
                ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs =
                sm.getActiveSubscriptionInfoList();
            if (subs != null && !subs.isEmpty()) {
                String num = subs.get(0).getNumber();
                return num != null ? num : "";
            }
        } catch (Exception e) {
            // Permission not granted yet
        }
        return "";
    }
}
