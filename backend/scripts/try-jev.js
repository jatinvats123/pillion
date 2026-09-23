// Asks Jev to classify rider sentences, exactly as a ride does.
//   node scripts/try-jev.js "Next drop kitni door hai?" "Aaj kitna kamaya?"
//   node scripts/try-jev.js --pending "Send an SMS to the customer Rahul: \"5 min\"" "haan bhej do"
//   --gap <seconds> between calls (default 2); the jevai.org key is tightly rate-limited.
import { setTimeout as sleep } from 'node:timers/promises';
import { classifyTurn, JevSkipped } from '../src/jev.js';

const args = process.argv.slice(2);
const option = (name) => {
  const at = args.indexOf(name);
  return at >= 0 ? args.splice(at, 2)[1] : undefined;
};
const pending = option('--pending');
const gapMs = Number(option('--gap') ?? 2) * 1000;
if (!args.length) {
  console.log('Usage: node scripts/try-jev.js [--pending "<action>"] [--gap <s>] "<rider sentence>" ...');
  process.exit(1);
}

for (const [i, text] of args.entries()) {
  if (i > 0) await sleep(gapMs);
  try {
    const r = await classifyTurn(text, pending);
    const confirm = r.confirm ? `  confirm=${r.confirm} (yes ${r.confirmYes.toFixed(2)})` : '';
    console.log(`${r.ms} ms  ${r.intent} ${r.confidence.toFixed(2)}${confirm}  ← "${text}"`);
  } catch (error) {
    if (!(error instanceof JevSkipped)) throw error;
    console.log(`skipped (${error.message})  ← "${text}"`);
  }
}
