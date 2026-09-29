import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const envPath = fileURLToPath(new URL('../.env', import.meta.url));
if (existsSync(envPath)) process.loadEnvFile(envPath);

const env = (name, fallback = '') => (process.env[name] ?? fallback).trim();

export const config = {
  agora: {
    appId: env('AGORA_APP_ID'),
    appCertificate: env('AGORA_APP_CERTIFICATE'),
    agentUid: env('AGORA_AGENT_UID', '1000'),
    area: env('AGORA_AREA', 'AP').toUpperCase(),
  },
  llm: {
    // gemini = Gemini with our key · openai = Agora-managed OpenAI (no key needed)
    provider: env('LLM_PROVIDER', 'openai').toLowerCase(),
    maxTokens: Number(env('LLM_MAX_TOKENS', '300')),
    temperature: Number(env('LLM_TEMPERATURE', '0.6')),
    openaiModel: env('OPENAI_MODEL', 'gpt-4o-mini'),
  },
  gemini: {
    apiKey: env('GEMINI_API_KEY'),
    model: env('GEMINI_MODEL', 'gemini-3.5-flash-lite'),
  },
  voiceStack: env('VOICE_STACK', 'sarvam').toLowerCase(),
  sarvam: {
    apiKey: env('SARVAM_API_KEY'),
    sttLanguage: env('SARVAM_STT_LANGUAGE', 'unknown'),
    ttsLanguage: env('SARVAM_TTS_LANGUAGE', 'hi-IN'),
    ttsSpeaker: env('SARVAM_TTS_SPEAKER', 'priya'),
    ttsPace: Number(env('SARVAM_TTS_PACE', '1.08')),
  },
  managed: {
    deepgramLanguage: env('DEEPGRAM_LANGUAGE', 'multi'),
    minimaxVoiceId: env('MINIMAX_VOICE_ID', 'English_captivating_female1'),
  },
  turnDetectionLanguage: env('TURN_DETECTION_LANGUAGE', 'hi-IN'),
  // Public HTTPS address of this backend (cloudflared), where Agora's cloud calls Pillion's tools.
  publicBaseUrl: env('PUBLIC_BASE_URL').replace(/\/+$/, ''),
  // Starting agents through the tunnel is refused unless this is on (phone on mobile data).
  allowPublicRideStart: env('ALLOW_PUBLIC_RIDE_START', 'false').toLowerCase() === 'true',
  jev: {
    enabled: env('JEV_ENABLED', 'true').toLowerCase() === 'true',
    url: env('JEV_URL', 'https://www.jevai.org/api/v1/decisions'),
    apiKey: env('JEV_API_KEY'),
    // Empty = let the endpoint choose. TypeSafe's own API requires one (e.g. jev-latest).
    model: env('JEV_MODEL'),
    timeoutMs: Number(env('JEV_TIMEOUT_MS', '1500')),
    minConfidence: Number(env('JEV_MIN_CONFIDENCE', '0.5')),
  },
  maps: {
    // geoapify = free tier, no live traffic · google = Routes + Places (New), live traffic
    provider: env('MAPS_PROVIDER', 'geoapify').toLowerCase(),
    geoapifyApiKey: env('GEOAPIFY_API_KEY'),
    googleApiKey: env('GOOGLE_MAPS_API_KEY'),
  },
  // Live Guardian: SOS SMS links where family hears, talks to and locates the rider. Off = no link,
  // no page, no handoff.
  guardian: {
    enabled: env('GUARDIAN_ENABLED', 'false').toLowerCase() === 'true',
  },
  // Public deployment (see limits.js). APP_KEY set = public mode: starting rides and geocoding need
  // the release app's key, from anywhere. Empty = local mode: the laptop / Wi-Fi / tunnel rules.
  appKey: env('APP_KEY'),
  limits: {
    ridesEnabled: env('RIDES_ENABLED', 'true').toLowerCase() !== 'false', // the kill switch
    maxRideMinutes: Number(env('MAX_RIDE_MINUTES', '10')), // 0 = no limit
    maxRidesPerDay: Number(env('MAX_RIDES_PER_DAY', '40')),
    maxAgentMinutesPerDay: Number(env('MAX_AGENT_MINUTES_PER_DAY', '300')),
    maxConcurrentRides: Number(env('MAX_CONCURRENT_RIDES', '5')),
  },
  // /debug/* (think, history, latency). Default: on in local mode, off in public mode.
  debugRoutes: env('DEBUG_ROUTES', env('APP_KEY') ? 'false' : 'true').toLowerCase() === 'true',
  // Filler words play when the LLM hasn't started answering after this long (in practice: tool calls).
  fillerWaitMs: Number(env('FILLER_WAIT_MS', '1500')),
  port: Number(env('PORT', '3000')),
  host: env('HOST', '127.0.0.1'),
  tokenExpirySeconds: Number(env('TOKEN_EXPIRY_SECONDS', '14400')),
};

