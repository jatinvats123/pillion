# Pillion demo video

Main cut **2:50–3:00, landscape 1920×1080**, plus a **30–45 s vertical cut** for YouTube Shorts.
Pillion's answers below are examples: use whatever it really says (the subtitles are yours to write in the
editor). Every line the rider speaks is in the "Rider" column; English subtitles in the last column.

## Main cut (≈ 3:00)

| Time | Shot | Rider says | Pillion says (example) | English subtitle |
|---|---|---|---|---|
| 0:00–0:10 | **Cold open, real world.** Bike parked at the roadside (or filmed from the pillion seat as a passenger, someone else riding), helmet on, phone in the pocket, earphones in. Text on screen: *"India's gig riders run their job on a phone they can't touch while riding."* | "Next drop kitna door hai?" | "Laxmi Nagar लगभग 8 km है, करीब 26 minute लगेंगे।" | "How far is the next drop?" / "Laxmi Nagar is about 8 km, around 26 minutes." |
| 0:10–0:25 | **Trailer** made with the `/brag` skill (landscape): logo, "Pillion — the AI that rides with you", the glass globe, feature beats. | — | — | — |
| 0:25–0:45 | **Start ride** (scrcpy mirror, picture-in-picture with the rider). The globe wakes up, the greeting appears with its subtitle. Then Hindi, then English. | "Aaj kitna kamaya?" … "How far is my next drop?" | "आज 12 trips में ₹840, कल से ₹120 ज़्यादा।" … "About eight kilometres, twenty-six minutes." | "How much did I earn today?" / "₹840 from 12 trips, ₹120 more than yesterday." |
| 0:45–0:55 | **Barge-in + nearby.** Ask, interrupt mid-answer. | "Paas mein petrol pump?" … (interrupting) "Ruko, puncture wala batao." | stops at once → "सबसे पास puncture की दुकान 600 meter पर है।" | "Petrol pump nearby?" … "Wait, find a puncture shop." |
| 0:55–1:15 | **SMS and call with a spoken yes.** Second phone in frame. | "Customer ko bolo 5 minute mein pahunch raha hoon." … "Haan." … "Customer ko call lagao." … "Haan." | "Rahul को भेज दूँ?" … "✓ SMS भेज दिया।" … "Rahul को call करूँ?" | "Tell the customer I'll be there in 5 minutes." / "Send it to Rahul?" / "Yes." |
| 1:15–1:30 | **Order scan.** In the gallery: the mock order screenshot → Share → "Pillion: scan order" → the confirm card → Set. | — | "Rahul का order set हो गया, drop Laxmi Nagar में।" | "Rahul's order is set, drop in Laxmi Nagar." |
| 1:30–2:05 | **Crash check → SOS → Live Guardian.** Bike parked. Settings → *Try the crash check* (caption: "a test sensor trace through the real detector"). Alarm, red screen, countdown runs out. Cut to the second phone: SMS with the link → tap → map + "Connected" → **Listen** → hold **Talk**. | (to family) "Haan, main theek hoon, bas gir gaya tha." | "आपके घरवाले line पे हैं।" … (family closes the page) "मैं वापस हूँ। कुछ चाहिए तो बोलिए।" | Family: "Beta, tum theek ho?" → "Are you OK, dear?" / Rider: "Yes, I'm fine, I just fell." / "Your family is on the line." / "I'm back." |
| 2:05–2:20 | **SOS with no internet.** Mobile data off on screen (quick settings), Start ride → "voice offline", tap the red **SOS** → 5 s → SMS lands on the second phone. Caption: "Crash detection and SOS work without internet." | — | (Android TTS) "5 second में आपके contacts को message जाएगा।" | "Messaging your contacts in five seconds." |
| 2:20–2:50 | **Architecture + Agora slide** (the README's diagram, cleaned up). Voice-over: ConvoAI agent per ride (Sarvam STT/TTS, managed gpt-4o-mini, VAD turn detection, filler words, metrics), custom tools, RTC with AI noise suppression + echo cancellation, RTM for transcripts and server-sent device requests, the speak API for safety prompts, RTC-only tokens + Web SDK for family, agent stop/restart handoff. | — | — | — |
| 2:50–3:00 | **End card:** logo, "Pillion — the AI that rides with you", `github.com/jatinvats123/pillion`, "APK in Releases", "Built by Jatin Vats, solo, on Agora Conversational AI". | — | — | — |

## Add-on clip: Answer calls by voice (≈ 20 s)

Needs `CALL_ANSWER_ENABLED=true` on the backend (Render: Environment, then a manual deploy) and **Settings →
Answer calls by voice** on in the app (allow both permissions). The second phone is the demo order's test
number. Wired earphones: the ringtone and the question both play in them (tested on the Realme). Bike parked,
phone in the pocket, screen off.

| Time | Shot | Rider says | Pillion says | English subtitle |
|---|---|---|---|---|
| 0:00–0:03 | Text on screen: *"A customer calls mid-ride. Hands stay on the handlebar."* The second phone dials. | — | — | — |
| 0:03–0:10 | The Realme rings in the pocket (insert: the second phone's screen "Calling…"). | "Haan, utha lo." | (the phone, over the ringtone) "Customer Rahul का call आ रहा है। उठाऊँ?" | "Your customer Rahul is calling. Pick up?" / "Yes, pick it up." |
| 0:10–0:14 | The call connects (insert: the second phone's timer starts). Caption: "Answered by voice in ~2 s." | "Haan Rahul, 5 minute mein pahunch raha hoon." | — | "Yes Rahul, I'll be there in 5 minutes." |
| 0:14–0:20 | Second call, same setup. | "Baad mein." | "Customer Rahul का call आ रहा है। उठाऊँ?" | "Later." → the call is declined (insert: "Call ended" on the second phone). |

## Shorts cut (30–45 s, 1080×1920)

1. **0–3 s** hook, text on screen: "Riding? Don't touch your phone." Rider (parked): "Next drop kitna door hai?"
2. **3–12 s** the answer + barge-in on "petrol pump" → "puncture wala batao". Full-height scrcpy capture, globe centred.
3. **12–22 s** crash check: alarm, red screen, countdown, SMS on the second phone.
4. **22–35 s** family taps the link: map, Listen, hold Talk "Beta, tum theek ho?", "आपके घरवाले line पे हैं।"
5. **35–42 s** end card: "Pillion — the AI that rides with you. Built on Agora Conversational AI."

## Recording the phone with scrcpy

scrcpy is at `C:\Users\jatin\Downloads\scrcpy-win64-v4.1\scrcpy-win64-v4.1`.

**Keep the two adb's from fighting.** scrcpy ships its own `adb.exe`; if it starts a server of a different
version than the SDK's, each one kills the other's server and wireless adb drops. Make scrcpy use the SDK's:

```powershell
$env:ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $env:ADB connect <IP:port>          # Wireless debugging; `& $env:ADB mdns services` if it moved
cd C:\Users\jatin\Downloads\scrcpy-win64-v4.1\scrcpy-win64-v4.1
```

**Mirror** (rehearsals):

```powershell
.\scrcpy.exe -s <IP:port> --no-audio --stay-awake --max-fps=60
```

**Record the screen** (one file per take):

```powershell
.\scrcpy.exe -s <IP:port> --no-audio --stay-awake --max-fps=60 --video-bit-rate=16M --record=take01.mp4
```

**About the phone's audio on Android 11:** scrcpy can forward audio on Android 11+, but its default source
**stops the sound playing on the phone** while it records (the "keep playing" option, `--audio-dup`, needs
Android 13), so you wouldn't hear Pillion in your earphones. Pillion's voice is also call-style audio, which
Android may not let a recorder capture at all. So for takes where Pillion talks:

- **Recommended:** record the screen with the command above (no audio), and record the sound separately:
  phone on **speaker** (earphones out) in a quiet room, laptop or second-phone voice recorder next to it.
  Clap once at the start of each take to sync in the editor.
- For the real-world shots (cold open, Live Guardian on the second phone), film with a second phone's camera:
  it gets picture and sound together.
- If you want to try scrcpy's audio anyway: add `--audio-codec=aac` instead of `--no-audio` for a 10-second
  test and check that Pillion's voice is in the file before relying on it.

**Clean status bar** on the phone for the recording (undo with `exit`):

```powershell
& $env:ADB -s <IP:port> shell settings put global sysui_demo_allowed 1
& $env:ADB -s <IP:port> shell am broadcast -a com.android.systemui.demo -e command enter
& $env:ADB -s <IP:port> shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1030
& $env:ADB -s <IP:port> shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
# afterwards:
& $env:ADB -s <IP:port> shell am broadcast -a com.android.systemui.demo -e command exit
```

## Safety for real-world shots

- **Never touch the phone while riding, and never ride while filming yourself.** Film parked at the
  roadside, or sit on the pillion seat as a passenger while someone else rides, helmets on.
- The crash check is always started from **Settings → Try the crash check with the bike parked**. Never drop
  or throw the phone near traffic.
- Every SMS and call goes to **your own second phone**. The mock order numbers are invented: never text or
  call them. Don't call 112 on camera.
- If a real contact (family) is your emergency contact, tell them first that a test SOS is coming.
- Keep faces and number plates of strangers out of frame, or blur them.

## Demo-mode checklist (the day of recording)

- [ ] Backend live on Render: `/health` shows `"guardian":true`, `"mode":"public"`; nobody deploys today.
      Open `/health` a minute before each take: the free server sleeps after 15 idle minutes.
- [ ] Fresh install of the release APK (or Settings → Apps → Pillion → Clear storage), so the first-run
      screen and seeded earnings look right.
- [ ] Rider name "Jatin"; emergency contact = your second phone; **Settings → Demo order: test number** =
      your second phone.
- [ ] Call add-on: `CALL_ANSWER_ENABLED=true` on the server and **Settings → Answer calls by voice** on.
      The second phone is both the test number and a contact: the customer match wins ("Customer Rahul").
- [ ] Sample order screenshots in the gallery: `.\scripts\push-order-samples.ps1 -Serial <IP:port>`.
- [ ] Both phones charged above 80%; the Realme's battery optimisation off for Pillion.
- [ ] **Do Not Disturb** on the Realme with an exception for the second phone; notifications from other
      apps silenced; ringer on the second phone on so the SMS and call are audible.
- [ ] Earphones charged and connected (wired is the most reliable); a quiet place for the voice scenes.
- [ ] Brightness up, font size normal, theme as you want it (the sun/moon button).
- [ ] Wi-Fi off, mobile data on (it's the real-world case), and a strong signal.
- [ ] A test ride first: greeting, one question, End ride. The demo server allows 10 minutes of voice per
      ride, so start a new ride for each long take.
