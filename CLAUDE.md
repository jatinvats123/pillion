# Pillion — Voice Co-Pilot for Gig Riders

"The AI that rides with you." Built by Jatin (solo) for the **Agora Voice AI Hackathon** by AI Mobile Coders (online, India). Submission deadline: **29 Sep 2026**. Hackathon rule: Agora Conversational AI must be a core component.

## Project summary

A native Android app for India's delivery, bike-taxi and rental riders who can't safely touch their phone while riding. The rider talks to Pillion through earphones in Hindi, English or Hinglish (more languages later); the agent answers in real time and takes actions.

## Tech stack

- **Android** (`/android`): Kotlin, Jetpack Compose, Material 3, minSdk 26, MVVM (ViewModel + repository), coroutines/Flow. Minimal dependencies.
- **Backend** (`/backend`): Node.js + Express. Holds all secrets, issues Agora tokens, starts/stops the agent. Uses the official `agora-agents` Node SDK.
- **Voice**: Agora Conversational AI Engine + Agora RTC (audio) + Agora RTM (transcripts and agent state).
- **LLM**: Google Gemini (native `gemini` style in ConvoAI), default `gemini-3.5-flash-lite`.
- **STT/TTS**: Sarvam (Indian languages, code-mixed Hinglish). Fallback via `VOICE_STACK=managed`: Agora-managed Deepgram nova-3 + MiniMax.
- **Dev machine**: Windows 11 (PowerShell). Test devices: Realme 5 Pro (Android 11) and the `Pixel_8d` emulator (API 36).

## How it fits together

1. App → `POST /agent/start` → backend makes a channel, a combined RTC+RTM token for the rider, and starts the agent (Sarvam STT → Gemini → Sarvam TTS) scoped to the rider's uid.
2. App joins the RTC channel (mic up, agent audio down) and logs into RTM, subscribing to the channel.
3. Agent publishes `user.transcription`, `assistant.transcription`, `message.state`, `message.interrupt`, `message.error` over RTM.
4. App → `POST /agent/stop` on End Ride. Safety net: agent idle-timeout 30 s after the rider leaves; backend stops its agents on Ctrl+C.

## Feature roadmap

- Voice actions: next-drop ETA, message/call customer, today's earnings, nearby places, traffic
- Customer number captured by scanning the delivery-app order screen with the camera (ML Kit OCR)
- Crash detection (accelerometer + gyroscope) → "Aap theek ho?" → auto SMS SOS with live location
- Fatigue reminder after long continuous riding
- Jev (TypeSafe AI) as a fast intent router in front of the main LLM
- Live transcript with English subtitles, trip summary, earnings charts, polished custom UI

## Judges and what they care about

- **Akshay Nandwana** — Agora Developer Evangelist, Google Developer Expert (Android).
- **Selen Demir** — Android engineer, founder, focus on accessibility.

They care about: deep Agora usage (not a re-skinned sample), production-quality Android, accessibility and multilingual support, and working in low connectivity.

## Rules

- Keep code minimal and readable; no unrequested abstractions or libraries.
- Never commit secrets. All keys live in `backend/.env` (gitignored). Nothing secret in the Android app.
- Android must feel production-quality: proper permissions, no crashes, smooth UI.
- UI direction (to be finalised later): dark, bold, high-contrast, glanceable while riding; must NOT look like a generic AI-generated template.
- Follow the Agora docs / SDK over assumptions; note any divergence.

## Running locally

- Backend: `cd backend; npm install; npm start` (needs `backend/.env`, see `.env.example`).
- Android: open `/android` in Android Studio. Backend URL comes from `PILLION_BACKEND_URL` in `android/local.properties` (default `http://10.0.2.2:3000` for the emulator).
- Real phone on USB: `adb reverse tcp:3000 tcp:3000` and use `http://localhost:3000`.
- Real phone on mobile data: `cloudflared tunnel --url http://localhost:3000` and use the printed https URL.

## Phase log

