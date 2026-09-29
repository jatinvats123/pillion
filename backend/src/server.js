import { readFileSync } from 'node:fs';
import express from 'express';
import { config, configWarnings, mapsConfigured, validateConfig } from './config.js';
import { geocodeAddress, MapsError } from './maps/index.js';
import { activeAgentCount, familyPresence, startRide, stopAllRides, stopRide } from './agora.js';
import {
  guardianEnabled,
  joinCredentials,
  linkFor,
  registerLink,
  rideHasLinks,
  riderIsOk,
  shortId,
  statusOf,
  touchLinks,
  updateLocation,
} from './guardian.js';
import { jevEnabled } from './jev.js';
import {
  clientIp,
  geocodeAllowed,
  hasAppKey,
  publicMode,
  rateLimiter,
  refuseRideStart,
  rideCallAllowed,
  translateAllowed,
} from './limits.js';
import { answerFromPhone, liveRideCount, newestRide, rideForToken } from './rides.js';
import { onRiderTurn, runTool } from './tools.js';
import { toEnglish } from './translate.js';

try {
  validateConfig();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
for (const warning of configWarnings()) console.warn(`[config] ${warning}`);

const app = express();
app.use(express.json({ limit: '10kb' }));

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    voiceStack: config.voiceStack,
    llm: config.llm.provider === 'openai' ? `managed ${config.llm.openaiModel}` : config.gemini.model,
    tools: Boolean(config.publicBaseUrl),
    // Which tunnel this process was started with (.env changes need a restart); scripts/start.ps1 compares it.
    tunnel: config.publicBaseUrl ? new URL(config.publicBaseUrl).host : null,
    jev: jevEnabled(),
    guardian: guardianEnabled(),
    maps: mapsConfigured() ? config.maps.provider : false,
    mode: publicMode() ? 'public' : 'local',
    rides: config.limits.ridesEnabled,
    activeAgents: activeAgentCount(),
  });
});

// Starting and stopping agents bills the Agora account.
// Public mode (APP_KEY set, e.g. on Render): only with the release app's key, from anywhere. The
// socket address means nothing there (every request comes from the host's own proxy).
// Local mode: the tunnel is there for Agora's tool calls, so these stay on this laptop and its local
// network (the phone over Wi-Fi when HOST=0.0.0.0): requests forwarded by Cloudflare carry cf-ray /
// cf-connecting-ip, the rest must come from a private address.
function appOnly(req, res, next) {
  if (publicMode()) return hasAppKey(req) ? next() : res.status(401).json({ error: 'app_key_required' });
  return localOnly(req, res, next);
}

function localOnly(req, res, next) {
  const viaTunnel = Boolean(req.get('cf-ray') || req.get('cf-connecting-ip'));
  if (viaTunnel && !config.allowPublicRideStart) {
    return res.status(403).json({ error: 'Not available through the public tunnel.' });
  }
  if (!viaTunnel && !isPrivateAddress(req.socket.remoteAddress)) {
    return res.status(403).json({ error: 'Only available on the local network.' });
  }
  return next();
}

// Loopback, RFC 1918 and link-local addresses (IPv4, or IPv4-mapped IPv6 as Node reports them).
function isPrivateAddress(address = '') {
  const ip = address.replace(/^::ffff:/, '');
  return ip === '::1' || /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)/.test(ip);
}

// Every ride-scoped call (Agora's tool calls and the rider app's own) carries the ride's secret.
function withRide(handler) {
  return async (req, res) => {
    const token = req.get('authorization')?.replace(/^Bearer\s+/i, '');
    const ride = rideForToken(token);
    if (!ride) return res.status(401).json({ error: 'unknown_or_ended_ride' });
    if (!rideCallAllowed(ride)) return res.status(429).json({ error: 'slow_down' });
    return handler(ride, req, res);
  };
}

// Agora ConvoAI custom tools: the LLM decided to call `name` with these arguments.
app.post(
  '/tools/:name',
  withRide(async (ride, req, res) => {
    const { tool_call_id: _id, ...args } = req.body ?? {};
    const { status, body } = await runTool(ride, req.params.name, args);
    res.status(status).json(body);
  }),
);

