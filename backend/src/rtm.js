import agoraToken from 'agora-token';
import { config } from './config.js';

const { RtmTokenBuilder } = agoraToken; // CommonJS package: no named ESM exports

// The backend talks to the rider's phone through Agora Signaling (RTM): the app is already logged in
// for transcripts, so a server-sent peer message reaches it with no extra connection.
const SENDER = 'pillion-server';
const TOKEN_TTL_SECONDS = 3600;

let serverToken = { value: '', expiresAt: 0 };

function token() {
  if (Date.now() > serverToken.expiresAt - 60_000) {
    serverToken = {
      value: RtmTokenBuilder.buildToken(config.agora.appId, config.agora.appCertificate, SENDER, TOKEN_TTL_SECONDS),
      expiresAt: Date.now() + TOKEN_TTL_SECONDS * 1000,
    };
  }
  return serverToken.value;
}

/** Sends a JSON message to one rider over RTM (Signaling REST "send peer-to-peer message"). */
export async function sendToRider(uid, message) {
  const url = `https://api.agora.io/dev/v2/project/${config.agora.appId}/rtm/users/${SENDER}/peer_messages`;
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      // Agora's docs show `x-agora-token` + `x-agora-uid` (rejected: "invalid token") or Basic auth
      // with Customer ID/Secret. `Authorization: agora token=<RTM token>` works with our certificate.
      Authorization: `agora token=${token()}`,
      'Content-Type': 'application/json;charset=utf-8',
    },
    body: JSON.stringify({
      destination: String(uid),
      enable_offline_messaging: false,
      enable_historical_messaging: false,
      payload: JSON.stringify(message),
    }),
    signal: AbortSignal.timeout(5000),
  });
  if (!res.ok) throw new Error(`RTM message failed: HTTP ${res.status} ${(await res.text()).slice(0, 200)}`);
}
