# Aero Voice — Real-time Voice Assistant (APK)

Full voice-to-voice AI assistant for Android using the **Gemini Live API**
(bidirectional WebSocket streaming of audio — no waiting for full replies).

## What it does
- Open the app → it asks for your **Google AI Studio API key**.
- Paste the key, pick a voice, hit **Save & Test**.
- Tap the mic → talk → the assistant replies out loud, in real time.
- You can interrupt it mid-speech; the conversation stays live until you tap stop.

## Getting your API key (free)
1. Go to **https://aistudio.google.com** (sign in with any Google account).
2. Click **Get API key** (top-right) → **Create API key**.
3. Copy the key (starts with `AIza…`). No project/folder selection is required.
4. Paste it into the app's Settings page.

> The key is stored only on your phone (app-private storage). It is never uploaded anywhere.

## Model
The app uses **`gemini-3.8-live`** by default — editable in Settings if you ever need to change it.
If you see an error like *model not found*, just open Settings and update the Model ID.

## Live internet access
The assistant can search the web in real time (free, key-less):

- When you ask about news, weather, prices, sports or anything current, the model
  calls the built-in `web_search` tool.
- The app fans the query out to **10+ free sources in parallel** — DuckDuckGo,
  Wikipedia, Google News, Bing News, Hacker News, Reddit, Stack Overflow, GitHub,
  Open-Meteo (weather) and CoinGecko (crypto).
- Results are returned to the model as JSON; it synthesizes a spoken answer.
- Multiple sources are rate-limited (per-source interval + exponential cooldown),
  so you never hit a "too many requests" wall. Results are cached for 5 minutes.
- If several results arrive while the assistant is already speaking, they are queued
  and announced one by one, 3 seconds after it finishes each part.

## Build output
- Signed release APK: `app/build/outputs/apk/release/app-release.apk`
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Installing on your phone
**Option A — USB (easiest):**
1. On your phone: Settings → About → tap Build number 7× to enable Developer options.
2. Developer options → enable **USB debugging**.
3. Connect the phone via USB and run:
   ```
   C:\Android\Sdk\platform-tools\adb.exe install -r app-release.apk
   ```

**Option B — send the file:** transfer `app-release.apk` to the phone (WhatsApp/Drive/email),
tap it, and allow install from that source when prompted.

After installing, the app will ask for microphone permission the first time you hit the mic.

## Rebuilding from source
Requires JDK 17 + Android SDK (platform 35, build-tools 35). Then:

```
./gradlew assembleRelease
```

Release signing is configured through `local.properties` (git-ignored), so
credentials never live in the repository:

```
AERO_STORE_FILE=release.jks
AERO_STORE_PASSWORD=…
AERO_KEY_ALIAS=…
AERO_KEY_PASSWORD=…
```

Without those entries the release build is produced unsigned.

## License

[MIT](LICENSE) © 2026 Kartik Sharma
