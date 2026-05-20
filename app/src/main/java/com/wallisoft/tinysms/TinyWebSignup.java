package com.wallisoft.tinysms;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TinyWebSignup
 * In-app account creation - no browser needed.
 * Username + password only.
 * Phone pre-filled from SIM (auto-validated).
 * No email verification required at this stage.
 */
public class TinyWebSignup {

    private static final String API_URL =
        "https://accounts.tiny-web.uk/api/register.php";

    private final Context         ctx;
    private final ExecutorService exec;
    private final Handler         handler;
    private final Runnable        onSuccess;

    private EditText etUsername, etPassword;
    private TextView tvUserStatus, tvPhone, tvError;
    private Button   btnCreate;

    private String  detectedPhone = "";
    private boolean usernameOk    = false;
    private boolean checkPending  = false;

    public TinyWebSignup(Context ctx,
                         ExecutorService exec,
                         Runnable onSuccess) {
        this.ctx       = ctx;
        this.exec      = exec;
        this.handler   = new Handler(Looper.getMainLooper());
        this.onSuccess = onSuccess;
    }

    public void show(AlertDialog.Builder parentBuilder) {
        View v = LayoutInflater.from(ctx)
            .inflate(android.R.layout.simple_list_item_2, null);

        // Build dialog content programmatically
        android.widget.LinearLayout layout =
            new android.widget.LinearLayout(ctx);
        layout.setOrientation(
            android.widget.LinearLayout.VERTICAL);
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
        tvPhone.setText("📱 " + (detectedPhone.isEmpty()
            ? "SIM number will be detected on first SMS"
            : detectedPhone + "  ✅ auto-verified"));
        tvPhone.setTextColor(0xFF538d4e);
        layout.addView(tvPhone);

        // Username
        etUsername = new EditText(ctx);
        etUsername.setHint("Username (min 3 chars)");
        etUsername.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT);
        etUsername.setPadding(16, 12, 16, 12);
        etUsername.setBackgroundResource(
            android.R.drawable.edit_text);
        layout.addView(etUsername);

        // Username status
        tvUserStatus = new TextView(ctx);
        tvUserStatus.setTextSize(12);
        tvUserStatus.setPadding(4, 4, 0, 12);
        layout.addView(tvUserStatus);

        // Password
        etPassword = new EditText(ctx);
        etPassword.setHint("Password (min 6 chars)");
        etPassword.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setPadding(16, 12, 16, 12);
        layout.addView(etPassword);

        // Error
        tvError = new TextView(ctx);
        tvError.setTextSize(13);
        tvError.setTextColor(0xFFd63b28);
        tvError.setPadding(0, 12, 0, 0);
        layout.addView(tvError);

        // Username availability check on each keypress
        etUsername.addTextChangedListener(new TextWatcher() {
            private final Handler h = new Handler(Looper.getMainLooper());
            private Runnable pending;
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a){}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c){}
            @Override public void afterTextChanged(Editable s) {
                String u = s.toString().toLowerCase().replaceAll("[^a-z0-9_]","");
                if (!u.equals(s.toString())) {
                    etUsername.setText(u);
                    etUsername.setSelection(u.length());
                    return;
                }
                usernameOk = false;
                updateCreateBtn();
                if (u.length() < 3) {
                    tvUserStatus.setText("");
                    setUserBorder(false);
                    return;
                }
                tvUserStatus.setText("Checking...");
                tvUserStatus.setTextColor(0xFF9ca3af);
                if (pending != null) h.removeCallbacks(pending);
                pending = () -> checkUsername(u);
                h.postDelayed(pending, 400);
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setView(layout)
            .setPositiveButton("Create Account", null)
            .setNegativeButton("Sign In Instead", (d, w) ->
                ((MainActivity)ctx).showLoginDialog())
            .create();
        dialog.show();
        // Override positive button to validate first
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v2 -> {
                String user = etUsername.getText()
                    .toString().trim();
                String pass = etPassword.getText()
                    .toString().trim();
                if (!usernameOk) {
                    tvError.setText(
                        "Please choose a valid username");
                    return;
                }
                if (pass.length() < 6) {
                    tvError.setText(
                        "Password must be at least 6 characters");
                    return;
                }
                doRegister(dialog, user, pass);
            });
    }

    private void checkUsername(String username) {
        exec.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("action",   "check_username");
                json.put("username", username);

                java.net.URL url = new java.net.URL(API_URL);
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection)url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty(
                    "Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);

                byte[] b = json.toString().getBytes("UTF-8");
                conn.getOutputStream().write(b);

                java.io.InputStream is =
                    conn.getResponseCode() < 400
                    ? conn.getInputStream()
                    : conn.getErrorStream();
                java.util.Scanner sc =
                    new java.util.Scanner(is).useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next() : "";
                JSONObject r = new JSONObject(resp);
                boolean available = r.optBoolean("available", false);

                handler.post(() -> {
                    usernameOk = available;
                    setUserBorder(available);
                    tvUserStatus.setText(available
                        ? "✅ " + username + " is available"
                        : "❌ " + username + " is taken");
                    tvUserStatus.setTextColor(available
                        ? 0xFF538d4e : 0xFFd63b28);
                    updateCreateBtn();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    tvUserStatus.setText("Could not check");
                    tvUserStatus.setTextColor(0xFF9ca3af);
                });
            }
        });
    }

    private void doRegister(android.app.Dialog dialog,
                             String username, String password) {
        tvError.setText("Creating account...");
        exec.execute(() -> {
            try {
                JSONObject json = new JSONObject();
                json.put("action",   "register");
                json.put("username", username);
                json.put("password", password);
                json.put("phone",    detectedPhone);
                json.put("android_id",
                    android.provider.Settings.Secure.getString(ctx.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID));

                java.net.URL url = new java.net.URL(API_URL);
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection)url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty(
                    "Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                byte[] b = json.toString().getBytes("UTF-8");
                conn.getOutputStream().write(b);

                java.io.InputStream is =
                    conn.getResponseCode() < 400
                    ? conn.getInputStream()
                    : conn.getErrorStream();
                java.util.Scanner sc =
                    new java.util.Scanner(is).useDelimiter("\\A");
                String resp = sc.hasNext() ? sc.next() : "";
                JSONObject r = new JSONObject(resp);

                if (r.optBoolean("ok", false)) {
                    // Save auth and register device
                    String uname = r.optString("username", username);
                    int    uid   = r.optInt("user_id", 0);
                    String uref  = r.optString("user_ref", "");
                    String plan  = r.optString("plan", "free");
                    TinyWebAuth.saveAuth(ctx, uname, uid, uref, plan);

                    // Register device
                    new ApiHelper(ctx).registerDevice();

                    handler.post(() -> {
                        dialog.dismiss();
                        LogStore.get(ctx).append(
                            "Account created: " + uname);
                        onSuccess.run();
                    });
                } else {
                    String err = r.optString("error",
                        "Registration failed");
                    handler.post(() -> tvError.setText(err));
                }
            } catch (Exception e) {
                handler.post(() -> tvError.setText(
                    "Error: " + e.getMessage()));
            }
        });
    }

    private String getSimNumber() {
        try {
            SubscriptionManager sm = (SubscriptionManager)
                ctx.getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE);
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

    private void setUserBorder(boolean ok) {
        etUsername.setBackgroundColor(
            ok ? 0x1538d4e : 0x15d63b28);
    }

    private void updateCreateBtn() {
        // Button state updated via dialog override
    }
}
