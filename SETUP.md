# Tiny-SMS Setup Guide

Everything you need to get from zero to sending SMS via email in about 10 minutes.

---

## Step 1 — Create a dedicated Gmail account

Create a new Gmail account specifically for the gateway, e.g. `probation.sms.gateway@gmail.com`

> **Tip for HMPPS / public sector:** Use a role account rather than a personal one. This way if staff change, the gateway keeps working.

---

## Step 2 — Google Cloud Console (one-time setup)

This is the only fiddly bit. You only do it once per deployment.

### 2a. Create a project

1. Go to [console.cloud.google.com](https://console.cloud.google.com)
2. Click the project dropdown (top left) → **New Project**
3. Name it `TinySMS` → **Create**

### 2b. Enable the Gmail API

1. In your new project, go to **APIs & Services → Library**
2. Search for **Gmail API**
3. Click it → **Enable**

### 2c. Configure the OAuth consent screen

1. Go to **APIs & Services → OAuth consent screen**
2. Select **External** → **Create**
3. Fill in:
   - App name: `Tiny-SMS`
   - User support email: your email
   - Developer contact: your email
4. Click **Save and Continue** through Scopes and Test Users screens
5. Back on the consent screen, scroll to **Test users** → **+ Add Users**
6. Add the Gmail address you created in Step 1
7. Save

### 2d. Create an OAuth credential

1. Go to **APIs & Services → Credentials**
2. Click **+ Create Credentials → OAuth client ID**
3. Application type: **Android**
4. Package name: `com.wallisoft.tinysms`
5. SHA-1 certificate fingerprint — get this by running on your PC:

```bash
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Copy the **SHA1** line and paste it in.

6. Click **Create**

> For a production/release build, repeat with your release keystore SHA-1.

---

## Step 3 — Install the APK

### Option A: Direct install
1. Download `TinySMS-v1.0.apk` from [Releases](../../releases)
2. Copy to the Android device
3. Open a file manager on the device, tap the APK
4. If prompted, enable **"Install from unknown sources"** in Settings

### Option B: ADB install
```bash
adb install TinySMS-v1.0.apk
```

---

## Step 4 — First run

1. Open **Tiny-SMS**
2. Grant the permissions requested (SMS read/send)
3. Tap **Link Gmail Account**
4. Sign in with the gateway Gmail account from Step 1
5. Toggle **Auto-check** on

---

## Step 5 — Send a test message

From **any** email client, send:

```
To:      your-gateway-gmail@gmail.com
Subject: sms

sms:07700 900123
Hello! This is a test from Tiny-SMS.
```

Within 5 minutes (or tap **Check Mail Now** immediately), the SMS will be delivered.

If the recipient replies, the reply will arrive in the original sender's inbox.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| "Not completed verification" on sign-in | Add your Gmail to Test Users (Step 2c) |
| SMS not sending | Check the phone has a SIM and mobile signal |
| No emails being picked up | Check subject is exactly `sms` (case insensitive) and first line is `sms:NUMBER` |
| Worker not running | Toggle Auto-check off and on again |
| Build fails with META-INF error | Run `rm -rf ~/.gradle/caches/` then rebuild |

---

## For IT departments

### What permissions does the app require?
- `SEND_SMS` — to send text messages
- `READ_SMS` — to poll the inbox for replies (no default SMS app needed)
- `INTERNET` — to connect to Gmail API
- `RECEIVE_BOOT_COMPLETED` — to restart the background worker after reboot

### What data is stored?
- The Gmail account name is stored in Android SharedPreferences (local to the device)
- A rolling 8KB activity log is stored in SharedPreferences
- Phone→email mappings are stored for 7 days to route replies (local, SharedPreferences)
- Nothing is sent to any Tiny-Web or Wallisoft servers

### Network traffic
All network traffic is to `gmail.googleapis.com` only (Google's Gmail API). No third-party servers involved.

### MDM / enterprise deployment
The APK can be distributed via any MDM that supports sideloading. The OAuth credential will need to be set up with the release keystore SHA-1 (Step 2d).
