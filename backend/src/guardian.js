import { randomInt } from 'node:crypto';
import agoraToken from 'agora-token';
import { config } from './config.js';

const { RtcRole, RtcTokenBuilder } = agoraToken; // CommonJS package: no named ESM exports

/**
 * Live Guardian links: an SOS SMS carries PUBLIC_BASE_URL/g/<token>, and whoever opens it hears
 * the rider, talks to them and sees their live location. The phone makes the token (so the SMS
 * never waits for this server) and registers it here with the ride's secret. A link exposes only
 * the rider's name, their location and their audio (an RTC-only token: no RTM, so no transcripts).
 * In memory only: links die with this process. Logs carry a token prefix and events, nothing else.
 */
const links = new Map();

const LINK_TTL_MS = 2 * 60 * 60 * 1000;
const MAX_LINKS_PER_RIDE = 3;
const TOKEN_FORMAT = /^[A-Za-z0-9_-]{22,64}$/;
// Family uids: riders get 100000–899999 and the agent AGORA_AGENT_UID, so these never collide.
const GUARDIAN_UID_MIN = 900_000;
const GUARDIAN_UID_MAX = 1_000_000;
const ONLINE_WINDOW_MS = 20_000; // the phone posts every 5 s while a link is live
const MAX_JOINS = 10; // per link per JOIN_WINDOW_MS
const JOIN_WINDOW_MS = 10 * 60 * 1000;

export const guardianEnabled = () => config.guardian.enabled && Boolean(config.publicBaseUrl);
export const shortId = (token) => String(token).slice(0, 6);

/** The phone put this link in an SOS SMS. Returns an error code, or null when registered. */
export function registerLink(ride, { token, name }) {
  sweep();
  if (!TOKEN_FORMAT.test(String(token ?? ''))) return 'bad_token';
  if (links.has(token)) return links.get(token).rideToken === ride.token ? null : 'bad_token';
  const count = [...links.values()].filter((link) => link.rideToken === ride.token).length;
  if (count >= MAX_LINKS_PER_RIDE) return 'too_many_links';
  links.set(token, {
    token,
    rideToken: ride.token,
    channel: ride.channel,
    riderUid: ride.uid,
    name: String(name ?? '').trim().slice(0, 40) || 'Rider',
    expiresAt: Date.now() + LINK_TTL_MS,
    location: null, // { lat, lng, accuracyM, at }
    lastPostAt: Date.now(),
    riderOkAt: null, // the rider tapped I'M OK NOW: location sharing stopped
    joins: [],
  });
  console.log(`[guardian] link ${shortId(token)} registered (expires in 2 h)`);
  return null;
}

/** A fix from the rider's phone, for every live link of this ride (none after I'M OK NOW). */
export function updateLocation(ride, { lat, lng, accuracyM, ageMs }) {
  if (![lat, lng].every(Number.isFinite) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return;
  const at = Date.now() - Math.max(0, Number(ageMs) || 0);
  for (const link of liveLinksOf(ride)) {
    link.lastPostAt = Date.now();
    if (!link.location || at >= link.location.at) {
      link.location = { lat, lng, accuracyM: Number.isFinite(accuracyM) ? Math.round(accuracyM) : null, at };
    }
  }
}

/** The phone is still there, even without a new fix. */
export function touchLinks(ride) {
  for (const link of liveLinksOf(ride)) link.lastPostAt = Date.now();
}

/** I'M OK NOW: the pages say so and keep the last location; no more updates. */
export function riderIsOk(ride) {
  for (const link of liveLinksOf(ride)) {
    link.riderOkAt = Date.now();
    console.log(`[guardian] link ${shortId(link.token)}: rider is OK, sharing stopped`);
  }
}

export const rideHasLinks = (ride) => liveLinksOf(ride).length > 0;

/** A live link, or undefined when unknown or expired (the page can't tell the two apart). */
export function linkFor(token) {
  const link = links.get(String(token ?? ''));
  if (!link) return undefined;
  if (Date.now() >= link.expiresAt) {
    links.delete(link.token);
    return undefined;
  }
  return link;
}

export function statusOf(link, rideIsLive) {
  const online = rideIsLive && !link.riderOkAt && Date.now() - link.lastPostAt < ONLINE_WINDOW_MS;
  return {
    name: link.name,
    online,
    riderOk: Boolean(link.riderOkAt),
    rideEnded: !rideIsLive,
    location: link.location && { ...link.location, ageMs: Date.now() - link.location.at },
    expiresInMs: link.expiresAt - Date.now(),
  };
}

/** An RTC-only token for one family member, valid until the link expires. Null when rate-limited. */
export function joinCredentials(link) {
  const now = Date.now();
  link.joins = link.joins.filter((at) => now - at < JOIN_WINDOW_MS);
  if (link.joins.length >= MAX_JOINS) return null;
  link.joins.push(now);
  const uid = randomInt(GUARDIAN_UID_MIN, GUARDIAN_UID_MAX);
  const ttl = Math.max(60, Math.floor((link.expiresAt - now) / 1000));
  const token = RtcTokenBuilder.buildTokenWithUid(
    config.agora.appId,
    config.agora.appCertificate,
    link.channel,
    uid,
    RtcRole.PUBLISHER, // push-to-talk
    ttl,
    ttl,
  );
  return { appId: config.agora.appId, channel: link.channel, uid, token, riderUid: link.riderUid };
}

function liveLinksOf(ride) {
  return [...links.values()].filter((link) => link.rideToken === ride.token && Date.now() < link.expiresAt && !link.riderOkAt);
}

function sweep() {
  for (const link of links.values()) if (Date.now() >= link.expiresAt) links.delete(link.token);
}
