# Pillion: a hands-free voice co-pilot for riders on Agora Conversational AI

A native Android app (Kotlin, Jetpack Compose) for India's delivery and bike-taxi riders, who can't
touch their phone while riding. The rider talks through earphones in **Hindi, English or Hinglish**, and
an **Agora Conversational AI** agent answers in real time and takes real actions on the phone: next-drop
ETA, nearby places, earnings, an SMS or a call to the customer after a spoken "haan", answering an
incoming call by voice, and an SOS with a live link that lets family hear and talk to the rider.

🏆 1st place, Agora Voice AI Hackathon 2026 (AI Mobile Coders).
[Repository](https://github.com/jatinvats123/pillion) ·
[APK](https://github.com/jatinvats123/pillion/releases/latest) ·
[Demo video](https://youtu.be/3c9N2AVr5nM)

## The idea

A voice agent on a moving bike has to do more than chat:

- **It acts on the phone.** The agent lives in Agora's cloud, but GPS, SMS, calls and the active order
  live on the phone. Custom tools call a small backend, and the backend asks the phone over **RTM**.
- **It speaks first when it matters.** A crash check ("आप ठीक हो?") or a ringing phone can't wait for the
  rider to ask, so the backend uses the **speak** and **think** APIs.
- **It steps aside for a human.** When family joins the rider's channel from an SOS link, the agent hands
  over the line and comes back when they leave.
- **It survives the road.** AI noise suppression and echo cancellation, a barge-in threshold tuned for
  traffic, and filler words while a tool runs.

## The scenario

> Rider: "नेक्स्ट ड्रॉप कितना दूर है?"
> Pillion: "Laxmi Nagar लगभग 8 km है, करीब 26 minute लगेंगे।"
>
> Rider: "Customer ko bolo 5 minute mein pahunch raha hoon." → Pillion: "Rahul को भेज दूँ?" → "हाँ।" → SMS sent.
>
> *The phone rings.* Pillion: "Customer Rahul का call आ रहा है। उठाऊँ?" → "हाँ, उठाओ।" → answered.

## What it uses

| Agora piece | How Pillion uses it |
|---|---|
| Conversational AI Engine (`agora-agents` Node SDK) | One agent per ride: Sarvam STT → Agora-managed `gpt-4o-mini` → Sarvam TTS (`bulbul:v3`), scoped to the rider's uid. |
| Custom tools (`llm.tools`) | ETA, nearby places, earnings, weather, SMS, call, SOS, answer an incoming call. Agora POSTs the backend with a per-ride secret from a template variable. |
| RTC (Android Voice SDK 4.6.4) | `AUDIO_SCENARIO_AI_CLIENT` with the AI noise suppression and AI echo cancellation extensions; volume indication drives the UI. |
| RTM (Signaling) | Transcripts, agent state and per-turn latency metrics to the phone; server-to-peer messages from the backend to the phone ("read GPS", "send this SMS"). |
| Speak API | The crash check, the order confirmation, the family handover line. |
| Think API | Tells the agent a phone call is ringing, so it can ask whether to answer. |
| Web SDK (4.24.8) | The family's Live Guardian page: listen to the rider, hold to talk. RTC-only token. |
| Filler words | Generated in the rider's language after 1.5 s of LLM silence. |

## Prerequisites

- An Agora project with the **App Certificate** on, and **Conversational AI** and **Signaling (RTM)** enabled.
- A **Sarvam** API key (speech in and out for Hindi and Hinglish) and a **Geoapify** key (maps).
- Node.js 20.12+, Android Studio (its bundled JDK), an Android phone (8.0+) with earphones.
- A public HTTPS URL for the backend while developing (e.g. `cloudflared`), because Agora calls the tools
  over the internet.

## Run it

### 1. The backend

```bash
cd backend
cp .env.example .env      # AGORA_APP_ID, AGORA_APP_CERTIFICATE, SARVAM_API_KEY, GEOAPIFY_API_KEY
npm install
cloudflared tunnel --protocol http2 --url http://localhost:3000   # put the URL in PUBLIC_BASE_URL
npm run dev
```

`GET /health` should answer `"ok": true`. Optional features are flags in `.env`: `GUARDIAN_ENABLED`
(SOS live link for family) and `CALL_ANSWER_ENABLED` (answer calls by voice). The full list of settings is
in the [README](https://github.com/jatinvats123/pillion#run-it-yourself), and hosting on Render in
[docs/DEPLOY.md](https://github.com/jatinvats123/pillion/blob/main/docs/DEPLOY.md).

### 2. The Android app

Open `android/` in Android Studio. In `android/local.properties`, set `PILLION_BACKEND_URL` to the
backend (the laptop's Wi-Fi address, or the tunnel URL), then run the `app` configuration on a phone.

Or skip building: install the [latest APK](https://github.com/jatinvats123/pillion/releases/latest), which
talks to the hosted demo server.

### 3. Try it

Earphones in, tap **Start ride**, and ask "Next drop kitna door hai?", "Paas mein petrol pump batao",
"Aaj kitna kamaya?". For SMS and calls, put a number you own in Settings → Demo order: test number. For
the crash check, add a second phone as an emergency contact and use Settings → Try the crash check.

## How it's wired

### Tools that call your backend, with a per-ride secret

Each tool is an HTTPS call from Agora's cloud. The ride's secret travels as a template variable, so the
backend knows which ride (and which phone) the call belongs to.

```js
// backend/src/tools.js
server: {
  method: 'POST',
  url: `${config.publicBaseUrl}/tools/${name}`,
  headers: { Authorization: '{{template_variables.tool_auth}}', 'Content-Type': 'application/json' },
  body: { ...Object.fromEntries(args.map((a) => [a, `{{args.${a}}}`])), tool_call_id: '{{tool_call_id}}' },
  timeout_ms: 10_000,
},

// backend/src/agora.js, when the agent starts
templateVariables: { tool_auth: `Bearer ${ride.token}` },
```

Every argument is listed as `required`: Agora fails the call if a body placeholder has no value.

### The phone does the work, over RTM

GPS, SMS and calls only exist on the phone. The tool endpoint sends the phone a server-to-peer RTM
message, waits for the phone to `POST /ride/device-result`, and returns JSON to the LLM.

```js
// backend/src/rtm.js
await fetch(`https://api.agora.io/dev/v2/project/${appId}/rtm/users/${SENDER}/peer_messages`, {
  method: 'POST',
  headers: { Authorization: `agora token=${rtmToken}`, 'Content-Type': 'application/json;charset=utf-8' },
  body: JSON.stringify({ destination: String(riderUid), payload: JSON.stringify(message),
    enable_offline_messaging: false, enable_historical_messaging: false }),
});
```

The phone is already logged in to RTM for the agent's transcripts, so this needs no second connection or
push service.

### Actions only after a spoken "yes"

`prepareSms` and `prepareCall` only store a pending action; `confirmPendingAction` acts when the rider's
actual words read as a clear yes. The LLM can't send a message on its own judgement.

### The agent speaks first: speak and think

```js
// A safety prompt that must cut through whatever the agent was saying
await ride.session.say('आप ठीक हो?', { priority: 'INTERRUPT', interruptable: false });

// A ringing phone: the agent learns who is calling and asks whether to answer
await ride.session.think(callRingingThink(call), {
  on_listening_action: 'interrupt', on_thinking_action: 'interrupt', on_speaking_action: 'interrupt',
  interruptable: false,
});
```

Speak text lands in the LLM's history, so the agent understands the rider's reply to "आप ठीक हो?".

### Handing the line to a human

An agent can't be paused, so when family joins the rider's channel (Web SDK, RTC-only token) the backend
speaks one line ("आपके घरवाले line पे हैं।") and stops the agent. When the last family member leaves, it
starts a fresh agent in the same channel with one line of context, which greets "मैं वापस हूँ…".

### Audio for a voice agent on a bike (Android)

```kotlin
// voice/VoiceSession.kt
mAudioScenario = Constants.AUDIO_SCENARIO_AI_CLIENT   // + AI noise suppression and AI echo cancellation
engine.enableAudioVolumeIndication(100, 3, true)      // drives the "listening / speaking" orb

// Answer calls by voice only: keep the mic on while the phone rings
engine.setParameters("""{"che.audio.bypass_pstn_call_event":true}""")
```

The audio parameters are re-applied on every audio route change (wired ↔ Bluetooth). Turn detection uses
400 ms of silence for end of speech and 160 ms of speech for barge-in, so traffic noise doesn't cut the
agent off.

## What "working" means

- Start ride → the greeting plays within a few seconds, with an English subtitle under the Hindi line.
- "Next drop kitna door hai?" → a filler line, then the ETA, and a "Next drop · km · min" line in the transcript.
- Speaking over Pillion stops it at once.
- "Customer ko bolo…" → it asks first; "haan" → the SMS arrives on the other phone.
- A call from the test number → "Customer … का call आ रहा है। उठाऊँ?" → "हाँ, उठाओ" → answered.
- Settings → Try the crash check → siren, "आप ठीक हो?", countdown → SOS SMS with a live link; opening
  the link lets the family listen and talk, and the agent steps aside until they leave.
