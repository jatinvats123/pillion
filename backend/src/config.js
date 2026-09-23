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
  },
  managed: {
    deepgramLanguage: env('DEEPGRAM_LANGUAGE', 'multi'),
    minimaxVoiceId: env('MINIMAX_VOICE_ID', 'English_captivating_female1'),
  },
  turnDetectionLanguage: env('TURN_DETECTION_LANGUAGE', 'hi-IN'),
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

  if (problems.length) {
    throw new Error(`Invalid backend/.env:\n  - ${problems.join('\n  - ')}`);
  }
}
