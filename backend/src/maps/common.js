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

/** Straight-line distance between two {lat, lng} points, in km. */
export function distanceKm(a, b) {
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad;
  const dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 12742 * Math.asin(Math.sqrt(h));
}

/** A six-digit Indian PIN code in an address ("Delhi 110092", "110 092"), or null. */
export function pinCodeOf(address) {
  const match = String(address).match(/(?<!\d)([1-8]\d{2})\s?(\d{3})(?!\d)/);
  return match ? match[1] + match[2] : null;
}
