import { randomBytes, randomUUID } from 'node:crypto';
import { config } from './config.js';
import { sendToRider } from './rtm.js';

/**
 * Live rides, keyed by a random per-ride secret. Agora sends the secret on every tool call
 * (as a template variable) and the rider app sends it on its own requests.
 */
const rides = new Map();

/** A phone-side failure the LLM should hear about, e.g. `permission_denied` or `phone_not_responding`. */
export class PhoneError extends Error {
  constructor(code, detail = {}) {
    super(code);
    this.code = code;
    this.detail = detail;
  }
}

export function createRide({ channel, uid }) {
  sweepOldRides();
  const ride = {
    token: randomBytes(24).toString('base64url'),
    channel,
    uid,
    agentId: null,
    session: null,
    startedAt: Date.now(),
    waiting: new Map(), // phone request id → { resolve, reject, timer }
    prefetched: new Map(), // Jev prefetch: key → { at, promise }
    turn: null, // latest final rider transcript (+ Jev's reading of it)
    pendingAction: null, // SMS or call waiting for the rider's "yes"
  };
  rides.set(ride.token, ride);
  return ride;
}

export const rideForToken = (token) => (token ? rides.get(token) : undefined);

export function rideForAgent(agentId) {
  for (const ride of rides.values()) if (ride.agentId === agentId) return ride;
  return undefined;
}

export function endRide(ride) {
  if (!ride) return;
  rides.delete(ride.token);
  for (const [id, wait] of ride.waiting) {
    clearTimeout(wait.timer);
    wait.reject(new PhoneError('ride_ended'));
    ride.waiting.delete(id);
  }
}

// Agents that idle-stop on their own never hit /agent/stop; drop them once their token has expired.
function sweepOldRides() {
  const cutoff = Date.now() - config.tokenExpirySeconds * 1000;
  for (const ride of rides.values()) if (ride.startedAt < cutoff) endRide(ride);
}

/**
 * Asks the rider's phone to do something (read GPS, read earnings, send an SMS, …) over RTM and
 * waits for its answer on POST /ride/device-result. Resolves with the phone's data.
 */
export async function askPhone(ride, action, args = {}, timeoutMs = 6000) {
  const id = randomUUID();
  const answer = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ride.waiting.delete(id);
      reject(new PhoneError('phone_not_responding'));
    }, timeoutMs);
    ride.waiting.set(id, { resolve, reject, timer });
  });
  try {
    await sendToRider(ride.uid, { object: 'pillion.request', id, action, args });
  } catch (error) {
    settle(ride, id)?.reject(new PhoneError('phone_unreachable', { reason: error.message }));
  }
  return answer;
}

/** The phone's reply to [askPhone]: `{ id, ok: true, data }` or `{ id, ok: false, error, ...detail }`. */
export function answerFromPhone(ride, { id, ok, data, error, ...detail }) {
  const wait = settle(ride, id);
  if (!wait) return false;
  if (ok) wait.resolve(data ?? {});
  else wait.reject(new PhoneError(String(error || 'phone_error'), detail));
  return true;
}

function settle(ride, id) {
  const wait = ride.waiting.get(id);
  if (!wait) return undefined;
  clearTimeout(wait.timer);
  ride.waiting.delete(id);
  return wait;
}

/** Shows a short action line ("✓ SMS sent to Rahul") in the rider's transcript. Best effort. */
export function notifyRider(ride, text, ok = true) {
  sendToRider(ride.uid, { object: 'pillion.action', text, ok }).catch((error) =>
    console.warn(`[notify] ${error.message}`),
  );
}

const PREFETCH_MAX_AGE_MS = 15_000;

/** Starts fetching data a tool is about to need (Jev predicted it). Failures stay silent here. */
export function prefetch(ride, key, fetcher) {
  const promise = fetcher();
  promise.catch(() => {});
  ride.prefetched.set(key, { at: Date.now(), promise });
}

/** The prefetched promise for `key` if still fresh; each prefetch is used at most once. */
export function takePrefetched(ride, key) {
  const entry = ride.prefetched.get(key);
  ride.prefetched.delete(key);
  return entry && Date.now() - entry.at < PREFETCH_MAX_AGE_MS ? entry.promise : null;
}
