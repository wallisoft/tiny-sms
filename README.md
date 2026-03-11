# Tiny-SMS 📱

**Email → SMS gateway. Zero servers. Zero cost. Works out of the box.**

Part of the [Tiny-Web](https://tiny-web.uk) sustainable services family.

---

## What is it?

Tiny-SMS is a free, open-source Android app that turns any Android phone into a personal SMS gateway. Staff send an email — a text message is delivered. The client replies — the reply lands back in your inbox. No subscription, no API keys, no third-party servers. Just a phone, a Gmail account, and this app.

```
Staff email                 Android phone              Client phone
─────────────────           ──────────────             ────────────
To: gateway@gmail.com  →→→  Tiny-SMS app      →→→     07700 900123
Subject: sms               (your device)
Body:
sms:07700 900123
Hi John, your appointment
is Tuesday at 10am.

                           ←←←  SMS reply      ←←←    "Thanks, see you then"
Reply arrives in
staff inbox ✓
```

---

## Why?

Built specifically for **HMPPS probation services** and similar public sector use cases where:

- Staff need to contact clients who only have basic mobile phones
- IT security rules out third-party SMS platforms with server-side data
- Budget is limited or zero
- No Play Store deployment or MDM is required — just sideload the APK

**Zero data leaves your own devices.** The Gmail account is yours. The phone is yours. Nothing touches our servers.

---

## How to send an SMS

Send an email from **any email client** (Gmail, Hotmail, Outlook — anything):

```
To:      your-gateway-gmail@gmail.com
Subject: sms

sms:07700 900123
Your message text goes here.
It can be multiple lines.
```

That's it. The app picks it up within 5 minutes automatically, or instantly with **Check Mail Now**.

---

## Features

- ✅ Gmail OAuth — no passwords stored, uses Google's secure sign-in
- ✅ Send SMS from any email client to any mobile number
- ✅ Automatic reply routing — SMS replies emailed back to original sender
- ✅ WorkManager background polling every 5 minutes
- ✅ Manual **Check Mail Now** button
- ✅ Scrolling activity log with timestamps
- ✅ Survives device reboot — worker restarts automatically
- ✅ No default SMS app required (uses inbox polling)
- ✅ No Play Store required — sideload the APK
- ✅ 100% free and open source (MIT)

---

## Quick Start

### 1. Download and install

Grab the latest APK from [Releases](../../releases) and sideload it:

```bash
adb install TinySMS-v1.0.apk
# or just copy to phone and open with a file manager
```

Enable **"Install from unknown sources"** in Android settings if prompted.

### 2. Set up Gmail OAuth

You need to register the app with Google Cloud Console once:

👉 See **[SETUP.md](SETUP.md)** for the full step-by-step guide (takes about 10 minutes).

### 3. Run the app

1. Tap **Link Gmail Account** and sign in with your gateway Gmail account
2. Toggle **Auto-check** on
3. Done — send a test email!

---

## Requirements

- Android 8.0 (Oreo) or higher
- A dedicated Gmail account for the gateway (free)
- Google Cloud Console project (free) — see SETUP.md
- The phone needs mobile data or WiFi to check email
- The phone needs a SIM with SMS capability to send texts

---

## Architecture

```
┌─────────────────────────────────────────┐
│           Android Device                 │
│                                         │
│  ┌──────────────┐    ┌───────────────┐  │
│  │ MainActivity │    │MailCheckWorker│  │
│  │   (UI + log) │    │  (5 min poll) │  │
│  └──────┬───────┘    └──────┬────────┘  │
│         │                   │           │
│         └────────┬──────────┘           │
│                  │                      │
│         ┌────────▼────────┐             │
│         │   GmailHelper   │◄──── OAuth  │
│         │  (Gmail API)    │             │
│         └────────┬────────┘             │
│                  │                      │
│         ┌────────▼────────┐             │
│         │   SmsPoller     │             │
│         │ (inbox polling) │             │
│         └────────┬────────┘             │
│                  │                      │
│         ┌────────▼────────┐             │
│         │  ReplyTracker   │             │
│         │ (number→email)  │             │
│         └─────────────────┘             │
└─────────────────────────────────────────┘
```

No broadcast receiver. No default SMS handler. Just READ_SMS + SEND_SMS.

---

## Building from source

```bash
git clone https://github.com/wallisoft/tiny-sms.git
cd tiny-sms
# Open in Android Studio and build
# or:
./gradlew assembleDebug
```

---

## Part of the Tiny-Web family

| Service | URL | Description |
|---------|-----|-------------|
| TinyVPS | [tiny-vps.uk](https://tiny-vps.uk) | Sustainable LXD container hosting from £0.99/mo |
| TinySMS | [tiny-sms.uk](https://tiny-sms.uk) | SMS API for when you need redundancy or to scale beyond one phone |
| TinyRDP | [tiny-rdp.uk](https://tiny-rdp.uk) | Remote desktop |
| TinyDNS | [tiny-dns.uk](https://tiny-dns.uk) | DNS forwarding |
| TinyMail | [tiny-mail.uk](https://tiny-mail.uk) | Webmail |

---

## Licence

MIT — see [LICENSE](LICENSE). Do whatever you want with it. Attribution appreciated but not required.

Built with ♻️ by [Wallisoft / Tiny-Web](https://tiny-web.uk) — Eastbourne, UK.

---

## Licence

**Free for personal and non-commercial use.**

Commercial use — including deployment by any business, government department, public sector body, or organisation — requires a paid licence.

| Tier | Price | Includes |
|------|-------|----------|
| Single deployment | £9.99 p/m  | 1 device, 1 org, setup support |
| Organisation | £500 p/y | Unlimited devices, managed setup, 12 months email support |
| Enterprise / white-label | POA | Contact us |

📧 **hello@tiny-web.uk** · 🌐 **tiny-web.uk**

See [LICENSE](LICENSE) for full terms.

> If you're a small charity or community project and the licence feels unreasonable, get in touch — we're flexible.
