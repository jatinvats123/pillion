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
import {
  CALL_RULES,
  FAILURE_MESSAGE,
  FAMILY_JOINED,
  FILLER_PHRASES,
  FILLER_PROMPT,
  GREETING,
  RESUMED_CONTEXT,
  RIDE_TIME_UP,
  SYSTEM_PROMPT,
  WELCOME_BACK,
} from './prompt.js';
import { rideHasLinks } from './guardian.js';
import { agentStarted, agentStopped, rideStarted } from './limits.js';
import { createRide, endRide, rideForAgent, rideForToken } from './rides.js';
import { sendToRider } from './rtm.js';
import { toolDefinitions } from './tools.js';

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

function buildAgent(ride, resumed) {
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
    // Agora has no tool-only filler trigger, only "LLM silent for N ms". Chat replies start in
    // 0.5–1.5 s; tool turns (LLM → tool → LLM) take 2.5 s+, so 1.5 s ≈ fillers on tool turns only.
    fillerWords: {
      enable: true,
      trigger: { mode: 'fixed_time', fixed_time_config: { response_wait_ms: config.fillerWaitMs } },
      content: {
        // Agora-hosted generation keeps the filler in the rider's language; static is the fallback.
        mode: 'generated',
        generated_config: { prompt: FILLER_PROMPT, fallback_strategy: 'static' },
        static_config: { phrases: FILLER_PHRASES, selection_rule: 'shuffle' },
      },
    },
  })
    .withLlm(buildLlm(ride, resumed))
    .withTools(true);

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
        pace: config.sarvam.ttsPace,
      }),
    );
}

