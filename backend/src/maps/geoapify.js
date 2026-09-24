import { config } from '../config.js';
import { distanceKm, fetchJson, MapsError, pinCodeOf } from './common.js';

// Geoapify (MAPS_PROVIDER=geoapify; free tier, OpenStreetMap data). No live traffic: routes use
// Geoapify's "approximated" model, which slows typically busy roads (free-flow said 8 min for
// 8 km across Delhi, approximated 26 min). The drop address is geocoded first.

const BASE = 'https://api.geoapify.com';
const MODE = 'motorcycle';
const TRAFFIC = 'approximated';
const SEARCH_RADIUS_M = 5000;

const CATEGORIES = {
  fuel: 'service.vehicle.fuel',
  ev_charging: 'service.vehicle.charging_station',
  atm: 'service.financial.atm',
  toilet: 'amenity.toilet',
  food: 'catering.fast_food,catering.restaurant',
  tea: 'catering.cafe',
  bike_repair: 'service.vehicle.repair',
  hospital: 'healthcare.hospital',
  pharmacy: 'healthcare.pharmacy',
  police: 'service.police',
  parking: 'parking',
};

function url(path, params) {
  if (!config.maps.geoapifyApiKey) throw new MapsError('maps_not_configured');
  return `${BASE}${path}?${new URLSearchParams({ ...params, apiKey: config.maps.geoapifyApiKey })}`;
}

// A rider's drop address doesn't move; don't geocode it on every ETA question.
const geocoded = new Map();

async function geocode(address, near) {
  if (geocoded.has(address)) return geocoded.get(address);
  const json = await fetchJson(
    url('/v1/geocode/search', {
      text: address,
      filter: 'countrycode:in',
      bias: `proximity:${near.lng},${near.lat}`,
      limit: 1,
      format: 'json',
    }),
  );
  const hit = json.results?.[0];
  if (!hit) throw new MapsError('address_not_found');
  const point = { lat: hit.lat, lng: hit.lon };
  if (geocoded.size > 100) geocoded.clear();
  geocoded.set(address, point);
  return point;
}

export async function routeTo(origin, to) {
  const destination = typeof to === 'string' ? await geocode(to, origin) : to;
  const json = await fetchJson(
    url('/v1/routing', {
      waypoints: `${origin.lat},${origin.lng}|${destination.lat},${destination.lng}`,
      mode: MODE,
      traffic: TRAFFIC,
      format: 'json',
    }),
  );
  const route = json.results?.[0];
  if (!route) throw new MapsError('no_route_found');
  return {
    distanceMeters: Math.round(route.distance ?? 0),
    durationSeconds: Math.round(route.time ?? 0),
    trafficDelaySeconds: null,
    traffic: 'typical_estimate',
  };
}

export async function placesNear(origin, { category, query }, count = 3) {
  const around = { filter: `circle:${origin.lng},${origin.lat},${SEARCH_RADIUS_M}`, bias: `proximity:${origin.lng},${origin.lat}` };
  // Places needs a category; free text (category "other") goes through the geocoder, which only
  // finds named places.
  const found = CATEGORIES[category]
    ? (await fetchJson(url('/v2/places', { categories: CATEGORIES[category], ...around, limit: count }))).features?.map(
        (f) => f.properties,
      )
    : (await fetchJson(url('/v1/geocode/search', { text: query, ...around, limit: count, format: 'json' }))).results;
  const places = (found ?? []).map((p) => ({
    name: p.name ?? null,
    address: p.address_line2 ?? p.formatted ?? '',
    lat: p.lat,
    lng: p.lon,
    openNow: null,
    // Straight-line distance until the matrix below replaces it with the road distance.
    distanceMeters: p.distance ?? null,
    durationSeconds: null,
    byRoad: false,
  }));
  if (!places.length) return [];

  try {
    const matrix = await fetchJson(url('/v1/routematrix', {}), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        mode: MODE,
        traffic: TRAFFIC,
        sources: [{ location: [origin.lng, origin.lat] }],
        targets: places.map((p) => ({ location: [p.lng, p.lat] })),
      }),
    });
    matrix.sources_to_targets?.[0]?.forEach((cell, i) => {
      if (cell?.distance == null) return;
      Object.assign(places[i], { distanceMeters: cell.distance, durationSeconds: Math.round(cell.time), byRoad: true });
    });
  } catch (error) {
    console.warn(`[maps] route matrix failed, using straight-line distances: ${error.message}`);
  }
  return places
    .sort((a, b) => (a.distanceMeters ?? Infinity) - (b.distanceMeters ?? Infinity))
    .map(({ lat, lng, ...place }) => place);
}