// The app posts each final rider transcript: Jev reads it while the LLM does.
app.post(
  '/ride/turn',
  withRide((ride, req, res) => {
    const text = String(req.body?.text ?? '').trim().slice(0, 500);
    if (text) onRiderTurn(ride, { turnId: Number(req.body?.turnId ?? -1), text });
    res.status(202).json({ ok: true });
  }),
);

// The phone's answer to a request relayed over RTM (GPS fix, earnings, SMS sent, call placed…).
app.post(
  '/ride/device-result',
  withRide((ride, req, res) => {
    const known = answerFromPhone(ride, req.body ?? {});
    res.status(known ? 200 : 410).json({ ok: known });
  }),
);

// Safety alerts from the phone ("Aap theek ho?", "SOS sent to 2 contacts"): the agent speaks the
// text now through Agora's speak API. INTERRUPT cuts off whatever it was saying; not interruptable,
// so road noise can't cut the alert short. The phone speaks it itself if this fails or is slow.
app.post(
  '/ride/say',
  withRide(async (ride, req, res) => {
    const text = String(req.body?.text ?? '').trim();
    // Agora's limit is 512 bytes (Devanagari is 3 bytes a character).
    if (!text || Buffer.byteLength(text) > 500 || !ride.session) {
      return res.status(400).json({ error: 'text (max 500 bytes) and a started ride are required' });
    }
    const priority = req.body?.interrupt === false ? 'APPEND' : 'INTERRUPT';
    const startedAt = Date.now();
    try {
      await ride.session.say(text, { priority, interruptable: false });
      console.log(`[say] ride=${ride.channel.slice(-6)} ${priority} in ${Date.now() - startedAt} ms (${text.length} chars)`);
      res.json({ ok: true });
    } catch (error) {
      console.warn(`[say] failed: ${describe(error)}`);
      res.status(424).json({ error: 'say_failed' });
    }
  }),
);

// English subtitle for a Hindi transcript line (Sarvam). The app shows the line without one if
// this fails; 424 keeps a failure's body JSON even through the Cloudflare tunnel.
app.post(
  '/ride/translate',
  withRide(async (ride, req, res) => {
    const text = String(req.body?.text ?? '').trim();
    if (!text) return res.status(400).json({ error: 'text is required' });
    if (!translateAllowed(ride)) return res.status(429).json({ error: 'slow_down' });
    try {
      const { english, ms } = await toEnglish(text);
      console.log(`[translate] ride=${ride.channel.slice(-6)} ${ms ? `${ms} ms` : 'cached'} (${text.length} chars)`);
      res.json({ english });
    } catch (error) {
      console.warn(`[translate] failed: ${error.message}`);
      res.status(424).json({ error: 'translate_failed' });
    }
  }),
);

// The rider set a scanned order: where is its drop? Asked before or during a ride, so it needs no
// ride token; the same access as starting a ride (it spends the maps quota), rate-limited per IP.
app.post('/order/geocode', appOnly, async (req, res) => {
  if (!geocodeAllowed(req)) return res.status(429).json({ error: 'slow_down' });
  const address = String(req.body?.address ?? '').trim().slice(0, 300);
  const area = String(req.body?.area ?? '').trim().slice(0, 80) || null;
  const lat = Number(req.body?.near?.lat);
  const lng = Number(req.body?.near?.lng);
  const near = Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null;
  if (!address) return res.status(400).json({ error: 'address is required' });
  if (!mapsConfigured()) return res.status(503).json({ error: 'maps_not_configured' });
  const startedAt = Date.now();
  try {
    const result = await geocodeAddress(address, { area, near });
    console.log(`[geocode] ${result.status} in ${Date.now() - startedAt} ms${near ? '' : ' (no rider position)'}`);
    res.json(result);
  } catch (error) {
    const code = error instanceof MapsError ? error.message.split(':')[0] : 'internal_error';
    console.warn(`[geocode] failed: ${error.message}`);
    res.status(error instanceof MapsError ? 424 : 500).json({ error: code });
  }
});

// --- Live Guardian (GUARDIAN_ENABLED): see guardian.js. Nothing here logs names, places or numbers.

function guardianOnly(_req, res, next) {
  return guardianEnabled() ? next() : res.status(404).json({ error: 'guardian_off' });
}

