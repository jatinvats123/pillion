# Release 1.0.0: build, phone check, GitHub Release

## 1. Build the signed APK (after the backend is live)

`android/local.properties` already has the keystore path, its password and `PILLION_APP_KEY` (all outside
git). **Back up `%USERPROFILE%\.pillion\pillion-release.jks` and the `PILLION_KEYSTORE_PASSWORD` line
together** (password manager + a second drive): without both, no update of this app can ever be signed.

```powershell
cd C:\Users\jatin\Raah-saathi\android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
# only if Render gave a different URL:  add PILLION_RELEASE_BACKEND_URL=https://... to local.properties
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest
Copy-Item app\build\outputs\apk\release\app-release.apk ..\pillion-1.0.0.apk
```

## 2. Install on the Realme

The debug and release builds are signed differently, so the debug app has to go first. **This deletes the
debug app's data** on the phone (emergency contacts, scanned order, rider name, ride history): note your
contacts first.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb connect <IP:port>            # from Wireless debugging; `& $adb mdns services` if it moved
& $adb -s <IP:port> uninstall app.pillion
& $adb -s <IP:port> install C:\Users\jatin\Raah-saathi\pillion-1.0.0.apk
```

## 3. Phone checklist (Wi-Fi OFF, mobile data ON)

Set up: rider name; emergency contact = your second phone; **Settings → Demo order: test number** = your
second phone; earphones in.

- [ ] **Start ride**: greeting within a few seconds ("Waking up the server…" only if the server is slow).
- [ ] **Hindi:** "Next drop kitna door hai?" → ETA in Hindi.
- [ ] **English + numbers:** "How far is my next drop?" and "Nearest petrol pump" → English answers with the
      numbers **said in English** ("seven kilometres", not "saat"). *(Fixed today; not yet heard on a phone.)*
- [ ] **Earnings:** "Aaj kitna kamaya?" → today vs yesterday.
- [ ] **Barge-in:** interrupt a long answer → Pillion stops and listens.
- [ ] **SMS:** "Customer ko bolo 5 minute mein pahunch raha hoon" → "bhej doon?" → "haan" → SMS on the second phone.
- [ ] **Call:** "Customer ko call lagao" → "haan" → the second phone rings; Pillion goes quiet during the call.
- [ ] **Share to scan:** push the samples (`.\scripts\push-order-samples.ps1 -Serial <IP:port>`), open one in
      Photos → Share → "Pillion: scan order" → confirm → Pillion says the order is set.
- [ ] **Sample screen:** home card → "Try a sample order screen" → set it → ask to message the customer →
      Pillion says SMS and calls are off for the sample order.
- [ ] **Crash check:** Settings → Try the crash check → dialog → alarm, "आप ठीक हो?", countdown → let it run
      out → SMS with location **and a live link** on the second phone.
- [ ] **Live Guardian** (second phone opens the link): Connected + map dot → **Listen** hears you → **hold
      Talk** → you hear them in the earphones → Pillion says "आपके घरवाले line पे हैं।" once and stays quiet →
      close the page → "मैं वापस हूँ…" within ~2 s → tap **I'M OK NOW** → page says you're OK.
- [ ] **No data:** mobile data off → Start ride → "voice offline" → red **SOS** button → 5 s → the SMS still
      arrives (plain maps link, no live link).
- [ ] **Glass globe:** smooth while listening / thinking / speaking; no stutter when the transcript updates.
- [ ] **Light/dark:** the sun/moon button; after sunset a ride on "System" goes dark.
- [ ] **10-minute limit:** keep a ride going past 10 min → Pillion says the demo line → "voice offline"
      card with Retry; crash check still works.
- [ ] **Screenshots for the README** (the emulator can't show voice replies): the ride screen with a real
      conversation, light and dark → `docs/screenshots/ride-light.png` and `ride-dark.png` (1080 px is fine;
      I resize them to 540).

Anything that fails: note what you said, the time, and `adb logcat -d > log.txt` right after.

## 4. GitHub Release v1.0.0

Only after the checklist passes. Releases on a private repo are visible to collaborators only: the download
link works for judges once the repo is public.

```powershell
cd C:\Users\jatin\Raah-saathi
$hash = (Get-FileHash pillion-1.0.0.apk -Algorithm SHA256).Hash.ToLower()
"$hash  pillion-1.0.0.apk" | Out-File -Encoding ascii pillion-1.0.0.apk.sha256
gh release create v1.0.0 pillion-1.0.0.apk pillion-1.0.0.apk.sha256 --target main --title "Pillion 1.0.0" --notes-file docs/release-notes-1.0.0.md
```

(`*.apk` is gitignored, so the APK never lands in the repo itself.)
