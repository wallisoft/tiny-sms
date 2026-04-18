package com.wallisoft.tinysms;

import android.content.Context;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import java.util.List;

/**
 * SimValidator
 * Sends validation SMS from each SIM to server-supplied number
 * Server receives, records the actual SIM phone numbers
 * Validates SMS capability at the same time
 */
public class SimValidator {

    private static final String TAG = "SimValidator";

    private final Context context;

    public SimValidator(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Validate all SIMs ─────────────────────────────────
    public void validateAllSims(String androidId,
                                 String validateNumber) {
        if (validateNumber == null || validateNumber.isEmpty()) {
            Log.w(TAG, "No validation number supplied");
            return;
        }

        if (Build.VERSION.SDK_INT < 22) {
            sendValidationSms(androidId, 1, -1,
                    "", validateNumber);
            return;
        }

        try {
            SubscriptionManager sm = (SubscriptionManager)
                    context.getSystemService(
                            Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> subs =
                    sm.getActiveSubscriptionInfoList();

            if (subs == null || subs.isEmpty()) {
                sendValidationSms(androidId, 1, -1,
                        "", validateNumber);
                return;
            }

            int slot = 1;
            for (SubscriptionInfo sub : subs) {
                String carrier = sub.getCarrierName() != null
                        ? sub.getCarrierName().toString() : "";
                sendValidationSms(androidId, slot,
                        sub.getSubscriptionId(),
                        carrier, validateNumber);
                slot++;
                if (slot > 2) break;
            }

        } catch (Exception e) {
            Log.w(TAG, "validateAllSims: " + e.getMessage());
            // Fallback to default SIM
            sendValidationSms(androidId, 1, -1,
                    "", validateNumber);
        }
    }

    // ── Send validation SMS from specific SIM ─────────────
    private void sendValidationSms(String androidId, int simSlot,
                                    int subscriptionId,
                                    String carrier,
                                    String validateNumber) {
        try {
            // Body format: sms:VALIDATE-{slot}-{android_id}
            String body = "sms:VALIDATE-" + simSlot
                    + "-" + androidId
                    + "\nSIM" + simSlot + " validation"
                    + (carrier.isEmpty() ? ""
                       : " (" + carrier + ")");

            SmsManager sms;
            if (Build.VERSION.SDK_INT >= 22
                    && subscriptionId >= 0) {
                sms = SmsManager.getSmsManagerForSubscriptionId(
                        subscriptionId);
            } else {
                sms = SmsManager.getDefault();
            }

            sms.sendTextMessage(
                    validateNumber, null, body, null, null);

            LogStore.get(context).append(
                    "SIM" + simSlot + " validation sent"
                    + (carrier.isEmpty() ? ""
                       : " (" + carrier + ")"));

        } catch (Exception e) {
            Log.w(TAG, "SIM" + simSlot
                    + " validation failed: " + e.getMessage());
            LogStore.get(context).append(
                    "SIM" + simSlot + " validation failed: "
                    + e.getMessage());
        }
    }
}