// The phone put <base>/g/<token> in an SOS SMS (it made the token, so the SMS didn't wait for us).
app.post(
  '/ride/guardian/link',
  guardianOnly,
  withRide((ride, req, res) => {
    const error = registerLink(ride, { token: req.body?.token, name: req.body?.name });
    res.status(error ? 400 : 200).json(error ? { error } : { ok: true });
  }),
);

// Every ~5 s while a link is live: the rider's fix (or no fix: "still here"). `live: false` = stop.
app.post(
  '/ride/guardian/location',
  guardianOnly,
  withRide((ride, req, res) => {
    const lat = Number(req.body?.lat);
    const lng = Number(req.body?.lng);
    if (Number.isFinite(lat) && Number.isFinite(lng)) {
      updateLocation(ride, { lat, lng, accuracyM: Number(req.body?.accuracyM), ageMs: Number(req.body?.ageMs) });
    } else {
      touchLinks(ride);
    }
    res.json({ live: rideHasLinks(ride) });
  }),
);

// The rider tapped I'M OK NOW: pages say so, location sharing stops.
app.post(
  '/ride/guardian/ok',
  guardianOnly,
  withRide((ride, _req, res) => {
    riderIsOk(ride);
    res.json({ ok: true });
  }),
);

// The phone saw family join (any) or leave (all) the RTC channel: the agent steps aside / comes back.
app.post(
  '/ride/guardian/presence',
  guardianOnly,
  withRide((ride, req, res) => {
    const present = req.body?.present === true;
    console.log(`[guardian] ride=${ride.channel.slice(-6)} family ${present ? 'joined' : 'left'}`);
    familyPresence(ride, present);
    res.json({ ok: true });
  }),
);

// Public: whoever has the link. Unknown and expired tokens look the same.
const pageLimit = rateLimiter({ max: 60, windowMs: 60_000 });
const pageHtml = readFileSync(new URL('../public/guardian.html', import.meta.url), 'utf8');
const expiredHtml = readFileSync(new URL('../public/expired.html', import.meta.url), 'utf8');

function publicPage(req, res, next) {
  res.set({
    'Cache-Control': 'no-store',
    // Map tiles and the CDN see our origin at most, never the token in the path.
    'Referrer-Policy': 'strict-origin',
    'X-Robots-Tag': 'noindex, nofollow',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
  });
  if (!pageLimit(clientIp(req))) return res.status(429).json({ error: 'slow_down' });
  return next();
}

const liveLink = (req) => (guardianEnabled() ? linkFor(req.params.token) : undefined);

app.get('/g/:token', publicPage, (req, res) => {
  res.status(liveLink(req) ? 200 : 410).type('html').send(liveLink(req) ? pageHtml : expiredHtml);
});

app.get('/g/:token/status', publicPage, (req, res) => {
  const link = liveLink(req);
  if (!link) return res.status(410).json({ error: 'expired' });
  return res.json(statusOf(link, Boolean(rideForToken(link.rideToken))));
});

app.post('/g/:token/join', publicPage, (req, res) => {
  const link = liveLink(req);
  if (!link) return res.status(410).json({ error: 'expired' });
  const credentials = joinCredentials(link);
  if (!credentials) return res.status(429).json({ error: 'slow_down' });
  console.log(`[guardian] link ${shortId(link.token)}: family joining`);
  return res.json(credentials);
});

// /debug/*: local mode by default; off in public mode unless DEBUG_ROUTES=true.
function debugOnly(_req, res, next) {
  return config.debugRoutes ? next() : res.status(404).json({ error: 'not_found' });
}

// Testing without speaking: sends text into the ride's LLM as if the rider had said it.
app.post(
  '/debug/think',
  debugOnly,
  withRide(async (ride, req, res) => {
    const text = String(req.body?.text ?? '').trim();
    if (!text || !ride.session) return res.status(400).json({ error: 'text and a started ride are required' });
    try {
      await ride.session.think(text);
      res.json({ ok: true });
    } catch (error) {
      res.status(502).json({ error: describe(error) });
    }
  }),
);

