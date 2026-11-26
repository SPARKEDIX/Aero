<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Aero" width="128" height="128">
</p>

<h1 align="center">Aero</h1>

<p align="center">
  <b>Your real-time, voice-to-voice AI assistant for Android.</b><br>
  Bol — aur Aero karega. Kaan par baat, screen pe control, aur yaad bhi rakhega.
</p>

---

## 📥 Download

This repository is **private**. The signed release APK is available from the
[Releases](https://github.com/SPARKEDIX/Aero/releases) page of this repo
(you need access to this repo).

- APK: `Aero v1.1.0` — signed release build (arm64)
- Install: download → tap → allow unknown sources → open
- First run: onboarding asks for a **free Google AI Studio API key**
  (https://aistudio.google.com/app/apikey), tests it, then asks your name/hobbies.

---

## ✨ Introduction

**Aero** is a native Android voice assistant that talks back — not just texts.
It uses the **Gemini Live API** (bidirectional WebSocket audio streaming), so the
conversation is a real-time, natural voice call. You can speak, interrupt, ask
follow-ups, and get spoken answers in **Hindi / Hinglish / English**.

All core intelligence runs on-device + in the live model session; the app needs
**no server** of its own for day-to-day use.

---

## ⚡ Features

### 🗣️ Conversation
- Voice-to-voice, real-time replies (no waiting for full text).
- Interrupt anytime — just start speaking.
- Replies in Hindi / Hinglish / English.
- Background internet searches (non-blocking): the app searches in the
  background and Aero announces results naturally mid-conversation.

### 🧠 Memory (on-device, no server)
- `"Yaad rakho mera naam Kartik hai"` → saved forever, injected into every future session.
- `"Tujhe kya yaad hai"` → Aero tells you what it remembers.
- `"Bhool jao …"` → removes memories.

### ⏰ Reminders & Calls (call-style UI)
- `"Kal shaam 5 baje yaad karao"` → at that time the phone **rings like a real call**.
- Full-screen incoming-call UI → Answer / Decline; in-call screen with
  **speaker/earpiece toggle**, mute, timer, end call, and voice-set reminders.
- Manage existing reminders: list / cancel / delay / earlier / fire now / clear all.

### 📱 Phone & App Control
- Open / close apps, clear recents, go home, back, scroll, click, type.
- Real SIM calls, WhatsApp messages / voice / video calls.
- Torch, Bluetooth, brightness, volume, screenshot, WiFi/data settings.
- Front/back camera live view; screen share for "screen par kya hai?" questions.

### 🤖 Autonomous Screen Agent
- `"Agent chalao — Instagram kholo aur search karo"` → Aero sees the screen
  (2-second in-memory feed — **nothing is saved to storage**), plans steps with
  the model, and taps/types/navigates by itself until the goal is done.

### 📩 WhatsApp Auto-Reply
- `"Auto-reply on"` → when you're busy, Aero answers WhatsApp messages itself
  (AI-generated replies) using the notification quick-reply path — the full
  WhatsApp app doesn't open.
- Limit to specific contacts: `"Auto-reply sirf Riya ke liye"`.

### 🩺 Device Health
- `"Battery batao"` → level, charging state, temperature.
- `"Device status"` → battery + storage free + RAM free + uptime + model.

### 🎨 Experience
- Floating orb overlay — available on any screen.
- Onboarding: typing welcome → API key test → name/hobbies.
- Mute & screen-share controls with clear red states; adjustable voices/moods.

---

## 🔒 Security & Privacy

- **API key** stored in app-private storage — never uploaded anywhere.
- **Memory & reminders** in app-local storage — no server, no cloud.
- **Microphone** used only while a session is active — no background hotword.
- **Screen feed** lives only in memory (latest frame), never written to disk,
  and is active only while the agent/screen-share needs it.
- **Permissions** are requested when a feature needs them; all are user-controlled.

---

## 🤝 Support

- **GitHub Issues:** SPARKEDIX/Aero/issues
- **Creator:** Kartik Sharma — AI engineer & full-stack developer
- **YouTube:** [SparkedIX](https://www.youtube.com/@SparkedIX)
- **Instagram:** [@iamsparkedix](https://instagram.com/iamsparkedix)

---

## 🛠️ Build

```
./gradlew assembleRelease
```

Signed release APK: `app/build/outputs/apk/release/app-release.apk`

---

<p align="center">
  Made with ❤️ by <b>Kartik Sharma</b> (SparkedIX)
</p>
