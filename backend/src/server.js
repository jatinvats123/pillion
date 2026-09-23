import express from 'express';
import { config, validateConfig } from './config.js';
import { activeAgentCount, startRide, stopAllRides, stopRide } from './agora.js';

try {
  validateConfig();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}

const app = express();
app.use(express.json({ limit: '10kb' }));

app.get('/health', (_req, res) => {
  res.json({
    ok: true,
    voiceStack: config.voiceStack,
    llm: config.llm.provider === 'openai' ? `managed ${config.llm.openaiModel}` : config.gemini.model,
    activeAgents: activeAgentCount(),
  });
});

app.post('/agent/start', async (_req, res) => {
  const startedAt = Date.now();
  try {
    const ride = await startRide();
    console.log(`[start] agent=${ride.agentId} channel=${ride.channel} uid=${ride.uid} in ${Date.now() - startedAt} ms`);
    res.json(ride);
  } catch (error) {
    console.error(`[start] failed: ${describe(error)}`);
    res.status(502).json({ error: 'Could not start the Pillion agent.', detail: describe(error) });
  }
});

app.post('/agent/stop', async (req, res) => {
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

const secrets = [config.agora.appCertificate, config.gemini.apiKey, config.sarvam.apiKey].filter(Boolean);

// Debug app builds mirror their per-turn latency breakdown here (ASR / LLM / TTS from Agora metrics).
app.post('/debug/latency', (req, res) => {
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
