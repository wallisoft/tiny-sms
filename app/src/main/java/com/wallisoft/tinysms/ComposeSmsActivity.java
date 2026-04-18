package com.wallisoft.tinysms;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Stub compose activity - required to be default SMS app
 * Redirects to Samsung Messages for actual composition
 */
public class ComposeSmsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Pass through to Samsung Messages
        Intent intent = getIntent();
        intent.setPackage(null);
        intent.setComponent(null);
        try {
            startActivity(intent);
        } catch (Exception e) {
            // Fallback - open main activity
            startActivity(new Intent(this, MainActivity.class));
        }
        finish();
    }
}
