// ═══════════════════════════════════════════════════════════
// WhitelistDialog.java — 
// ═══════════════════════════════════════════════════════════

package com.wallisoft.tinysms;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/**
 * WhitelistDialog
 * Manage SMS sender whitelist from the app
 * Launched from MainActivity tvReplyStatus long press
 */
public class WhitelistDialog {

    private static final String API_URL =
        "https://tiny-web.uk/api/whitelist.php";

    private final Context         ctx;
    private final ExecutorService exec;
    private final Handler         handler;
    private       LinearLayout    listLayout;
    private       TextView        tvStatus;

    public WhitelistDialog(Context ctx, ExecutorService exec) {
        this.ctx     = ctx;
        this.exec    = exec;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void show() {
        // Build dialog layout
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 32, 48, 8);

        // Title info
        TextView tvInfo = new TextView(ctx);
        tvInfo.setText("Emails allowed to send SMS via your device.\n" +
                       "* means anyone can send.");
        tvInfo.setTextSize(13);
        tvInfo.setTextColor(0xFF888888);
        tvInfo.setPadding(0, 0, 0, 16);
        root.addView(tvInfo);

        // Status / loading
        tvStatus = new TextView(ctx);
        tvStatus.setText("Loading\u2026");
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(0xFF9ca3af);
        root.addView(tvStatus);

        // Scrollable list container
        android.widget.ScrollView scroll =
            new android.widget.ScrollView(ctx);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 400));
        listLayout = new LinearLayout(ctx);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listLayout);
        root.addView(scroll);

        AlertDialog dialog = new AlertDialog.Builder(ctx)
            .setTitle("SMS Sender Whitelist")
            .setView(root)
            .setPositiveButton("Add sender", null)
            .setNegativeButton("Close", null)
            .create();
        dialog.show();

        // Wire Add button
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> showAddDialog(dialog));

        // Load whitelist
        loadWhitelist();
    }

    private void loadWhitelist() {
        exec.execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("action",     "list");
                req.put("android_id", getAndroidId());
                String resp = post(req);
                if (resp == null) throw new Exception("No response");
                JSONObject r = new JSONObject(resp);
                if (!r.optBoolean("ok", false))
                    throw new Exception(r.optString("error", "Failed"));
                JSONArray list = r.getJSONArray("whitelist");
                handler.post(() -> renderList(list));
            } catch (Exception e) {
                handler.post(() -> {
                    tvStatus.setText("Error: " + e.getMessage());
                });
            }
        });
    }

    private void renderList(JSONArray list) {
        listLayout.removeAllViews();
        tvStatus.setVisibility(View.GONE);

        if (list.length() == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("No entries — add one below.");
            empty.setTextColor(0xFF9ca3af);
            empty.setTextSize(13);
            listLayout.addView(empty);
            return;
        }

        for (int i = 0; i < list.length(); i++) {
            try {
                JSONObject item = list.getJSONObject(i);
                int    id     = item.getInt("id");
                String sender = item.getString("sender");
                String label  = item.optString("label", sender);

                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 12, 0, 12);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                // Sender info
                LinearLayout info = new LinearLayout(ctx);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvSender = new TextView(ctx);
                tvSender.setText(sender.equals("*") ? "* Anyone" : sender);
                tvSender.setTextSize(14);
                tvSender.setTextColor(0xFF111827);
                tvSender.setTypeface(null,
                    android.graphics.Typeface.BOLD);
                info.addView(tvSender);

                if (!label.equals(sender) && !sender.equals("*")) {
                    TextView tvLabel = new TextView(ctx);
                    tvLabel.setText(label);
                    tvLabel.setTextSize(11);
                    tvLabel.setTextColor(0xFF9ca3af);
                    info.addView(tvLabel);
                }
                row.addView(info);

                // Delete button (not for wildcard if it's the only entry)
                android.widget.Button btnDel =
                    new android.widget.Button(ctx);
                btnDel.setText("Remove");
                btnDel.setTextSize(11);
                btnDel.setTextColor(0xFFd63b28);
                btnDel.setBackgroundColor(0x00000000);
                btnDel.setOnClickListener(v ->
                    confirmDelete(id, sender));
                row.addView(btnDel);

                // Divider
                View div = new View(ctx);
                div.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
                div.setBackgroundColor(0xFFe5e7eb);

                listLayout.addView(row);
                listLayout.addView(div);

            } catch (Exception ignored) {}
        }
    }

    private void showAddDialog(AlertDialog parent) {
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 0);

        EditText etEmail = new EditText(ctx);
        etEmail.setHint("email@example.com  or  *  for anyone");
        etEmail.setInputType(InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        layout.addView(etEmail);

        EditText etLabel = new EditText(ctx);
        etLabel.setHint("Label (optional)");
        etLabel.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(etLabel);

        new AlertDialog.Builder(ctx)
            .setTitle("Add allowed sender")
            .setView(layout)
            .setPositiveButton("Add", (d, w) -> {
                String sender = etEmail.getText()
                    .toString().trim().toLowerCase();
                String label  = etLabel.getText()
                    .toString().trim();
                if (sender.isEmpty()) return;
                doAdd(sender, label);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void confirmDelete(int id, String sender) {
        new AlertDialog.Builder(ctx)
            .setTitle("Remove sender?")
            .setMessage(sender.equals("*")
                ? "This will restrict SMS to your whitelist only."
                : "Remove " + sender + " from whitelist?")
            .setPositiveButton("Remove", (d, w) -> doDelete(id))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void doAdd(String sender, String label) {
        exec.execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("action",     "add");
                req.put("android_id", getAndroidId());
                req.put("sender",     sender);
                req.put("label",      label);
                String resp = post(req);
                JSONObject r = new JSONObject(resp);
                handler.post(() -> {
                    if (r.optBoolean("ok", false)) {
                        loadWhitelist();
                    } else {
                        tvStatus.setVisibility(View.VISIBLE);
                        tvStatus.setText(r.optString("error","Failed"));
                        tvStatus.setTextColor(0xFFd63b28);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setText("Error: " + e.getMessage());
                    tvStatus.setTextColor(0xFFd63b28);
                });
            }
        });
    }

    private void doDelete(int id) {
        exec.execute(() -> {
            try {
                JSONObject req = new JSONObject();
                req.put("action",     "delete");
                req.put("android_id", getAndroidId());
                req.put("id",         id);
                String resp = post(req);
                JSONObject r = new JSONObject(resp);
                handler.post(() -> {
                    if (r.optBoolean("ok", false)) {
                        loadWhitelist();
                    } else {
                        tvStatus.setVisibility(View.VISIBLE);
                        tvStatus.setText(r.optString("error", "Failed"));
                        tvStatus.setTextColor(0xFFd63b28);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    tvStatus.setVisibility(View.VISIBLE);
                    tvStatus.setText("Error: " + e.getMessage());
                });
            }
        });
    }

    private String post(JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection)
            new URL(API_URL).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setDoInput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        java.io.InputStream is = conn.getInputStream();
        byte[] buf = new byte[4096];
        int len = is.read(buf);
        conn.disconnect();
        return len > 0
            ? new String(buf, 0, len, StandardCharsets.UTF_8)
            : null;
    }

    private String getAndroidId() {
        return android.provider.Settings.Secure.getString(
            ctx.getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID);
    }
}