export function validateConfig() {
  const problems = [];
  const { agora, gemini, sarvam, voiceStack } = config;

  if (agora.appId.length !== 32) problems.push('AGORA_APP_ID must be the 32-character App ID');
  if (agora.appCertificate.length !== 32) problems.push('AGORA_APP_CERTIFICATE must be the 32-character App Certificate');
  if (!/^\d+$/.test(agora.agentUid)) problems.push('AGORA_AGENT_UID must be numeric');
  if (!['US', 'EU', 'AP'].includes(agora.area)) problems.push('AGORA_AREA must be US, EU or AP');
  if (!['gemini', 'openai'].includes(config.llm.provider)) problems.push('LLM_PROVIDER must be "gemini" or "openai"');
  if (config.llm.provider === 'gemini' && !gemini.apiKey) problems.push('GEMINI_API_KEY is missing (or set LLM_PROVIDER=openai)');
  if (!['sarvam', 'managed'].includes(voiceStack)) problems.push('VOICE_STACK must be "sarvam" or "managed"');
  if (voiceStack === 'sarvam' && !sarvam.apiKey) problems.push('SARVAM_API_KEY is missing (or set VOICE_STACK=managed)');
  if (!(config.tokenExpirySeconds > 0 && config.tokenExpirySeconds <= 86400)) {
    problems.push('TOKEN_EXPIRY_SECONDS must be between 1 and 86400');
  }
  if (config.publicBaseUrl && !config.publicBaseUrl.startsWith('https://')) {
    problems.push('PUBLIC_BASE_URL must be an https:// URL (Agora only calls HTTPS tools)');
  }
  if (!(config.fillerWaitMs >= 100 && config.fillerWaitMs <= 10000)) problems.push('FILLER_WAIT_MS must be 100–10000');
  if (!['geoapify', 'google'].includes(config.maps.provider)) problems.push('MAPS_PROVIDER must be "geoapify" or "google"');
  if (config.appKey && config.appKey.length < 24) problems.push('APP_KEY must be at least 24 characters');
  for (const [name, value] of Object.entries({
    MAX_RIDE_MINUTES: config.limits.maxRideMinutes,
    MAX_RIDES_PER_DAY: config.limits.maxRidesPerDay,
    MAX_AGENT_MINUTES_PER_DAY: config.limits.maxAgentMinutesPerDay,
    MAX_CONCURRENT_RIDES: config.limits.maxConcurrentRides,
  })) {
    if (!(Number.isInteger(value) && value >= 0)) problems.push(`${name} must be a whole number ≥ 0 (0 = no limit)`);
  }

  if (problems.length) {
    throw new Error(`Invalid backend/.env:\n  - ${problems.join('\n  - ')}`);
  }
}

export const mapsConfigured = () =>
  Boolean(config.maps.provider === 'google' ? config.maps.googleApiKey : config.maps.geoapifyApiKey);

/** Optional pieces that are missing: the server runs, but these features report errors. */
export function configWarnings() {
  const warnings = [];
  if (!config.publicBaseUrl) warnings.push('PUBLIC_BASE_URL is not set: rides can\'t start until cloudflared runs (see .env.example)');
  const mapsKey = config.maps.provider === 'google' ? 'GOOGLE_MAPS_API_KEY' : 'GEOAPIFY_API_KEY';
  if (!mapsConfigured()) warnings.push(`${mapsKey} is not set (MAPS_PROVIDER=${config.maps.provider}): ETA and nearby places will fail`);
  if (config.guardian.enabled && !config.publicBaseUrl) warnings.push('GUARDIAN_ENABLED needs PUBLIC_BASE_URL: SOS SMS go without a live link');
  if (config.jev.enabled && !config.jev.apiKey) warnings.push('JEV_API_KEY is not set: Jev routing is off');
  if (!config.limits.ridesEnabled) warnings.push('RIDES_ENABLED=false: new rides are refused');
  if (config.appKey && config.debugRoutes) warnings.push('DEBUG_ROUTES=true in public mode: /debug/* is reachable with a ride token');
  return warnings;
}
