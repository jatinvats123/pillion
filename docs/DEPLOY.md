# Deploying the Pillion backend

The backend keeps live rides, pending SMS/call confirmations and Live Guardian links **in memory, in one
process**, so it runs as **one instance** (never scaled out). It's on **Render Free**: $0, no card.

## Host choice

| Option | Cost (check the pricing page) | Verdict |
|---|---|---|
| **Render Free, Singapore** | **$0, no card** | ✓ **Chosen.** Sleeps after 15 min without traffic; the first request then takes up to about a minute (the app shows "Waking up the server…" and waits up to 75 s). A ride keeps it awake (the phone and Agora's tool calls talk to it all the time). It can restart now and then, which ends a live ride or SOS link: rare, and acceptable for a demo. |
| Render Starter, Singapore | ~$7/month, prorated | Always on, no cold start. Switch the service's **Instance type** to Starter if the free one gives trouble; nothing else changes. |
| Railway Hobby | ~$5/month incl. $5 usage | ✓ Would also work (always on, Singapore region). |
| Fly.io | ~$2–3/month, card needed | ✓ Works with `min_machines_running = 1` and auto-stop off; more setup. |

Every deploy restarts the process: **don't deploy while judges may be testing.** `render.yaml` sets
`autoDeploy: false`, so pushes to GitHub don't redeploy on their own.

## Steps (Render)

1. Merge `ship-v1` into `main` (or pick `ship-v1` in step 3).
2. <https://dashboard.render.com> → sign in with GitHub → allow access to the `pillion` repo (private is fine).
3. **New + → Blueprint** → pick the repo and branch `main` → Render reads [`render.yaml`](../render.yaml): one
   web service `pillion-backend`, Free plan, Singapore, root `backend`, `npm ci` / `npm start`, health check
   `/health`.
4. Render asks for the values marked `sync: false`: paste `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE`,
   `SARVAM_API_KEY`, `GEOAPIFY_API_KEY`, `JEV_API_KEY` from your `backend/.env`, and `APP_KEY` = the
   `PILLION_APP_KEY` line in `android/local.properties`. Leave `PUBLIC_BASE_URL` empty for now. **Apply.**
5. When the first deploy is live, copy the service URL from the top of its page (e.g.
   `https://pillion-backend.onrender.com`) → **Environment** tab → `PUBLIC_BASE_URL` = that URL → **Save
   changes** (Render restarts it).
6. Check: `curl https://<your-url>/health` → `"mode":"public"`, `"tools":true`, `"guardian":true`,
   `"maps":"geoapify"`.
7. If the URL isn't `https://pillion-backend.onrender.com`, put it in `android/local.properties` as
   `PILLION_RELEASE_BACKEND_URL=<url>` and rebuild the release APK.

**Keeping it awake (optional):** a free pinger (e.g. cron-job.org) calling `https://<your-url>/health` every
10 minutes keeps it from sleeping. Render's free hours (750 a month) are shared by all free services in the
workspace, and one service awake all month uses ~744 of them: if your other Render services are free too,
ping only during the judging window, or not at all (the app handles the wake-up).

Logs: the service's **Logs** tab. They carry `[start]`, `[tool]`, `[jev]`, `[limit]`, `[guardian]` lines
with ids and timings only (no transcripts, names, numbers or places).

## Environment variables (production)

Secrets only in the Render dashboard, never in git. Unlisted optional variables keep their defaults.

| Variable | Production value | Notes |
|---|---|---|
| `AGORA_APP_ID` | *secret* | 32 characters. |
| `AGORA_APP_CERTIFICATE` | *secret* | 32 characters. |
| `AGORA_AGENT_UID` | `1000` | In `render.yaml`. |
| `AGORA_AREA` | `AP` | In `render.yaml`. |
| `LLM_PROVIDER` | `openai` | Agora-managed gpt-4o-mini, no key. |
| `VOICE_STACK` | `sarvam` | |
| `SARVAM_API_KEY` | *secret* | |
| `MAPS_PROVIDER` | `geoapify` | |
| `GEOAPIFY_API_KEY` | *secret* | |
| `JEV_ENABLED` | `true` | `false` turns the router off; the LLM routes alone. |
| `JEV_API_KEY` | *secret* | |
| `PUBLIC_BASE_URL` | `https://<service>.onrender.com` | Tool calls from Agora and the SOS links use it. |
| `GUARDIAN_ENABLED` | `true` | |
| `CALL_ANSWER_ENABLED` | `true` | Answer calls by voice (riders also turn it on in Settings). |
| `APP_KEY` | *secret* (= `PILLION_APP_KEY`) | Public mode: `/agent/start`, `/agent/stop`, `/order/geocode` need it. |
| `RIDES_ENABLED` | `true` | **Kill switch:** `false` refuses new rides. |
| `MAX_RIDE_MINUTES` | `10` | Voice per ride (waits while an SOS link is live). |
| `MAX_RIDES_PER_DAY` | `40` | Resets at midnight IST (and on a restart). |
| `MAX_AGENT_MINUTES_PER_DAY` | `300` | Counted from agent start to stop. |
| `MAX_CONCURRENT_RIDES` | `5` | |
| `HOST` | `0.0.0.0` | In `render.yaml`. |
| `NODE_VERSION` | `22` | In `render.yaml`. |
| `PORT` | *(don't set)* | Render sets it. |
| `DEBUG_ROUTES` | *(don't set)* | Off in public mode. |
| `ALLOW_PUBLIC_RIDE_START` | *(don't set)* | Local-tunnel mode only; public mode uses `APP_KEY`. |

## What changed from "laptop + tunnel"

- **Public mode** (`APP_KEY` set): the old local-network rule can't work on a host, because every request
  arrives from the host's own proxy (a private address), and Render sits behind Cloudflare, so every request
  also carries `cf-ray`. Instead, starting/stopping rides and geocoding need the app key, from anywhere, plus
  rate limits per IP and per install. Without `APP_KEY` the backend behaves exactly as before, so
  `scripts/start.ps1` and the tunnel keep working for local development.
- `/order/geocode` works for the release app (app key or ride token), rate-limited per IP.
- `/debug/*` is off in public mode.
- SIGTERM (a deploy or restart) stops all agents, like Ctrl+C locally.

## The kill switch and the caps

- **Refuse new rides now:** Environment → `RIDES_ENABLED` = `false` → Save. Render restarts the service,
  which also ends running rides. To stop everything at once instead: the service's **Settings → Suspend**.
- The app shows the server's refusals as plain sentences: "Demo limit reached for today", "Pillion's voice is
  paused right now", "Pillion is busy with other riders", "Too many tries". Crash detection and SOS never
  depend on the server.

## Provider dashboards: limits and alerts

I couldn't check every console from here; this is what to look for. The backend's daily caps are the hard
stop; these are the backstops.

- **Agora Console:** turn on a usage or balance alert under billing at a small amount, and look at the usage
  page after the first day of judging. ConvoAI agent minutes and RTC audio minutes are what cost money.
- **Sarvam:** prepaid credits. Keep only a small balance on the account during judging; that's the real cap.
- **Geoapify:** the free plan has a daily credit limit and stops there (no surprise bill). Nothing to set.
- **Jev (jevai.org community key):** rate-limited by nature; nothing to set.
- **After the hackathon:** rotate the Agora certificate, the Sarvam, Geoapify and Jev keys, and `APP_KEY`.
