package com.wallisoft.tinysms;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Stub service - required to be default SMS app */
public class RespondViaMessageService extends Service {
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
