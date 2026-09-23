import { config } from './config.js';

// Jev (TypeSafe AI's "System One" model) answers typed questions about a state with calibrated
// probabilities, in well under a second. Pillion never depends on it: every caller has a plain LLM
// path for when Jev is off, slow, rate-limited or unsure.

const COOLDOWN_MS = 30_000;
let coolingUntil = 0;

/** Jev wasn't asked or didn't answer; `message` says why (off, timeout, rate_limited, …). */
export class JevSkipped extends Error {}

export const jevEnabled = () => config.jev.enabled && Boolean(config.jev.apiKey);

/** POST {model, state, questions} → answers. Works with jevai.org (JEV_URL default) and api.typesafe.ai. */
export async function askJev(state, questions) {
  if (!jevEnabled()) throw new JevSkipped('off');
  if (Date.now() < coolingUntil) throw new JevSkipped('cooling_down_after_429');

  const startedAt = Date.now();
  let res;
  let body;
  try {
    res = await fetch(config.jev.url, {
      method: 'POST',
      headers: { Authorization: `Bearer ${config.jev.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...(config.jev.model && { model: config.jev.model }), state, questions }),
      signal: AbortSignal.timeout(config.jev.timeoutMs),
    });
    body = await res.json();
  } catch (error) {
    const reason = error?.name === 'TimeoutError' ? `timeout_${config.jev.timeoutMs}ms` : res ? 'bad_json' : 'network_error';
    throw new JevSkipped(reason);
  }
  if (res.status === 429 || res.status === 529) {
    // The jevai.org key is tightly rate-limited; back off instead of adding a failed call to every turn.
    coolingUntil = Date.now() + COOLDOWN_MS;
    throw new JevSkipped(`rate_limited_${res.status}`);
  }
  // jevai.org wraps answers as {code, data: {answers}}; TypeSafe's own API returns {answers}.
  const answers = body?.data?.answers ?? body?.answers;
  if (!res.ok || !answers) throw new JevSkipped(`http_${res.status}`);
  return { answers, ms: Date.now() - startedAt };
}

const INTENT_QUESTION = {
  type: 'choice',
  instructions:
    'A delivery rider on a motorbike spoke to their voice assistant in Hindi, English or Hinglish (`rider_said`). What does the rider want the assistant to do now?',
  criteria: {
    eta: 'Tell how far the next drop or customer address is, or how long until the rider reaches it',
    nearby: 'Find a nearby place, e.g. petrol pump, CNG, EV charging, ATM, toilet, food, tea, puncture repair, hospital',
    sms_customer: 'Send a text message to the customer',
    call_customer: 'Phone the customer',
    earnings: 'Tell the rider their trips or money earned (today, yesterday, this week)',
    sos: 'Emergency: the rider asks for SOS or help, had an accident, is hurt or needs an ambulance',
    chat: 'Anything else: small talk, general questions, a yes/no reply, unclear or cut-off speech',
  },
};

const CONFIRM_QUESTION = {
  type: 'choice',
  instructions: 'The assistant asked the rider to confirm `pending_action`. Is `rider_said` a clear yes to go ahead with it?',
  criteria: {
    yes: 'A clear go-ahead, e.g. haan, ha, haan bhej do, kar do, laga do, theek hai, yes, ok, sure, go ahead',
    no: 'Refuses, cancels, or wants to wait or change it, e.g. nahi, mat bhejo, ruko, rehne do, cancel, no, wait',
    unclear: 'Anything else, or not an answer to the question',
  },
};

/**
 * One Jev call per rider turn: the intent, plus — if an SMS/call is waiting for a yes — whether
 * this turn is that yes. Throws [JevSkipped] when Jev didn't answer.
 */
export async function classifyTurn(text, pendingAction) {
  const state = { rider_said: text, ...(pendingAction && { pending_action: pendingAction }) };
  const questions = { intent: INTENT_QUESTION, ...(pendingAction && { confirm: CONFIRM_QUESTION }) };
  const { answers, ms } = await askJev(state, questions);
  return {
    ms,
    intent: answers.intent?.choice ?? 'chat',
    confidence: answers.intent?.confidence ?? 0,
    confirm: answers.confirm?.choice ?? null,
    confirmYes: answers.confirm?.probabilities?.yes ?? 0,
  };
}
