import { timingSafeEqual } from 'node:crypto';
import { config } from './config.js';

// What keeps a public backend from spending the Agora / Sarvam / maps accounts without limit:
// the release app's key (a speed bump: it can be pulled out of the APK), rate limits per IP,
// device and ride, a cap on concurrent rides, daily caps on rides and agent minutes, and a kill
// switch. All in memory: a restart resets the day's counters (the providers' own budgets are the
// backstop). The per-ride secret on tool calls stays as it was.

/** Fixed-window request counter per key (IP, device, ride). Returns false once over `max`. */
export function rateLimiter({ max, windowMs }) {
  const hits = new Map();
  return (key) => {
    const now = Date.now();
    const entry = hits.get(key);
    if (!entry || now - entry.start >= windowMs) {
      if (hits.size > 10_000) hits.clear();
      hits.set(key, { start: now, count: 1 });
      return true;
    }
    entry.count += 1;
    return entry.count <= max;
  };
}

/**
 * The caller's address. Behind Cloudflare (the tunnel, and Render's edge) Cloudflare sets
 * cf-connecting-ip itself; other proxies append the client to x-forwarded-for.
 */
export function clientIp(req) {
  return (
    req.get('cf-connecting-ip') ||
    req.get('x-forwarded-for')?.split(',')[0].trim() ||
    req.socket.remoteAddress ||
    'unknown'
  );
}

export const publicMode = () => Boolean(config.appKey);

export function hasAppKey(req) {
  const given = Buffer.from(String(req.get('x-pillion-key') ?? ''));
  const expected = Buffer.from(config.appKey);
  return given.length === expected.length && timingSafeEqual(given, expected);
}

// ---- Starting rides

const startsPerIp = rateLimiter({ max: 4, windowMs: 10 * 60_000 });
const startsPerDevice = rateLimiter({ max: 4, windowMs: 10 * 60_000 });

// India time: the day's caps reset at midnight IST.
const today = () => new Date(Date.now() + 5.5 * 3600_000).toISOString().slice(0, 10);
let usage = { day: today(), rides: 0, agentMs: 0 };
const liveAgents = new Map(); // agent id → start time, for minutes not yet counted

function currentUsage() {
  if (usage.day !== today()) usage = { day: today(), rides: 0, agentMs: 0 };
  return usage;
}

/** Agent minutes today: finished agents plus the running ones so far. */
function agentMinutesToday() {
  const running = [...liveAgents.values()].reduce((sum, since) => sum + (Date.now() - since), 0);
  return (currentUsage().agentMs + running) / 60_000;
}

/**
 * Can this request start a ride? Returns null, or { status, error } for the app to explain:
 * rides_paused (kill switch), server_busy, slow_down (rate limit), demo_limit_reached (daily caps).
 */
export function refuseRideStart(req, activeRides) {
  const { limits } = config;
  if (!limits.ridesEnabled) return { status: 503, error: 'rides_paused' };
  if (limits.maxConcurrentRides && activeRides >= limits.maxConcurrentRides) return { status: 503, error: 'server_busy' };
  const device = String(req.get('x-pillion-device') ?? '').slice(0, 64);
  if (!startsPerIp(clientIp(req)) || (device && !startsPerDevice(device))) return { status: 429, error: 'slow_down' };
  const day = currentUsage();
  if (limits.maxRidesPerDay && day.rides >= limits.maxRidesPerDay) return { status: 429, error: 'demo_limit_reached' };
  if (limits.maxAgentMinutesPerDay && agentMinutesToday() >= limits.maxAgentMinutesPerDay) {
    return { status: 429, error: 'demo_limit_reached' };
  }
  return null;
}

export function rideStarted() {
  currentUsage().rides += 1;
}

/** Every agent counts towards the day's minutes from its start until it is stopped. */
export function agentStarted(agentId) {
  liveAgents.set(agentId, Date.now());
}

export function agentStopped(agentId) {
  const since = liveAgents.get(agentId);
  if (since === undefined) return;
  liveAgents.delete(agentId);
  currentUsage().agentMs += Date.now() - since;
}

export const usageToday = () => ({
  rides: currentUsage().rides,
  agentMinutes: Math.round(agentMinutesToday()),
});

// ---- Per-ride request limits (the app's own calls and Agora's tool calls)

const ridePosts = rateLimiter({ max: 120, windowMs: 60_000 });
const rideTranslations = rateLimiter({ max: 30, windowMs: 60_000 }); // each one is a Sarvam call
const geocodes = rateLimiter({ max: 20, windowMs: 10 * 60_000 }); // three maps lookups each

export const rideCallAllowed = (ride) => ridePosts(ride.token);
export const translateAllowed = (ride) => rideTranslations(ride.token);
export const geocodeAllowed = (req) => geocodes(clientIp(req));
