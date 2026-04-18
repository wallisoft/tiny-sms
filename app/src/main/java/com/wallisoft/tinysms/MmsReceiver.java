package com.wallisoft.tinysms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Stub MMS receiver - required to be default SMS app */
public class MmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // MMS not supported - stub required by Android
    }
}
