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
- **Actions**: Agora ConvoAI custom tools (`llm.tools`, v2.12) → backend `/tools/*` over cloudflared → phone via server-sent RTM messages. Jev (TypeSafe AI, via jevai.org) reads each rider turn alongside the LLM.
- **Maps**: `MAPS_PROVIDER=geoapify` (default; free, OpenStreetMap data, motorcycle mode) or `google` (Routes + Places (New), TWO_WHEELER, live traffic; blocked on Google Cloud billing verification for now). Same interface in `backend/src/maps/`.
- **Dev machine**: Windows 11 (PowerShell). Test devices: Realme 5 Pro (Android 11) and the `Pixel_8d` emulator (API 36).

## How it fits together

1. App → `POST /agent/start` → backend makes a channel, a combined RTC+RTM token for the rider, and starts the agent (Sarvam STT → gpt-4o-mini → Sarvam TTS) scoped to the rider's uid.
2. App joins the RTC channel (mic up, agent audio down) and logs into RTM, subscribing to the channel.
3. Agent publishes `user.transcription`, `assistant.transcription`, `message.state`, `message.interrupt`, `message.error` and `message.metrics` (per-turn ASR/LLM/TTS latency) over RTM. Debug builds log a per-turn `Latency` line (logcat tag `VoiceSession`) and mirror it to the backend log via `POST /debug/latency`.
4. App → `POST /agent/stop` on End Ride. Safety net: agent idle-timeout 30 s after the rider leaves; backend stops its agents on Ctrl+C.
5. Tools: the LLM calls a custom tool → Agora POSTs `PUBLIC_BASE_URL/tools/<name>` with the ride's secret (template variable) → backend asks the phone over RTM (`pillion.request` from RTM user `pillion-server`) → app answers `POST /ride/device-result` → backend calls Google if needed → JSON back to the LLM. Backend also sends `pillion.action` lines ("✓ SMS sent to Rahul") for the transcript.
6. App posts each final rider transcript to `POST /ride/turn`; Jev classifies it (intent + "is this a yes?") and prefetches the data the LLM is about to ask for. Per-turn `[jev]` and `[tool]` lines in the backend log show routing, agreement with the LLM and timing.

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
- Tools need a public HTTPS URL each session: `cloudflared tunnel --protocol http2 --url http://localhost:3000` → put the printed URL in `PUBLIC_BASE_URL` → restart the backend. Without it `/agent/start` refuses to start rides.
- Backend checks without the phone: `node scripts/try-jev.js [--gap 35] "<sentence>"`, `node scripts/try-maps.js route <lat>,<lng> "<address>"` / `places <lat>,<lng> <category> ["<text>"]`, `node scripts/ride-check.js "<sentence>" ...` (real agent, text injected with Agora `think`; phone-backed tools fail with `phone_not_responding`). With a live ride, `POST /debug/think` (ride token) injects text into it.
- Android: open `/android` in Android Studio. Backend URL: `-PPILLION_BACKEND_URL=...` Gradle property, else `PILLION_BACKEND_URL` in `android/local.properties`, else `http://10.0.2.2:3000` (emulator).
- Real phone (USB or wireless debugging): `.\scripts\phone.ps1` — adb reverse, build with `http://localhost:3000`, install, follow logs. Wireless adb drops on sleep/Wi-Fi change → reconnect and rerun.
- Real phone on mobile data: build with the tunnel's https URL and set `ALLOW_PUBLIC_RIDE_START=true` (by default `/agent/start`, `/agent/stop` and `/debug/latency` refuse requests that arrive through Cloudflare, so a leaked tunnel URL can't start agents on the Agora account).
- The emulator (`Pixel_8d`) is not usable for voice on this machine: its host mic delivers silence and audio output is broken system-wide. Test voice on the Realme.

## Decisions

