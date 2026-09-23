import { randomInt } from 'node:crypto';
import {
  Agent,
  AgoraClient,
  Area,
  DeepgramSTT,
  Gemini,
  MiniMaxTTS,
  OpenAI,
  SarvamSTT,
  SarvamTTS,
  generateConvoAIToken,
} from 'agora-agents';
import { config } from './config.js';
import { FAILURE_MESSAGE, GREETING, SYSTEM_PROMPT } from './prompt.js';

// Created lazily so config validation can report problems before the SDK does.
let client;
const agoraClient = () =>
  (client ??= new AgoraClient({
    area: Area[config.agora.area],
    appId: config.agora.appId,
    appCertificate: config.agora.appCertificate,
  }));

// Agents started by this process, so they can be stopped if the server shuts down.
const activeAgents = new Set();

export const activeAgentCount = () => activeAgents.size;

function buildAgent() {
  const agent = new Agent({
    client: agoraClient(),
    turnDetection: {
      language: config.turnDetectionLanguage,
      config: {
        speech_threshold: 0.5,
        start_of_speech: {
          mode: 'vad',
          // interrupt 160 ms (not lower): short road-noise bursts shouldn't barge in on Pillion.
          vad_config: { interrupt_duration_ms: 160, prefix_padding_ms: 240 },
        },
        end_of_speech: {
          mode: 'vad',
          // 400 ms of silence ends the rider's turn (was 640). Lower cuts riders off mid-sentence.
          vad_config: { silence_duration_ms: 400 },
        },
      },
    },
    // Barge-in: the agent stops talking as soon as the rider starts speaking.
    interruption: { enable: true, mode: 'start_of_speech' },
    // RTM carries transcripts and agent state (listening/thinking/speaking) to the app.
    advancedFeatures: { enable_rtm: true },
    parameters: {
      data_channel: 'rtm',
      enable_error_message: true,
      // Per-turn latency of each stage (ASR / LLM / TTS) as `message.metrics` over RTM.
      enable_metrics: true,
      // Tuned for agent conversations and network resilience (riders are on patchy mobile data).
      audio_scenario: 'aiserver',
    },
  }).withLlm(buildLlm());

  if (config.voiceStack === 'managed') {
    return agent
      .withStt(new DeepgramSTT({ model: 'nova-3', language: config.managed.deepgramLanguage }))
      .withTts(new MiniMaxTTS({ model: 'speech_2_8_turbo', voiceId: config.managed.minimaxVoiceId }));
  }

  return agent
    .withStt(new SarvamSTT({ apiKey: config.sarvam.apiKey, language: config.sarvam.sttLanguage }))
    .withTts(
      new SarvamTTS({
        key: config.sarvam.apiKey,
        speaker: config.sarvam.ttsSpeaker,
        targetLanguageCode: config.sarvam.ttsLanguage,
      }),
    );
}

function buildLlm() {
  const common = {
    systemMessages: [{ role: 'system', content: SYSTEM_PROMPT }],
    greetingMessage: GREETING,
    failureMessage: FAILURE_MESSAGE,
    maxHistory: 12,
  };
  const { provider, maxTokens, temperature, openaiModel } = config.llm;

  if (provider === 'openai') {
    // No apiKey/url: the SDK sends this as an Agora-managed preset (e.g. openai_gpt_4o_mini).
    return new OpenAI({ ...common, model: openaiModel, maxTokens, temperature });
  }

  return new Gemini({
    ...common,
    apiKey: config.gemini.apiKey,
    model: config.gemini.model,
    // Agora copies `params` into Gemini's request body as-is, so settings must sit under
    // generationConfig (the SDK's `temperature` option lands top-level → Gemini HTTP 400).
    // gemini-3.5-flash-lite already defaults to the lowest thinking level ("minimal").
    params: { generationConfig: { temperature, maxOutputTokens: maxTokens } },
  });
}

/**
 * Creates a fresh channel, a token for the rider, and starts the Pillion agent in it.
 * The agent only listens to this rider's uid and leaves 30 s after the rider disconnects.
 */
export async function startRide() {
  const channel = `pillion-${Date.now()}-${randomInt(100_000, 1_000_000)}`;
  const uid = randomInt(100_000, 900_000);

  // One AccessToken2 with both RTC (audio) and RTM (transcript/state) privileges for the rider.
  const token = generateConvoAIToken({
    appId: config.agora.appId,
    appCertificate: config.agora.appCertificate,
    channelName: channel,
    uid,
    tokenExpire: config.tokenExpirySeconds,
  });

  const session = buildAgent().createSession({
    name: channel,
    channel,
    agentUid: config.agora.agentUid,
    remoteUids: [String(uid)],
    idleTimeout: 30,
    expiresIn: config.tokenExpirySeconds,
  });

  const agentId = await session.start();
  activeAgents.add(agentId);

  return {
    appId: config.agora.appId,
    channel,
    token,
    uid,
    agentUid: Number(config.agora.agentUid),
    agentId,
  };
}

/** Stops an agent by id. Already-stopped agents count as success. */
export async function stopRide(agentId) {
  await agoraClient().stopAgent(agentId);
  activeAgents.delete(agentId);
}

export async function stopAllRides() {
  await Promise.allSettled([...activeAgents].map((id) => stopRide(id)));
}