// Screenshots and demo recordings from the laptop: the same, into the newest ride (whose token only
// the phone has). Debug routes on and this machine / local network only, never through Cloudflare.
app.post('/debug/think-latest', debugOnly, localOnly, async (req, res) => {
  const ride = newestRide();
  const text = String(req.body?.text ?? '').trim();
  if (!text || !ride?.session) return res.status(400).json({ error: 'text and a started ride are required' });
  try {
    await ride.session.think(text);
    res.json({ ok: true, ride: ride.channel.slice(-6) });
  } catch (error) {
    res.status(502).json({ error: describe(error) });
  }
});

app.get(
  '/debug/history',
  debugOnly,
  withRide(async (ride, _req, res) => {
    try {
      res.json(await ride.session.getHistory());
    } catch (error) {
      res.status(502).json({ error: describe(error) });
    }
  }),
);

app.post('/agent/start', appOnly, async (req, res) => {
  const refused = refuseRideStart(req, liveRideCount());
  if (refused) {
    console.log(`[start] refused: ${refused.error}`);
    return res.status(refused.status).json({ error: refused.error });
  }
  const startedAt = Date.now();
  try {
    const ride = await startRide();
    console.log(`[start] agent=${ride.agentId} channel=${ride.channel} uid=${ride.uid} in ${Date.now() - startedAt} ms (jev ${jevEnabled() ? 'on' : 'off'})`);
    res.json(ride);
  } catch (error) {
    console.error(`[start] failed: ${describe(error)}`);
    // 424, not 502: Cloudflare swaps an origin's 502 for its own HTML page. No Agora detail in public mode.
    res.status(424).json({ error: 'Could not start the Pillion agent.', ...(publicMode() ? {} : { detail: describe(error) }) });
  }
});

app.post('/agent/stop', appOnly, async (req, res) => {
  const agentId = req.body?.agentId;
  if (typeof agentId !== 'string' || !agentId.trim()) {
    return res.status(400).json({ error: 'agentId is required.' });
  }
  try {
    await stopRide(agentId);
    console.log(`[stop] agent=${agentId}`);
    res.json({ ok: true });
  } catch (error) {
    console.error(`[stop] agent=${agentId} failed: ${describe(error)}`);
    // 424, not 502: Cloudflare swaps an origin's 502 for its own HTML page. No Agora detail in public mode.
    res.status(424).json({ error: 'Could not stop the Pillion agent.', ...(publicMode() ? {} : { detail: describe(error) }) });
  }
});

const secrets = [
  config.agora.appCertificate,
  config.gemini.apiKey,
  config.sarvam.apiKey,
  config.jev.apiKey,
  config.maps.geoapifyApiKey,
  config.maps.googleApiKey,
].filter(Boolean);

// Debug app builds mirror their per-turn latency breakdown here (ASR / LLM / TTS from Agora metrics).
app.post('/debug/latency', debugOnly, appOnly, (req, res) => {
  const line = String(req.body?.line ?? '').slice(0, 300);
  if (line) console.log(`[latency] ${line}`);
  res.json({ ok: true });
});

// Agora SDK errors carry the upstream status and body; keep it short and never echo a secret.
function describe(error) {
  const status = error?.statusCode ? `HTTP ${error.statusCode} ` : '';
  const body = error?.body ? JSON.stringify(error.body) : error?.message ?? String(error);
  let text = `${status}${body}`;
  for (const secret of secrets) text = text.replaceAll(secret, '***');
  return text.slice(0, 500);
}

const server = app.listen(config.port, config.host, () => {
  console.log(`Pillion backend on http://${config.host}:${config.port} (voice: ${config.voiceStack}, llm: ${config.llm.provider === 'openai' ? config.llm.openaiModel : config.gemini.model})`);
});

// Don't leave agents running (and billing) when the server stops: Ctrl+C locally, SIGTERM from the
// host on a deploy or restart (Render waits 30 s). Open keep-alive connections mustn't hold it up.
let stopping = false;
async function shutdown(signal) {
  if (stopping) return;
  stopping = true;
  console.log(`${signal}: stopping ${activeAgentCount()} active agent(s)...`);
  setTimeout(() => process.exit(0), 10_000).unref();
  await stopAllRides();
  server.close(() => process.exit(0));
  server.closeIdleConnections();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