// ---- Scanned drop addresses. House-level geocoding of Indian addresses on OpenStreetMap is
// unreliable, and Geoapify's own confidence doesn't show it ("Laxmi Nagar Metro Station" scores 0
// and is exact; "H.No 45, Gali No 3, Mandawali" scores 0.5 and lands on a Gali No 3 5 km away).
// The locality is the reliable part, checked against the PIN code's area (or the rider's
// surroundings). A building or landmark counts as found only when it agrees with both; otherwise
// the locality is used, marked approximate. Streets never count as found: every colony has a "Gali No 3".

const PRECISE_TYPES = new Set(['building', 'amenity']);
// OSM tags villages and small towns as "city".
const AREA_TYPES = new Set(['suburb', 'district', 'city', 'county']);
const PIN_RADIUS_KM = 6;
const RIDER_RADIUS_KM = 20; // deliveries are local
const SAME_PLACE_KM = 1.5;
const IN_LOCALITY_KM = 2;
const SAME_AREA_KM = 3;

async function search(params, bias) {
  const json = await fetchJson(
    url('/v1/geocode/search', {
      ...params,
      filter: 'countrycode:in',
      ...(bias && { bias: `proximity:${bias.lng},${bias.lat}` }),
      limit: 5,
      format: 'json',
    }),
  );
  return (json.results ?? []).map((r) => ({
    lat: r.lat,
    lng: r.lon,
    type: r.result_type,
    match: r.rank?.match_type,
    // A locality's own name, else the suburb; tehsils are administrative, riders don't use them.
    area: [AREA_TYPES.has(r.result_type) ? r.name : null, r.suburb, r.district].find((a) => a && !/tehsil/i.test(a)) ?? null,
    label: r.formatted ?? '',
  }));
}

const located = new Map();

export async function geocodeAddress(address, { area, near }) {
  const key = `${address}|${area ?? ''}|${near ? `${near.lat.toFixed(2)},${near.lng.toFixed(2)}` : ''}`;
  if (located.has(key)) return located.get(key);

  // Three lookups at once: the PIN code's area, the full address and the locality. A slow one (an
  // unbiased "Gali No 3" search can take over 5 s) costs that lookup, not the answer; it's an
  // error only when neither the address nor the locality search answered.
  const pin = pinCodeOf(address);
  const settled = await Promise.allSettled([
    pin ? search({ postcode: pin }) : [],
    search({ text: address }, near),
    area ? search({ text: pin ? `${area}, ${pin}` : area }, near) : [],
  ]);
  const [pinHits, hits, areaHits] = settled.map((s) => (s.status === 'fulfilled' ? s.value : []));
  if (settled[1].status === 'rejected' && !areaHits.length) throw settled[1].reason;

  const anchor = pinHits[0] ?? near;
  const radius = pinHits[0] ? PIN_RADIUS_KM : RIDER_RADIUS_KM;
  const inArea = (p) => !anchor || distanceKm(p, anchor) <= radius;
  const point = (p, status) => ({ status, lat: p.lat, lng: p.lng, area: p.area, label: p.label });

  // The locality, preferring places that carry its name (a Devanagari name won't match; then all).
  const localities = [...areaHits, ...hits].filter((p) => AREA_TYPES.has(p.type) && inArea(p));
  const named = area ? localities.filter((p) => p.label.toLowerCase().includes(area.toLowerCase())) : [];
  const places = named.length ? named : localities;
  const locality = places.length && places.every((p) => distanceKm(p, places[0]) <= SAME_AREA_KM) ? places[0] : null;

  const precise = hits.find((h) => PRECISE_TYPES.has(h.type) && inArea(h) && (h === hits[0] || locality));
  const exact =
    precise &&
    (precise.match === 'full_match' || precise.match === 'match_by_building') &&
    (!locality || distanceKm(precise, locality) <= IN_LOCALITY_KM) &&
    !hits.some((h) => h.type === precise.type && inArea(h) && distanceKm(h, precise) > SAME_PLACE_KM);

  let result;
  if (exact) result = point(precise, 'found');
  else if (locality) result = point(locality, 'approximate');
  else if (places.length) result = { status: 'ambiguous' };
  else result = { status: 'not_found' };

  if (located.size > 100) located.clear();
  if (settled.every((s) => s.status === 'fulfilled')) located.set(key, result);
  return result;
}
