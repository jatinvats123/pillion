# Submission pack (Agora Voice AI Hackathon, Commudle form)

Copy-paste ready. First person, as Jatin.

## Project name

Pillion

## One-liner

Pillion is the AI that rides with you: a hands-free voice co-pilot for India's gig riders, in Hindi, English
and Hinglish, built on Agora Conversational AI.

## Short description (150 words)

<!-- short:start -->
Pillion is a hands-free voice co-pilot for India's delivery and bike-taxi riders, who run their job on a
phone they can't safely touch while riding. With earphones in, a rider just talks in Hindi, English or
Hinglish, and Pillion answers in real time: the next drop's ETA, the nearest petrol pump or puncture shop,
today's earnings, and a text or call to the customer after a spoken "yes". It reads customer details from a
shared delivery-app screenshot with on-device OCR. It also watches over the rider: the
phone's sensors detect a crash, Pillion asks "Aap theek ho?", and if nobody answers it texts the emergency
contacts with the location, even without internet. The SOS link lets family hear the rider, talk into their
earphones and see them on a live map. The voice runs on Agora Conversational AI with Sarvam speech and
gpt-4o-mini; the app is native Kotlin.
<!-- short:end -->

## Long description (300 words)

<!-- long:start -->
India's delivery and bike-taxi riders run their job on a phone clipped to the handlebar: the next
drop, customer calls, "5 minute mein pahunch raha hoon" texts, earnings. Every glance on the road is a risk,
and when a rider crashes alone, nobody knows. I built Pillion to be the friend on the pillion seat.

The rider puts earphones in and talks, in Hindi, English or Hinglish, switching freely.
Pillion answers in about a second and can be interrupted. It gives the next drop's ETA, finds the nearest
petrol pump, puncture shop or ATM, reads today's earnings, and texts or calls the customer only after a
spoken "yes" that my server double-checks. Sharing a screenshot of the delivery app's order screen fills in
the customer's name, number and drop address with on-device OCR.

Safety runs on the phone, with or without internet. Accelerometer, gyroscope and GPS detect a crash; Pillion
asks "Aap theek ho?" through Agora's speak API, sounds an alarm and counts down; if nobody answers, the
emergency contacts get an SMS with the location. The SMS carries a Live Guardian link: family opens it in any
browser, hears the rider, holds a button to talk into their earphones and sees them on a live map, while the
agent steps aside.

Agora is the whole voice path: a Conversational AI agent per ride with Sarvam speech-to-text and
text-to-speech, Agora-managed gpt-4o-mini, tuned turn detection and filler words; eight custom tools
answered by the phone through server-sent RTM messages; RTC with AI noise suppression and echo cancellation;
the Web SDK with RTC-only tokens for family. English subtitles, big touch targets, TalkBack labels,
reduced motion and automatic night mode make it usable on a moving bike.

I built it solo, measured the latency, and wrote down twenty-one pieces of SDK feedback.
<!-- long:end -->

## Tech stack

- **Android:** Kotlin, Jetpack Compose (Material 3, own design tokens), MVVM with coroutines/Flow, minSdk 26;
  Agora Voice SDK 4.6.4 with the AI noise suppression and AI echo cancellation extensions; Agora RTM 2.3
  (lite); ML Kit Text Recognition v2 (Devanagari + Latin, on-device); CameraX; OpenGL ES 2.0 for the glass
  globe; SQLite for earnings and the safety log.
- **Backend:** Node.js + Express, the official `agora-agents` Node SDK 2.10, `agora-token`; hosted on Render.
- **Voice AI:** Agora Conversational AI Engine, Sarvam STT/TTS (`bulbul:v3`) and Sarvam translate,
  Agora-managed OpenAI gpt-4o-mini.
- **Actions:** Agora ConvoAI custom tools, Geoapify (routing, places, geocoding on OpenStreetMap data),
  Jev by TypeSafe AI as an intent router beside the LLM.
- **Family page:** Agora Web SDK 4.24.8, Leaflet + OpenStreetMap.

## How Agora Conversational AI is used

Each ride starts one ConvoAI agent from my backend, scoped to the rider's RTC uid, with Sarvam STT
(language auto-detected per utterance), Agora-managed gpt-4o-mini and Sarvam TTS. Turn detection is VAD
with 400 ms end-of-speech silence and barge-in after 160 ms of speech (high enough that road noise doesn't
interrupt). Agora-generated filler words cover tool turns in the rider's language. Eight custom tools
(ETA, nearby, earnings, weather, prepare SMS, prepare call, confirm, SOS) call my backend with a per-ride secret;
the backend asks the phone over server-sent RTM messages and returns JSON to the LLM. Transcripts, agent
state, interrupts, errors and per-turn metrics arrive on the phone over RTM and drive the live transcript and
the glass globe. The speak API voices safety prompts ("Aap theek ho?") with interrupt priority. For Live
Guardian, family joins the same channel from a browser with an RTC-only token; the agent says one line and
is stopped, and a fresh agent resumes when they leave.

## Links

- **Repository:** https://github.com/jatinvats123/pillion
- **APK:** https://github.com/jatinvats123/pillion/releases/latest
- **Demo video:** _(add the YouTube link)_
- **Shorts cut:** _(add the link)_

## About me

I'm Jatin Vats, an Android developer (Kotlin and Jetpack Compose) interning at a two-wheeler rental company.
I built Pillion alone for this hackathon: the app, the backend, the crash detector and the family page.