- **Default LLM is Agora-managed `gpt-4o-mini`, not Gemini (Phase 1).** Measured on the Realme with Agora `message.metrics` (23 Sep 2026): Gemini 3.5 Flash-Lite took 24–37 s to first token, gpt-4o-mini 0.5–1.5 s, with equal answer quality and language matching. Direct Gemini API calls were equally slow at the time (10–76 s), so it was Google-side latency, not Agora. Gemini's thinking is already at its minimum (`minimal`) on this model. Gemini stays available via `LLM_PROVIDER=gemini`.
- Sarvam STT/TTS for Hindi, English and Hinglish; Agora-managed Deepgram + MiniMax as the fallback (`VOICE_STACK=managed`).
- Turn detection: end-of-speech silence 400 ms, prefix padding 240 ms, barge-in after 160 ms of speech (kept above EasyEV's 120 ms so road noise doesn't interrupt Pillion).
- Agent `audio_scenario: aiserver` (network resilience); app uses `AUDIO_SCENARIO_AI_CLIENT` + AI echo cancellation + AI noise suppression per Agora's audio best-practice doc.
- **Phase 2 architecture: Agora custom tools with the managed gpt-4o-mini, not the backend as a custom LLM.** A custom LLM would need our own OpenAI key (Agora-managed models can't be called from outside), would put the laptop and tunnel into every turn, and can't use Agora's custom tools at all. Cost: Jev can't sit in front of the LLM.
- **Jev runs alongside the LLM, never in front of it.** One call per final transcript (intent plus, if an SMS/call is pending, "is this a clear yes?"). Confident intent → prefetch that tool's data. Jev off, slow (1.5 s timeout), rate-limited (30 s cooldown after 429) or unsure → nothing changes. `JEV_ENABLED=false` turns it off.
- **SMS/call need a spoken yes, checked on our server** (Agora's guidance for action tools): `prepareSms`/`prepareCall` only store a pending action; `confirmPendingAction` acts only if Jev reads the rider's actual transcript as "yes" (p ≥ 0.8). If Jev didn't answer, the LLM's judgement stands.
- **Filler words at 1500 ms, Agora-generated (in the rider's language), static Hindi fallback.** Agora only has a "LLM silent for N ms" trigger; chat replies start in 0.5–1.5 s, tool turns take 2.5 s+, so 1500 ms ≈ tool turns only. An LLM "Ek second…" preamble was tried and rejected (see SDK issue 11).
- **Maps on Geoapify (Google billing verification failed).** Geoapify ETA has **no live traffic**: it uses Geoapify's `traffic=approximated` model (typical slowdowns on busy roads). The tool result says `traffic: "typical_estimate"` and the prompt forbids claiming live traffic. For scale: 8 km Connaught Place → Laxmi Nagar Metro = 8 min free-flow vs 26 min approximated. Nearby places use fixed categories (Geoapify Places has no free-text search; "puncture" as text finds nothing), then the route matrix gives road distances and re-sorts by them. The drop address is geocoded (cached); wording matters: "Laxmi Nagar Metro Station, Delhi" resolves exactly, the same with "Vikas Marg, 110092" lands ~2 km off. OCR addresses (Phase 4) will need care or Google.
- Customer phone number stays on the phone; the backend only sees name, drop address, and GPS when a tool needs them. Seeded order ships with no number, so SMS/calls can't reach a stranger; debug builds take `PILLION_TEST_CUSTOMER_PHONE` from `android/local.properties` (gitignored) or the debug card.
- Phone calls: placed with `TelecomManager.placeCall` (works with the screen locked). While any call is active, the app disables Agora mic capture and mutes the agent (call state via `TelephonyCallback`/`PhoneStateListener`).

## Later phases (agreed)

- **Final phase:** README, including the "Agora SDK feedback" section built from the notes below.
- **Phase 2 leftovers to verify on device:** permission-denied cards (skipped), earnings with Jev off (see Phase 2 tests), the "✓ Delivered" line (depends on Vi sending delivery reports). SMS to the iPhone test number didn't arrive while Android → Android did: iPhone-side filtering, not the app.

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
8. Signaling REST (server → user peer message): docs show `x-agora-token` + `x-agora-uid` (→ 401 "invalid token") or Basic auth with Customer ID/Secret. `Authorization: agora token=<RTM token>` (signed with the app certificate) works. ~0.3–0.4 s per message from India.
9. Filler words: phrases containing non-Latin characters are rejected above 20 characters (HTTP 400 at agent start); the docs say 50 code points.
10. Filler words have no tool-call trigger (docs: "Custom tools don't add a separate, tool-specific filler word trigger"); only a fixed LLM-wait threshold.
11. If the LLM writes a short text ("Ek second, dekhti hoon.") in the same response as a tool call, the tool is never called — 0 of 3 tool requests reached the endpoint in our test.
12. Custom tools don't support custom LLMs, so "own LLM endpoint" and "Agora tools" are mutually exclusive designs.
13. Non-JSON tool error bodies go verbatim into the LLM context. We hit it because Cloudflare (the tunnel) replaces an origin's 502/504 with a large HTML error page (400/403/409/424/500/503 pass through). Tool failures now use 4xx (424 for phone/maps).
14. Step-1 check (think API, no audio): managed gpt-4o-mini called both custom tools correctly, including `{{args.*}}`, `{{template_variables.*}}` in headers and `{{tool_call_id}}`. User turn → tool result ≈ 2.6 s once and ≈ 9 s once (the second right after an interrupted reply).
15. Idle timeout also applies when the remote user never joins (agent stops ~30–40 s after start), and history is gone after stop — scripts must fetch history while the agent runs.

## Jev (TypeSafe AI) notes for the README

- jevai.org is an independent community hub with its own proxy (`POST https://www.jevai.org/api/v1/decisions`, `jev_…` keys, response wrapped as `{code, message, data: {answers}}`). TypeSafe's own API (`api.typesafe.ai/v1/systemone`, `sk-…` keys) rejects these keys (401).
- jevai.org rejects `model: "jev-latest"` ("model must be a Jev identifier such as typesafe-ai/jev"); omit it. TypeSafe requires it.
- Latency from India: 0.45–0.8 s per call (one to two questions).
- Rate limit on the community key is very tight: one call gets through, the next ones get 429 for a minute or more, even 35 s apart (1 of 6). No Retry-After header; one call hung 11 s. Hence the 30 s cooldown and the design where Jev is never on the critical path.
- Answers seen: "Customer ko bolo 5 minute mein pahunch raha hoon" → sms_customer 0.52 (call_customer 0.36); "Customer ko call lagao" → call_customer 1.00; "aaj kitna kamaya" → earnings 1.00.

## Phase 2 tests

- **Jev off (`JEV_ENABLED=false`), Realme, 24 Sep 2026, two rides.** Every turn logged `[jev] … skipped (off); LLM routes alone`: no Jev calls, no errors.
  - "नेक्स्ट ड्रॉप कितना दूर है?" → `getNextDropEta` called 1.6 s after the transcript, ok in 2.8 s → "Next drop · 6.9 km · 25 min".
  - "पास में पेट्रोल पंप बताओ।" → `findNearby` called 1.5 s after the transcript, ok in 1.4 s → Indraprastha Gas Limited, 1.1 km.
  - A misheard "कोई फॉर्म बताओ।" got a clarifying question and no tool call (correct).
  - "Aaj kitna kamaya?" was not asked in the Jev-off rides (earnings was tested earlier with Jev on).
  - Jev switched back on afterwards; a live call answered "Aaj kitna kamaya?" → earnings, confidence 1.00, 0.67 s (the call before it hit the 1.5 s timeout and was skipped, as designed).
- The per-turn `[latency]` e2e on tool turns (3.1–3.3 s here) measures the first audio, which is the filler ("जांच कर रहा हूँ"); the real answer follows ~1–1.5 s later.

## Phase log

- Phase 1 done: foundation + live voice loop — Start/End Ride, Hindi/English/Hinglish voice with live transcript, barge-in, mic permission flow, locked-screen foreground service, per-turn latency metrics; default LLM switched to Agora-managed gpt-4o-mini (Gemini 24–37 s → gpt-4o-mini ~1 s to first token).
- Phase 2 done: voice actions via Agora ConvoAI custom tools — next-drop ETA and nearby places (Geoapify; Google behind `MAPS_PROVIDER`), today's earnings vs yesterday (seeded on-phone SQLite), SMS and call to the customer after a spoken yes checked on the server, device actions relayed over server-sent RTM messages, action lines in the transcript, calls pause Pillion's audio, Jev intent routing alongside the LLM with per-turn logs, filler words at 1.5 s (generated, in the rider's language). Verified on the Realme in Hindi and English: ETA, nearby, earnings, SMS (Android → Android), call.
