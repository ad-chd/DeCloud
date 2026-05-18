# DeCloud — Privacy Policy

**Last updated: April 23, 2026**

---

## A note from the developer

Hi. I'm the person who made DeCloud.

I built this app for one simple reason: people shouldn't have to upload their personal photos, videos, contacts, or files to some company's cloud just to move them between their phone and their PC. The whole point of DeCloud is that **your data stays yours, on your devices, and never travels through anyone else's servers — including mine.**

I don't run any servers. I don't have analytics. I don't make money from your data. I don't even *want* your data — I genuinely have no way to see it. The app doesn't need an internet connection at all. It only uses your local WiFi or a USB cable to talk between your phone and your PC.

If you're wondering "what's the catch?" — there isn't one. This is a tool I made because I thought it should exist. That's the entire story.

---

## What is DeCloud?

DeCloud is a free Android app that helps you transfer files, photos, videos, and contacts from your phone to your Windows PC. It works over your home WiFi (or a phone hotspot) or a USB cable. **It never sends data to the internet.**

---

## What information does DeCloud access on your device?

To do its job — moving files from your phone to your PC — DeCloud needs to read certain things on your device when *you* tell it to:

- **Files, photos, videos, and audio** — so you can pick what to send.
- **Contacts** *(only if you tap the "Contacts" button)* — to export your address book as a VCF file that gets sent to your PC.
- **Location permission** *(only if you use the WiFi-hotspot mode)* — Android requires location permission to start a local-only WiFi hotspot. The app does **not** read your location. The system just gates the hotspot API behind this permission.
- **WiFi and network state** — to detect whether your PC is connected and to send files over the local network.
- **Bluetooth** *(optional)* — used only as a fallback method to find your PC on the network. The app does not pair with or read data from any Bluetooth device.
- **Notifications** — so the transfer status can show in the notification shade while it's running.

That's the entire list. DeCloud has no other access to your device.

---

## What does DeCloud do with that information?

Nothing leaves your local network. Specifically:

- Files you select are streamed directly from your phone to your PC over your local WiFi or USB cable.
- The contacts file (VCF) is generated on your phone, sent to your PC, and that's it.
- **No data is ever uploaded to a server.** There is no server.
- **No data is shared with any third party.** There are no third parties.
- **No data is stored by me.** I have no database, no logs, no telemetry, no anything.

When the transfer ends, the data is on your PC where you wanted it. The app's job is done.

---

## What DeCloud will never do

- ❌ Upload your data to any server (mine or anyone else's)
- ❌ Send your data to any third party
- ❌ Track you, profile you, or run analytics on your behavior
- ❌ Show ads
- ❌ Sell or share your information
- ❌ Require an account, sign-in, or email
- ❌ Need an internet connection to function

---

## A note about local network transfers

Because DeCloud transfers data over your **local** network, anyone connected to the *same* network could in theory see network traffic between your phone and PC. For this reason, **only use DeCloud on networks you trust** — your home WiFi, your phone's hotspot, or a direct USB cable. Don't use it on public WiFi (cafés, airports, etc.).

This is the same recommendation that applies to any local-network tool. The transfer itself does not encrypt the data because no internet is involved — your data goes directly from your device to the PC sitting next to you.

---

## Permissions, explained in plain English

| Permission | Why DeCloud needs it |
|---|---|
| Files, Photos, Videos, Audio | So you can pick what to send to your PC. |
| Contacts (optional) | So you can export your contacts as a VCF file. |
| All Files Access | So you can browse and select files from any folder, like a file manager. |
| Location | Required by Android to start a local-only WiFi hotspot. The app never reads your location. |
| WiFi / Network state | To detect your PC and send files over WiFi. |
| Bluetooth (optional) | Fallback method for finding your PC on the network. |
| Notifications | To show transfer progress in the notification shade. |
| Foreground service | To keep the transfer running while the screen is off. |

You can revoke any of these at any time from your phone's **Settings → Apps → DeCloud → Permissions**. The app will continue to work for any feature that doesn't depend on the revoked permission.

---

## Children's privacy

DeCloud does not collect any data from anyone, including children. It is safe to use at any age. There are no ads, no in-app purchases, no accounts, and no internet activity.

---

## Changes to this policy

This policy may be updated over time as the app grows — for example, if I add a paid version, in-app purchases, optional features, or new ways to transfer files. When that happens, I'll change the "Last updated" date at the top of this page and (where it materially affects you) describe what's new.

**However, the core promise of DeCloud will never change:**

- Your data stays on your own devices and your own local network.
- Your data is never uploaded to my servers (because I don't have any) or to any third party.
- The app does not track you, profile you, or run analytics on your behavior.
- The app exists to help you move your files — that's it. It will never become a vehicle for collecting or selling your data.

If a future change to the app would ever affect that core — for example, a cloud sync feature (which I have no plans for) — I would update this policy *before* releasing the feature, and you'd be free to not update the app.

---

## Contact

If you have any questions about this policy, the app, or anything else, please reach out:

**Developer:** [Your Name]
**Email:** [your-email@example.com]

I read every email. If something seems off, please tell me.

---

*Thanks for using DeCloud.*
