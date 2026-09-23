// End-to-end check without the phone: starts a real ride on the running backend, types rider
// sentences into the agent (Agora "think"), then prints which tools the LLM called and its replies.
// Phone-backed tools fail with phone_not_responding (no app in this ride) — that checks the error path.
// With no rider in the channel the agent idle-stops ~30 s after start, so give 2–3 sentences per run.
//   node scripts/ride-check.js "Next drop kitni door hai?" "What did I earn today?"
// BACKEND_URL defaults to http://127.0.0.1:3000.
import { setTimeout as sleep } from 'node:timers/promises';

const base = (process.env.BACKEND_URL ?? 'http://127.0.0.1:3000').replace(/\/+$/, '');
const sentences = process.argv.slice(2);
if (!sentences.length) {
  console.log('Usage: node scripts/ride-check.js "<rider sentence>" ...');
  process.exit(1);
}

async function call(method, path, body, rideToken) {
  const res = await fetch(base + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(rideToken && { Authorization: `Bearer ${rideToken}` }) },
    body: body && JSON.stringify(body),
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`${method} ${path} → HTTP ${res.status} ${JSON.stringify(json)}`);
  return json;
}

const ride = await call('POST', '/agent/start', {});
console.log(`ride started: agent=${ride.agentId}`);
// Agora deletes the history once the agent stops, so keep the latest copy after every sentence.
let history;
try {
  await sleep(3000); // let the greeting start
  for (const text of sentences) {
    console.log(`> ${text}`);
    await call('POST', '/debug/think', { text }, ride.rideToken);
    // Leave time for tool calls (phone-backed ones time out after ~6 s here) and the reply.
    await sleep(10_000);
    history = await call('GET', '/debug/history', undefined, ride.rideToken);
  }
} catch (error) {
  console.warn(`stopped early: ${error.message}`);
} finally {
  console.log('\n--- conversation ---');
  for (const m of history?.contents ?? []) {
    const what = m.tool_calls
      ? `calls ${m.tool_calls.map((c) => `${c.name}(${JSON.stringify(c.arguments)})`).join(', ')}`
      : String(m.content ?? '');
    console.log(`turn ${m.turn_id} ${m.role.padEnd(9)} ${what}`);
  }
  await call('POST', '/agent/stop', { agentId: ride.agentId }).catch((e) => console.warn(e.message));
  console.log('ride stopped');
}
