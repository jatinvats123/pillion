import express from 'express';
import { config, configWarnings, mapsConfigured, validateConfig } from './config.js';
import { geocodeAddress, MapsError } from './maps/index.js';
import { activeAgentCount, startRide, stopAllRides, stopRide } from './agora.js';
import { jevEnabled } from './jev.js';
import { answerFromPhone, rideForToken } from './rides.js';
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
    maps: mapsConfigured() ? config.maps.provider : false,
    activeAgents: activeAgentCount(),
  });
});

// The tunnel is there for Agora's tool calls. Starting and stopping agents (which bills the Agora
// account) stays on this laptop and its local network (the phone over Wi-Fi when HOST=0.0.0.0):
// requests forwarded by Cloudflare carry cf-ray / cf-connecting-ip, the rest must come from a
// private address.
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
      console.log(`[say] ride=${ride.channel.slice(-6)} ${priority} in ${Date.now() - startedAt} ms: "${text.slice(0, 60)}"`);
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
    try {
      const { english, ms } = await toEnglish(text);
      console.log(`[translate] ride=${ride.channel.slice(-6)} ${ms ? `${ms} ms` : 'cached'}: "${text.slice(0, 40)}" → "${english.slice(0, 40)}"`);
      res.json({ english });
    } catch (error) {
      console.warn(`[translate] failed: ${error.message}`);
      res.status(424).json({ error: 'translate_failed' });
    }
  }),
);

// The rider set a scanned order: where is its drop? Asked before or during a ride, so it needs no
// ride token; local network only, like starting a ride (it spends the maps quota).
app.post('/order/geocode', localOnly, async (req, res) => {
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
    console.log(`[geocode] ${result.status} in ${Date.now() - startedAt} ms${near ? '' : ' (no rider position)'}${result.area ? ` · ${result.area}` : ''}`);
    res.json(result);
  } catch (error) {
    const code = error instanceof MapsError ? error.message.split(':')[0] : 'internal_error';
    console.warn(`[geocode] failed: ${error.message}`);
    res.status(error instanceof MapsError ? 424 : 500).json({ error: code });
  }
});

// Testing without speaking: sends text into the ride's LLM as if the rider had said it.
app.post(
  '/debug/think',
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

app.get(
  '/debug/history',
  withRide(async (ride, _req, res) => {
    try {
      res.json(await ride.session.getHistory());
    } catch (error) {
      res.status(502).json({ error: describe(error) });
    }
  }),
);

app.post('/agent/start', localOnly, async (_req, res) => {
  const startedAt = Date.now();
  try {
    const ride = await startRide();
    console.log(`[start] agent=${ride.agentId} channel=${ride.channel} uid=${ride.uid} in ${Date.now() - startedAt} ms (jev ${jevEnabled() ? 'on' : 'off'})`);
    res.json(ride);
  } catch (error) {
    console.error(`[start] failed: ${describe(error)}`);
    res.status(502).json({ error: 'Could not start the Pillion agent.', detail: describe(error) });
  }
});

app.post('/agent/stop', localOnly, async (req, res) => {
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
    res.status(502).json({ error: 'Could not stop the Pillion agent.', detail: describe(error) });
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
app.post('/debug/latency', localOnly, (req, res) => {
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

// Don't leave agents running (and billing) when the server is stopped with Ctrl+C.
async function shutdown() {
  console.log(`Stopping ${activeAgentCount()} active agent(s)...`);
  await stopAllRides();
  server.close(() => process.exit(0));
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
