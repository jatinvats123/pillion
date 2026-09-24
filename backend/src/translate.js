import { config } from './config.js';

// English subtitles for Hindi transcript lines, shown under them in the app. Display-only: the
// app calls this beside the voice loop, never in it, and shows the line as is if this fails.
// sarvam-translate:v1 (formal) measured 0.65–1.0 s from India and handled Hinglish lines that
// mayura:v1 garbled ("Rahul का order set हो गया" → "Your order for Rahul's order has been placed").
const URL = 'https://api.sarvam.ai/translate';
const TIMEOUT_MS = 3_000;
const MAX_CHARS = 1_000;
const CACHE_SIZE = 300;

const cache = new Map();

class TranslateError extends Error {}

export async function toEnglish(text) {
  if (!config.sarvam.apiKey) throw new TranslateError('no_sarvam_key');
  const input = text.slice(0, MAX_CHARS);
  const cached = cache.get(input);
  if (cached) return { english: cached, ms: 0 };

  const startedAt = Date.now();
  let res;
  let body;
  try {
    res = await fetch(URL, {
      method: 'POST',
      headers: { 'api-subscription-key': config.sarvam.apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        input,
        source_language_code: 'hi-IN',
        target_language_code: 'en-IN',
        model: 'sarvam-translate:v1',
        mode: 'formal',
      }),
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    body = await res.json();
  } catch (error) {
    throw new TranslateError(error?.name === 'TimeoutError' ? `timeout_${TIMEOUT_MS}ms` : res ? 'bad_json' : 'network_error');
  }
  const english = body?.translated_text?.trim();
  if (!res.ok || !english) throw new TranslateError(`http_${res.status}`);

  cache.set(input, english);
  if (cache.size > CACHE_SIZE) cache.delete(cache.keys().next().value);
  return { english, ms: Date.now() - startedAt };
}
