# Pillion — Voice Co-Pilot for Gig Riders

> The AI that rides with you.

Pillion is a hands-free voice co-pilot for India's delivery, bike-taxi and rental riders. The rider talks through earphones in Hindi, English or Hinglish, and Pillion answers in real time — no touching the phone while riding.

Built for the Agora Voice AI Hackathon on **Agora Conversational AI**.

## Status

Phase 1 — foundation and live voice loop (in progress).

## Architecture

```
Android app ──POST /agent/start──▶ Node backend ──▶ Agora ConvoAI REST (start agent)
     │                                                     │
     ├── Agora RTC: rider mic ⇄ agent voice ◀──────────────┤  Sarvam STT → Gemini → Sarvam TTS
     └── Agora RTM: transcripts + agent state ◀────────────┘
```

- `android/` — Kotlin + Jetpack Compose app
- `backend/` — Node.js + Express; holds secrets, issues tokens, starts/stops the agent

## Setup

### Backend

```powershell
cd backend
Copy-Item .env.example .env   # then fill in your keys
npm install
npm start
```

Requires an Agora project with App Certificate, Conversational AI and Signaling (RTM) enabled, a Gemini API key and a Sarvam API key.

### Android

Open `android/` in Android Studio and run on the emulator. To point the app at a different backend, set `PILLION_BACKEND_URL` in `android/local.properties`.

## License

TBD
