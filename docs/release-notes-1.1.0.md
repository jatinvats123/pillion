🏆 Pillion won **1st place** at the Agora Voice AI Hackathon 2026 by AI Mobile Coders. This release has
everything shown in the final round.

**Install:** download `pillion-1.1.0.apk` below (Android 8.0+, arm64 / armv7 phones), allow installs from
your browser, open it and allow the microphone. Already on 1.0.0? It installs as an update; your settings
and contacts stay. Check the file with the `.sha256` next to it.

**New in 1.1.0**
- **Answer calls by voice.** The phone rings mid-ride, Pillion says who is calling ("Customer Rahul ka call
  aa raha hai, uthaun?") and answers or declines on "haan" / "baad mein". Turn it on in Settings → Answer
  calls by voice (it asks for the call permissions there). The caller's number never leaves the phone.
- **The call question is spoken reliably** on phones that put Google's voice engine to sleep in the
  background (seen on ColorOS): Pillion reconnects it when the phone rings.
- **The "GPS weak (indoors?)" notice** shows for a few seconds instead of staying on the ride screen.

**Try:** put earphones in, tap Start ride and ask "Next drop kitna door hai?", "Nearest petrol pump",
"Aaj kitna kamaya?". For SMS and calls, put a number you own in Settings → Demo order: test number. For the
crash check and SOS, add your own second phone as an emergency contact, start a ride, then Settings → Try the
crash check (it can send a real SMS).

**Demo server limits:** it's on a free plan, so after a quiet spell the first ride can take up to a minute
("Waking up the server…"). 10 minutes of voice per ride (crash detection and SOS keep running) and a daily
cap on rides. If you see "Demo limit reached", please try again later.

How it works and how it uses Agora: [README](https://github.com/jatinvats123/pillion#readme).
