# Pillion — Voice Co-Pilot for Gig Riders

"The AI that rides with you." Built by Jatin (solo) for the **Agora Voice AI Hackathon** by AI Mobile Coders (online, India). Submission deadline: **29 Sep 2026**. Hackathon rule: Agora Conversational AI must be a core component.

## Project summary

A native Android app for India's delivery, bike-taxi and rental riders who can't safely touch their phone while riding. The rider talks to Pillion through earphones in Hindi, English or Hinglish (more languages later); the agent answers in real time and takes actions.

## Tech stack

- **Android** (`/android`): Kotlin, Jetpack Compose, Material 3, minSdk 26, MVVM (ViewModel + repository), coroutines/Flow. Minimal dependencies.
- **Backend** (`/backend`): Node.js + Express. Holds all secrets, issues Agora tokens, starts/stops the agent. Uses the official `agora-agents` Node SDK.
- **Voice**: Agora Conversational AI Engine + Agora RTC (audio) + Agora RTM (transcripts and agent state).
- **LLM**: Agora-managed OpenAI `gpt-4o-mini` (default, no key). Google Gemini `gemini-3.5-flash-lite` (native `gemini` style) via `LLM_PROVIDER=gemini`. See "Decisions".
- **STT/TTS**: Sarvam (Indian languages, code-mixed Hinglish): STT auto-detect, TTS `bulbul:v3` voice `priya`, pace 1.08. Fallback via `VOICE_STACK=managed`: Agora-managed Deepgram nova-3 + MiniMax.
- **Dev machine**: Windows 11 (PowerShell). Test devices: Realme 5 Pro (Android 11) and the `Pixel_8d` emulator (API 36).

## How it fits together

1. App → `POST /agent/start` → backend makes a channel, a combined RTC+RTM token for the rider, and starts the agent (Sarvam STT → gpt-4o-mini → Sarvam TTS) scoped to the rider's uid.
2. App joins the RTC channel (mic up, agent audio down) and logs into RTM, subscribing to the channel.
3. Agent publishes `user.transcription`, `assistant.transcription`, `message.state`, `message.interrupt`, `message.error` and `message.metrics` (per-turn ASR/LLM/TTS latency) over RTM. Debug builds log a per-turn `Latency` line (logcat tag `VoiceSession`) and mirror it to the backend log via `POST /debug/latency`.
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

- Backend: `cd backend; npm install; npm run dev` (auto-reloads on code changes; `.env` changes still need a restart). Needs `backend/.env`, see `.env.example`.
- Android: open `/android` in Android Studio. Backend URL: `-PPILLION_BACKEND_URL=...` Gradle property, else `PILLION_BACKEND_URL` in `android/local.properties`, else `http://10.0.2.2:3000` (emulator).
- Real phone (USB or wireless debugging): `.\scripts\phone.ps1` — adb reverse, build with `http://localhost:3000`, install, follow logs. Wireless adb drops on sleep/Wi-Fi change → reconnect and rerun.
- Real phone on mobile data: `cloudflared tunnel --url http://localhost:3000` and use the printed https URL.
- The emulator (`Pixel_8d`) is not usable for voice on this machine: its host mic delivers silence and audio output is broken system-wide. Test voice on the Realme.

## Decisions

- **Default LLM is Agora-managed `gpt-4o-mini`, not Gemini (Phase 1).** Measured on the Realme with Agora `message.metrics` (23 Sep 2026): Gemini 3.5 Flash-Lite took 24–37 s to first token, gpt-4o-mini 0.5–1.5 s, with equal answer quality and language matching. Direct Gemini API calls were equally slow at the time (10–76 s), so it was Google-side latency, not Agora. Gemini's thinking is already at its minimum (`minimal`) on this model. Gemini stays available via `LLM_PROVIDER=gemini`.
- Sarvam STT/TTS for Hindi, English and Hinglish; Agora-managed Deepgram + MiniMax as the fallback (`VOICE_STACK=managed`).
- Turn detection: end-of-speech silence 400 ms, prefix padding 240 ms, barge-in after 160 ms of speech (kept above EasyEV's 120 ms so road noise doesn't interrupt Pillion).
- Agent `audio_scenario: aiserver` (network resilience); app uses `AUDIO_SCENARIO_AI_CLIENT` + AI echo cancellation + AI noise suppression per Agora's audio best-practice doc.

## Later phases (agreed)

- **Phase 2:** filler words (Agora `fillerWords`, ~700 ms, short Hindi/Hinglish/English phrases) together with the first tool calls.
- **Final phase:** README, including the "Agora SDK feedback" section built from the notes below.

## Agora SDK feedback — notes for the README

**Latency (Realme 5 Pro, Android 11, Wi-Fi; Agora `message.metrics` + client-side timing).** e2e = rider stops talking (local VAD, ±200 ms) → agent starts speaking.

| Setup | e2e | LLM first token | TTS first audio | ASR |
|---|---|---|---|---|
| Gemini 3.5 Flash-Lite, turn detection 640/800 ms | 26.5–41.6 s | 24.0–37.3 s | 1.0–2.8 s | 0–0.2 s |
| gpt-4o-mini (managed), 640/800 ms | 2.3–4.6 s | 0.5–1.5 s | 0.4–1.2 s | 0.06–0.48 s |
| gpt-4o-mini (managed), 400/240 ms | 4.0–4.2 s (2 turns) | 0.9–1.3 s | 1.3–1.5 s | 0.17–0.18 s |

Turn detection 640→400 ms: the part before the final transcript (end-of-speech wait + ASR) was 0.85–1.50 s before and 0.96–1.34 s after. With only 2 turns afterwards the difference isn't measurable yet. Remaining cost is mostly after the transcript: LLM ~1 s + Sarvam TTS ~1–1.5 s. Target (<2 s e2e) not yet met.

**Issues hit with the SDK / docs:**
1. `agora-agents` `Gemini` class: the `temperature` option is serialised into `llm.params`, which ConvoAI forwards verbatim into Gemini's request body → top-level `temperature` → Gemini HTTP 400 → the agent silently speaks `failure_message`. Workaround: `params: { generationConfig: { temperature } }`.
2. Sarvam TTS: Agora's docs list bulbul v2 speakers (`anushka`, `abhilash`, …) but ConvoAI calls `bulbul:v3`, which rejects them (HTTP 400 surfaced only as an RTM `message.error`). v3 speakers (`priya`, `neha`, `rahul`, …) work.
3. The Android quickstart uses `AUDIO_SCENARIO_CHORUS`; Agora's own audio best-practice doc recommends `AUDIO_SCENARIO_AI_CLIENT` plus the AI AEC/NS extensions and a set of `che.audio.*` parameters re-applied on route change. Easy to miss.
4. Agent history (`GET …/agents/{id}/history`) returns 404 as soon as the agent stops, so a ride can't be analysed after it ends; per-turn metrics only reach the client over RTM. We had to build client-side latency logging.
5. Agora-managed Deepgram and MiniMax docs don't state Hindi support, which pushed us to Sarvam (BYOK) for Hindi.
6. Agora SDK log files on device are encrypted, so mic capture problems had to be diagnosed with `enableAudioVolumeIndication` instead.
7. Naming: `AgentSession.think()` vs low-level `agentManagement.agentThink()`.

## Phase log

- Phase 1 done: foundation + live voice loop — Start/End Ride, Hindi/English/Hinglish voice with live transcript, barge-in, mic permission flow, locked-screen foreground service, per-turn latency metrics; default LLM switched to Agora-managed gpt-4o-mini (Gemini 24–37 s → gpt-4o-mini ~1 s to first token).
