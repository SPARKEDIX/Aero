<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Aero" width="128" height="128">
</p>

<h1 align="center">Aero</h1>

<p align="center">
  <b>Your real-time, voice-to-voice AI assistant for Android.</b><br>
  Bol — aur Aero karega. Kaan par baat, screen pe control, aur yaad bhi rakhega.
</p>

---

## ✨ Introduction

**Aero** is a native Android voice assistant that talks back — not just texts.
It uses the **Gemini Live API** (bidirectional WebSocket audio streaming), so the
conversation is a real-time, natural voice call. You can speak, interrupt, ask
follow-ups, and get spoken answers in **Hindi / Hinglish / English** — in your own voice tone.

All core intelligence runs on-device + in the live model session; the app needs
**no server** of its own for day-to-day use.

**Quick start**
1. Get a free API key from [Google AI Studio](https://aistudio.google.com) (starts with `AIza…`).
2. Open Aero → Settings → paste the key → pick a voice → **Save & Test**.
3. Tap the mic and start talking — or set Aero as your default assistant and
   use the side button / floating orb.

---

## ⚡ Features

### 🗣️ Conversation
- Voice-to-voice, real-time replies (no waiting for full text).
- Interrupt anytime — just start speaking.
- Replies in Hindi / Hinglish / English (follows your language).
- Live internet search via the built-in `web_search` tool (news, weather, prices, sports…).

### 🧠 Memory (on-device, no server)
- `"Yaad rakho mera naam Kartik hai"` → saved forever, injected into every future session.
- `"Tujhe kya yaad hai"` → Aero tells you what it remembers.
- `"Bhool jao …"` → removes memories.
- All facts are stored **only on your phone**.

### ⏰ Reminders & Calls (call-style UI)
- `"Kal shaam 5 baje yaad karao"` → at that time the phone **rings like a real call**.
- Full-screen incoming-call UI → Answer / Decline.
- In-call screen: **earpiece ↔ speaker toggle**, mute, timer, end call, and
  voice — set another reminder right inside the call.
- Auto-retry: if not answered in 60s, Aero calls again (up to 3×).
- Works after reboot; missed reminders are caught when the phone turns on.

### 📱 Phone & App Control
- Open / close apps, clear recents, go home, back, scroll, click, type.
- Read & clear notifications.
- Real SIM calls (`"Call Riya"`), WhatsApp messages / voice / video calls.
- Torch, Bluetooth, brightness, volume, screenshot.
- Front/back camera live view — Aero can see through it.
- Screen share (lightweight frames) for "screen par kya hai?" questions.

### 🎨 Experience
- Floating orb overlay — available on any screen, never in your way.
- Mute & screen-share controls with clear red states.
- Adjustable moods/voices, top-left menu / top-right sliders.
- No text in the main UI — pure voice + orb.

---

## 🔒 Security & Privacy

- **API key** is stored only in app-private storage — never uploaded anywhere.
- **Memory & reminders** live in app-local storage — no server, no cloud.
- **Microphone** is used only while a session is active — no background hotword
  / waking-word listening.
- **Screen share** captures only lightweight frames while sharing is ON and is
  never saved to storage.
- **Permissions** are requested only when a feature needs them (mic, camera,
  accessibility, overlay, notifications). You control all of them.
- I Aero-specific dismiss intents close Aero — plain "band kar" never kills it.

---

## 🤝 Support

Have a problem, a bug, or an idea? We'd love to hear from you.

- **GitHub Issues:** [SPARKEDIX/Aero/issues](https://github.com/SPARKEDIX/Aero/issues)
- **Creator:** Kartik Sharma — AI engineer & full-stack developer
- **YouTube:** [SparkedIX](https://www.youtube.com/@SparkedIX)
- **Instagram:** [@iamsparkedix](https://instagram.com/iamsparkedix)

> Please include your Android version, device model, and what you said/were when
> the issue happened — it helps us fix it much faster.

---

## 🛠️ Build

```
./gradlew assembleRelease
```

Signed release APK: `app/build/outputs/apk/release/app-release.apk`
(debug: `app/build/outputs/apk/debug/app-debug.apk`)

---

<p align="center">
  Made with ❤️ by <b>Kartik Sharma</b> (SparkedIX)
</p>
