The first public build of Pillion, the voice co-pilot for gig riders, built on Agora Conversational AI.

**Install:** download `pillion-1.0.0.apk` below (Android 8.0+, arm64 / armv7 phones), allow installs from
your browser, open it and allow the microphone. Check the file with the `.sha256` next to it.

**Try:** put earphones in, tap Start ride and ask "Next drop kitna door hai?", "Nearest petrol pump",
"Aaj kitna kamaya?". For SMS and calls, put a number you own in Settings → Demo order: test number. For the
crash check and SOS, add your own second phone as an emergency contact, start a ride, then Settings → Try the
crash check (it can send a real SMS).

**What's in it:** Hindi / English / Hinglish voice with barge-in, voice actions (ETA, nearby places,
earnings, SMS and calls after a spoken yes), order scan by on-device OCR, crash detection with an offline SOS
by SMS, Live Guardian (family hears and talks to the rider from the SOS link), English subtitles.

**Demo server limits:** it's on a free plan, so after a quiet spell the first ride can take up to a minute
("Waking up the server…"). 10 minutes of voice per ride (crash detection and SOS keep running) and a daily
cap on rides. If you see "Demo limit reached", please try again later.

Everything about how it works, the Agora pieces and the honest limits is in the
[README](https://github.com/jatinvats123/pillion#readme).