function buildLlm(ride, resumed) {
  const systemMessages = [{ role: 'system', content: SYSTEM_PROMPT }];
  if (config.callAnswer.enabled) systemMessages.push({ role: 'system', content: CALL_RULES });
  if (resumed) systemMessages.push({ role: 'system', content: RESUMED_CONTEXT });
  const common = {
    systemMessages,
    greetingMessage: resumed ? WELCOME_BACK : GREETING,
    failureMessage: FAILURE_MESSAGE,
    maxHistory: 12,
    tools: toolDefinitions(),
    // Agora sends this as the Authorization header of every tool call; it identifies the ride.
    templateVariables: { tool_auth: `Bearer ${ride.token}` },
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
  if (!config.publicBaseUrl) {
    throw new Error('PUBLIC_BASE_URL is not set: start cloudflared and put its https URL in backend/.env (tools need it).');
  }
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

  const ride = createRide({ channel, uid });
  let agentId;
  try {
    agentId = await startAgent(ride, false);
  } catch (error) {
    endRide(ride);
    throw error;
  }
  rideStarted();
  startRideTimer(ride);

  return {
    appId: config.agora.appId,
    channel,
    token,
    uid,
    agentUid: Number(config.agora.agentUid),
    agentId,
    // Authenticates the app's own calls (transcripts, device results) for this ride.
    rideToken: ride.token,
    // Live Guardian: base of the SOS link (<base>/g/<token>); null = feature off, no link.
    guardianBaseUrl: config.guardian.enabled ? config.publicBaseUrl : null,
    // The voice stops after this long (0 = no limit); safety keeps running on the phone.
    maxRideMinutes: config.limits.maxRideMinutes,
    // Answer calls by voice: the phone may keep Pillion on while it rings and report the call.
    callAnswer: config.callAnswer.enabled,
  };
}

/** Starts an agent in the ride's channel that only listens to the rider. Returns its id. */
async function startAgent(ride, resumed) {
  const session = buildAgent(ride, resumed).createSession({
    name: `${ride.channel}${resumed ? `-r${Date.now() % 100_000}` : ''}`,
    channel: ride.channel,
    agentUid: config.agora.agentUid,
    remoteUids: [String(ride.uid)],
    idleTimeout: 30,
    expiresIn: Math.max(60, config.tokenExpirySeconds - Math.floor((Date.now() - ride.startedAt) / 1000)),
  });
  const agentId = await session.start();
  ride.agentId = agentId;
  ride.agentIds.add(agentId);
  ride.session = session;
  activeAgents.add(agentId);
  agentStarted(agentId);
  return agentId;
}

/** Stops one agent and counts its minutes towards the day's cap. */
async function stopAgent(agentId) {
  try {
    await agoraClient().stopAgent(agentId);
  } finally {
    activeAgents.delete(agentId);
    agentStopped(agentId);
  }
}

const TIME_UP_LINE_MS = 7000; // the time-up line: first audio ~1–1.5 s + ~5 s of speech

/**
 * MAX_RIDE_MINUTES after the start, the voice ends: the agent says so and is stopped; a Live
 * Guardian handoff can't bring it back. Never while an SOS link is live (checked every minute
 * after the limit): the phone keeps the voice channel open for family. The app gets a
 * `pillion.notice`; the phone keeps crash detection and SOS.
 */
function startRideTimer(ride) {
  const minutes = config.limits.maxRideMinutes;
  if (!minutes) return;
  const check = () => {
    // An SOS link is live: family may open it any moment, so the voice stays until it isn't.
    if (rideHasLinks(ride)) {
      ride.limitTimer = setTimeout(check, 60_000);
      ride.limitTimer.unref();
      return;
    }
    ride.timeUp = true;
    ride.handoff = ride.handoff
      .then(() => endVoiceForTimeLimit(ride, minutes))
      .catch((error) => console.warn(`[limit] ride=${ride.channel.slice(-6)} stop failed: ${describeError(error)}`));
  };
  ride.limitTimer = setTimeout(check, minutes * 60_000);
  ride.limitTimer.unref();
}

async function endVoiceForTimeLimit(ride, minutes) {
  if (!rideForToken(ride.token)) return;
  sendToRider(ride.uid, { object: 'pillion.notice', code: 'ride_time_limit', minutes }).catch((error) =>
    console.warn(`[limit] notice not sent: ${error.message}`),
  );
  if (!ride.session) return; // family on the line: the agent is already off and stays off
  await ride.session.say(RIDE_TIME_UP(minutes), { priority: 'INTERRUPT', interruptable: false }).catch(() => {});
  await new Promise((resolve) => setTimeout(resolve, TIME_UP_LINE_MS));
  const agentId = ride.agentId;
  if (!agentId || !rideForToken(ride.token)) return;
  ride.session = null;
  ride.agentId = null;
  await stopAgent(agentId);
  console.log(`[limit] ride=${ride.channel.slice(-6)} reached ${minutes} min: agent stopped`);
}

const FAMILY_LINE_MS = 4000; // the family line: Sarvam's first audio ~1–1.5 s + ~2 s of speech

/**
 * Live Guardian handoff, reported by the rider's phone (it sees family uids join and leave the
 * RTC channel). ConvoAI can't pause an agent (update only takes token and llm), so the agent says
 * one line and is stopped while family is on; a fresh one starts in the same channel when the
 * last of them leaves. Steps run one after another; each checks the latest presence first.
 */
export function familyPresence(ride, present) {
  if (ride.familyPresent === present) return;
  ride.familyPresent = present;
  ride.handoff = ride.handoff
    .then(() => (present ? pauseForFamily(ride) : resumeAfterFamily(ride)))
    .catch((error) => console.warn(`[guardian] ride=${ride.channel.slice(-6)} handoff failed: ${describeError(error)}`));
}

async function pauseForFamily(ride) {
  if (!ride.familyPresent || !ride.session) return;
  try {
    await ride.session.say(FAMILY_JOINED, { priority: 'INTERRUPT', interruptable: false });
  } catch (error) {
    console.warn(`[guardian] family line not said: ${describeError(error)}`);
  }
  await new Promise((resolve) => setTimeout(resolve, FAMILY_LINE_MS));
  if (!ride.familyPresent || !ride.session) return; // they left while it spoke: keep the agent
  const agentId = ride.agentId;
  ride.session = null;
  ride.agentId = null;
  await stopAgent(agentId);
  console.log(`[guardian] ride=${ride.channel.slice(-6)} family on the line: agent stopped`);
}

async function resumeAfterFamily(ride) {
  if (ride.familyPresent || ride.session || ride.timeUp || !rideForToken(ride.token)) return;
  const startedAt = Date.now();
  const agentId = await startAgent(ride, true);
  if (!rideForToken(ride.token)) {
    // The ride ended while the agent started.
    await stopAgent(agentId).catch(() => {});
    return;
  }
  console.log(`[guardian] ride=${ride.channel.slice(-6)} family left: agent back in ${Date.now() - startedAt} ms`);
}

function describeError(error) {
  return error?.statusCode ? `HTTP ${error.statusCode}` : String(error?.message ?? error).slice(0, 120);
}

/**
 * Stops a ride by any agent id it has had (the app knows the first; Live Guardian may have
 * replaced it). Already-stopped agents count as success.
 */
export async function stopRide(agentId) {
  const ride = rideForAgent(agentId);
  endRide(ride); // first, so a handoff in flight doesn't start a new agent
  const current = ride ? ride.agentId : agentId;
  if (!current) return; // paused for family or over the time limit: no agent running
  await stopAgent(current);
}

export async function stopAllRides() {
  await Promise.allSettled([...activeAgents].map((id) => stopRide(id)));
}
