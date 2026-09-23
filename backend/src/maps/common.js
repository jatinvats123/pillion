// Shared by the maps providers (MAPS_PROVIDER=geoapify|google).

export class MapsError extends Error {}

/** Kinds of place the rider can ask for; each provider maps them to its own categories or search text. */
export const PLACE_CATEGORIES = [
  'fuel',
  'ev_charging',
  'atm',
  'toilet',
  'food',
  'tea',
  'bike_repair',
  'hospital',
  'pharmacy',
  'police',
  'parking',
  'other',
];

/** fetch → JSON with a timeout; non-2xx becomes a MapsError the LLM can report. */
export async function fetchJson(url, options = {}) {
  let res;
  try {
    res = await fetch(url, { ...options, signal: AbortSignal.timeout(5000) });
  } catch (error) {
    throw new MapsError(error?.name === 'TimeoutError' ? 'maps_timeout' : 'maps_unreachable');
  }
  const json = await res.json().catch(() => ({}));
  if (!res.ok) {
    const detail = json?.error?.message ?? json?.message ?? '';
    throw new MapsError(`maps_http_${res.status}: ${detail}`.slice(0, 200));
  }
  return json;
}
