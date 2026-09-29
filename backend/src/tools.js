import { config } from './config.js';
import { classifyTurn, JevSkipped } from './jev.js';
import { geocodeAddress, MapsError, PLACE_CATEGORIES, placesNear, routeTo } from './maps/index.js';
import { askPhone, notifyRider, PhoneError, prefetch, takePrefetched } from './rides.js';

// Pillion's actions, exposed to the LLM as Agora ConvoAI custom tools (llm.tools). Agora's cloud
// calls POST {PUBLIC_BASE_URL}/tools/<name>; anything that needs the phone (GPS, earnings, SMS,
// call) is relayed to the app over RTM. Only the LLM's reply is spoken, never the raw result.

/** A tool failure. Sent back as non-2xx JSON, so the LLM never claims the action worked. */
class ToolError extends Error {
  constructor(status, code, detail = {}) {
    super(code);
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

const PENDING_ACTION_TTL_MS = 60_000;
// Jev must put at least this probability on "yes" before an SMS goes out or a call is placed.
const CONFIRM_MIN_YES = 0.8;

/** Tool declarations for the agent's `llm.tools`. */
export function toolDefinitions() {
  const tool = (name, description, properties = {}, timeoutMs = 10_000) => {
    // Agora fails the call if a body placeholder is missing, so every argument is required.
    const args = Object.keys(properties);
    return {
      type: 'function',
      function: { name, description, parameters: { type: 'object', properties, required: args } },
      server: {
        method: 'POST',
        url: `${config.publicBaseUrl}/tools/${name}`,
        headers: { Authorization: '{{template_variables.tool_auth}}', 'Content-Type': 'application/json' },
        body: {
          ...Object.fromEntries(args.map((arg) => [arg, `{{args.${arg}}}`])),
          tool_call_id: '{{tool_call_id}}',
        },
        timeout_ms: timeoutMs,
      },
    };
  };

  return [
    tool(
      'getNextDropEta',
      'Road distance and travel time by motorbike from the rider to the next drop address. Use when the rider asks how far the next drop or customer is, or when they will reach.',
    ),
    tool(
      'findNearby',
      'The nearest places of one kind around the rider, with road distance and time.',
      {
        category: {
          type: 'string',
          enum: PLACE_CATEGORIES,
          description: 'fuel = petrol pump or CNG; bike_repair = puncture or bike mechanic; other = anything not listed.',
        },
        place: { type: 'string', description: 'What the rider asked for, in English, e.g. "petrol pump", "ATM".' },
      },
    ),
    tool(
      'getEarnings',
      "The rider's trips and earnings today, yesterday, and yesterday up to this time of day, with the difference already worked out.",
    ),
    tool(
      'prepareSms',
      "Prepares a text message to the current order's customer. Sends nothing: returns the customer's name so you can ask the rider to confirm.",
      {
        message: {
          type: 'string',
          description:
            "The SMS text as the rider would type it: first person, short, Latin script (English or Hinglish), e.g. '5 minute mein pahunch raha hoon.'",
        },
      },
      8000,
    ),
    tool(
      'prepareCall',
      "Prepares a phone call to the current order's customer. Calls nobody: returns the customer's name so you can ask the rider to confirm.",
      {},
      8000,
    ),
    tool(
      'sendSos',
      "Emergency SOS. The rider's phone texts their emergency contacts their location after a 5-second window in which the rider can cancel. Call it straight away, without asking to confirm, when the rider asks for SOS, help in an emergency, an ambulance, or says they had an accident or are hurt.",
      {},
      8000,
    ),
    tool(
      'confirmPendingAction',
      'Use after you asked the rider to confirm a prepared SMS or call. answer "yes" only if the rider clearly said yes, "no" if they said no or cancelled. Returns whether the SMS was sent or the call placed.',
      { answer: { type: 'string', enum: ['yes', 'no'] } },
      20_000,
    ),
  ];
}

const handlers = {
  // The phone runs the SOS itself (countdown, SMS over the SIM, speaking the result); this only
  // starts it. No action line: the phone shows its own.
  async sendSos(ctx) {
    const result = await askPhone(ctx.ride, 'sos', {}, 6000);
    return { body: result, line: null };
  },

  async getNextDropEta(ctx) {
    const route = await prefetchedOr(ctx, 'eta', () => nextDropRoute(ctx.ride));
    return { body: route, line: `Next drop · ${route.distance_km} km · ${route.minutes} min` };
  },

  async findNearby(ctx, { category, place }) {
    const query = String(place ?? '').trim().slice(0, 80);
    const kind = PLACE_CATEGORIES.includes(category) ? category : 'other';
    if (!query && kind === 'other') throw new ToolError(400, 'place_missing');
    const location = await prefetchedOr(ctx, 'location', () => askPhone(ctx.ride, 'location'));
    const results = (await placesNear(location, { category: kind, query })).map((p) => ({
      name: p.name ?? `unnamed ${query || kind}`,
      address: p.address,
      distance_km: p.distanceMeters == null ? null : round1(p.distanceMeters / 1000),
      distance_is: p.byRoad ? 'by_road' : 'straight_line',
      minutes: p.durationSeconds == null ? null : Math.max(1, Math.round(p.durationSeconds / 60)),
      open_now: p.openNow,
    }));
    const first = results[0];
    return {
      body: { place: query, results, ...locationAge(location) },
      line: first ? `${first.name} · ${first.distance_km ?? '?'} km` : `No ${query} found nearby`,
    };
  },

  async getEarnings(ctx) {
    const earnings = await prefetchedOr(ctx, 'earnings', () => askPhone(ctx.ride, 'earnings'));
    const today = earnings.today ?? {};
    return { body: earnings, line: `Today ₹${today.earned_rupees ?? '?'} · ${today.trips ?? '?'} trips` };
  },

  async prepareSms(ctx, { message }) {
    const text = String(message ?? '').trim();
    if (!text) throw new ToolError(400, 'message_missing');
    if (text.length > 300) throw new ToolError(400, 'message_too_long');
    const name = await customerFirstName(ctx);
    ctx.ride.pendingAction = { kind: 'sms', message: text, customerName: name, at: Date.now() };
    return {
      body: { status: 'waiting_for_rider_confirmation', customer_name: name, message: text },
      line: `SMS to ${name}: "${text}" · waiting for yes`,
    };
  },

  async prepareCall(ctx) {
    const name = await customerFirstName(ctx);
    ctx.ride.pendingAction = { kind: 'call', customerName: name, at: Date.now() };
    return { body: { status: 'waiting_for_rider_confirmation', customer_name: name }, line: `Call ${name}? · waiting for yes` };
  },

  async confirmPendingAction(ctx, { answer }) {
    const { ride } = ctx;
    const pending = ride.pendingAction;
    if (!pending || Date.now() - pending.at > PENDING_ACTION_TTL_MS) {
      ride.pendingAction = null;
      throw new ToolError(409, 'nothing_to_confirm');
    }
    ctx.label = pending.kind === 'sms' ? 'SMS' : 'Call';
    if (answer !== 'yes') {
      ride.pendingAction = null;
      return { body: { status: 'cancelled' }, line: `${ctx.label} to ${pending.customerName} cancelled` };
    }

    const gate = await confirmationGate(ride, pending);
    ctx.gate = gate.why;
    if (!gate.allowed) {
      throw new ToolError(409, 'rider_did_not_clearly_confirm', {
        rider_said: gate.riderSaid,
        next_step: 'Nothing was sent. Ask again in a few words for a clear yes or no.',
      });
    }

    ride.pendingAction = null;
    if (pending.kind === 'sms') {
      await askPhone(ride, 'sms', { message: pending.message }, 15_000);
      return { body: { status: 'sms_sent', customer_name: pending.customerName }, line: `✓ SMS sent to ${pending.customerName}` };
    }
    await askPhone(ride, 'call', {}, 8000);
    return { body: { status: 'call_started', customer_name: pending.customerName }, line: `✓ Calling ${pending.customerName}` };
  },
};

/**
 * The server-side "yes" check Agora recommends for actions: the rider's own words (the ASR
 * transcript the app posted, not the LLM's reading of them) must be a clear yes according to Jev.
 * If Jev didn't answer, the LLM's judgement stands.
 */
async function confirmationGate(ride, pending) {
  const turn = ride.turn;
  if (!turn || turn.at < pending.at) return { allowed: true, why: 'no transcript since the question, LLM decides' };
  const jev = await turn.jev;
  if (jev.skipped) return { allowed: true, why: `jev ${jev.skipped}, LLM decides` };
  const yes = jev.confirmYes.toFixed(2);
  if (jev.confirm === 'yes' && jev.confirmYes >= CONFIRM_MIN_YES) return { allowed: true, why: `jev yes ${yes}` };
  return { allowed: false, why: `jev ${jev.confirm} (yes ${yes}), blocked`, riderSaid: turn.text };
}

async function nextDropRoute(ride) {
  const [location, order] = await Promise.all([askPhone(ride, 'location'), askPhone(ride, 'order')]);
  const drop = await dropPoint(order, location);
  const route = await routeTo(location, drop.destination);
  return {
    drop_address: order.drop_address,
    // Only the drop's locality is known (scanned address): the time is rough.
    ...(drop.approximate && { drop_precision: 'area' }),
    distance_km: round1(route.distanceMeters / 1000),
    minutes: Math.max(1, Math.round(route.durationSeconds / 60)),
    // 'live' (Google) or 'typical_estimate' (Geoapify: usual traffic, not today's).
    traffic: route.traffic,
    ...(route.trafficDelaySeconds != null && { traffic_delay_minutes: Math.round(route.trafficDelaySeconds / 60) }),
    ...locationAge(location),
  };
}

/**
 * Where to route to. A scanned order comes with the drop's point (looked up when the rider set it)
 * or `drop_location: 'not_found'`, or 'unchecked' if the server couldn't be reached then. The
 * seeded demo order sends only its address, geocoded as before.
 */
async function dropPoint(order, near) {
  if (Number.isFinite(order.drop_lat) && Number.isFinite(order.drop_lng)) {
    return { destination: { lat: order.drop_lat, lng: order.drop_lng }, approximate: order.drop_precision === 'area' };
  }
  if (order.drop_location === 'not_found') throw new ToolError(409, 'drop_location_unknown');
  if (order.drop_location === 'unchecked') {
    const found = await geocodeAddress(order.drop_address, { area: order.drop_area || null, near });
    if (!('lat' in found)) throw new ToolError(409, 'drop_location_unknown', { reason: found.status });
    return { destination: found, approximate: found.status === 'approximate' };
  }
  return { destination: order.drop_address, approximate: false };
}

// Without a fresh GPS fix in time the phone sends its last one (up to 5 min old); say so rather
// than present an old position as the current one.
const locationAge = (location) =>
  location?.age_s > 60 ? { location_age_minutes: Math.round(location.age_s / 60) } : {};

// The phone sends the customer's name and drop address; the phone number never leaves the phone.
async function customerFirstName(ctx) {
  const order = await prefetchedOr(ctx, 'order', () => askPhone(ctx.ride, 'order'));
  return String(order.customer_name ?? '').trim().split(/\s+/)[0] || 'customer';
}

function prefetchedOr(ctx, key, fetcher) {
  const prefetched = takePrefetched(ctx.ride, key);
  if (prefetched) ctx.prefetchHit = true;
  return prefetched ?? fetcher();
}

/** Runs one tool call from Agora. Returns the HTTP status and JSON body for the LLM. */
export async function runTool(ride, name, args) {
  const handler = Object.hasOwn(handlers, name) ? handlers[name] : null;
  if (!handler) return { status: 404, body: { error: 'unknown_tool' } };

  const ctx = { ride, prefetchHit: false, gate: null, label: null };
  const turn = ride.turn;
  const startedAt = Date.now();
  let status = 200;
  let body;
  let line;
  try {
    ({ body, line } = await handler(ctx, args ?? {}));
  } catch (error) {
    ({ status, body } = toolFailure(error));
    line = `✗ ${ctx.label ?? TOOL_LABELS[name]}: ${body.error.replaceAll('_', ' ')}`;
  }
  if (line) notifyRider(ride, line, status === 200);
  logTool(ride, turn, name, status === 200 ? 'ok' : body.error, startedAt, ctx);
  return { status, body };
}

// Never 502/504: Cloudflare (the tunnel) swaps those for its own HTML error page, which would then
// reach the LLM instead of our JSON. 424 = a dependency (phone, maps) failed.
function toolFailure(error) {
  if (error instanceof ToolError) return { status: error.status, body: { error: error.code, ...error.detail } };
  if (error instanceof PhoneError) {
    const status = error.code === 'permission_denied' ? 403 : error.code.startsWith('phone_') ? 424 : 409;
    return { status, body: { error: error.code, ...error.detail } };
  }
  if (error instanceof MapsError) {
    console.warn(`[maps] ${error.message}`);
    return { status: 424, body: { error: error.message.split(':')[0] } };
  }
  console.error('[tool] unexpected failure', error);
  return { status: 500, body: { error: 'internal_error' } };
}

// ---- Jev intent routing, one call per final rider transcript (posted by the app).
// Jev runs alongside the LLM, never in front of it: when it is confident, the data the LLM is about
// to ask for is fetched early. Off, slow, rate-limited or unsure → nothing happens; the LLM path is unchanged.

const PREFETCH = {
  eta: ['eta', (ride) => nextDropRoute(ride)],
  nearby: ['location', (ride) => askPhone(ride, 'location')],
  earnings: ['earnings', (ride) => askPhone(ride, 'earnings')],
  sms_customer: ['order', (ride) => askPhone(ride, 'order')],
  call_customer: ['order', (ride) => askPhone(ride, 'order')],
};

export function onRiderTurn(ride, { turnId, text }) {
  const pending =
    ride.pendingAction && Date.now() - ride.pendingAction.at < PENDING_ACTION_TTL_MS ? ride.pendingAction : null;
  const turn = { id: turnId, text, at: Date.now(), jevResult: null };
  ride.turn = turn;

  turn.jev = classifyTurn(text, pending && describePending(pending))
    .then((jev) => {
      const entry = jev.confidence >= config.jev.minConfidence ? PREFETCH[jev.intent] : null;
      if (entry) prefetch(ride, entry[0], () => entry[1](ride));
      const confirm = jev.confirm ? `, confirm=${jev.confirm} (yes ${jev.confirmYes.toFixed(2)})` : '';
      console.log(
        `[jev] ${tag(ride, turn)} ${words(text)} → ${jev.intent} ${jev.confidence.toFixed(2)}${confirm} in ${jev.ms} ms${entry ? ` · prefetching ${entry[0]}` : ''}`,
      );
      return jev;
    })
    .catch((error) => {
      if (!(error instanceof JevSkipped)) console.error('[jev] unexpected', error);
      console.log(`[jev] ${tag(ride, turn)} ${words(text)} → skipped (${error.message}); LLM routes alone`);
      return { skipped: error.message };
    })
    .then((result) => (turn.jevResult = result));
}

const describePending = (p) =>
  p.kind === 'sms' ? `Send an SMS to the customer ${p.customerName}: "${p.message}"` : `Phone the customer ${p.customerName}`;

// ---- Logging: one line per tool call, with how Jev's routing compared to the LLM's choice.

const TOOL_LABELS = {
  getNextDropEta: 'ETA',
  findNearby: 'Nearby',
  getEarnings: 'Earnings',
  prepareSms: 'SMS',
  prepareCall: 'Call',
  confirmPendingAction: 'Confirm',
  sendSos: 'SOS',
};
const TOOL_INTENT = {
  getNextDropEta: 'eta',
  findNearby: 'nearby',
  getEarnings: 'earnings',
  prepareSms: 'sms_customer',
  prepareCall: 'call_customer',
  confirmPendingAction: 'chat',
  sendSos: 'sos',
};

function logTool(ride, turn, name, outcome, startedAt, ctx) {
  const jev = turn?.jevResult;
  const routing = !turn
    ? 'no transcript'
    : !jev
      ? 'jev still pending'
      : jev.skipped
        ? `jev skipped (${jev.skipped})`
        : `jev said ${jev.intent} ${jev.intent === TOOL_INTENT[name] ? '(agrees)' : '(differs)'}`;
  const parts = [
    `[tool] ${tag(ride, turn)} ${name} → ${outcome} in ${Date.now() - startedAt} ms`,
    turn ? `called ${startedAt - turn.at} ms after transcript` : null,
    routing,
    ctx.prefetchHit ? 'prefetch hit' : null,
    ctx.gate ? `confirm: ${ctx.gate}` : null,
  ];
  console.log(parts.filter(Boolean).join(' · '));
}

const tag = (ride, turn) => `ride=${ride.channel.slice(-6)} turn=${turn?.id ?? '-'}`;
// Logs never carry what the rider said (names, numbers, addresses): only its length.
const words = (text) => `(${text.split(/\s+/).filter(Boolean).length} words)`;
const round1 = (n) => Math.round(n * 10) / 10;
