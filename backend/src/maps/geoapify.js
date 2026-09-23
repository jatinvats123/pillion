import { config } from '../config.js';
import { fetchJson, MapsError } from './common.js';

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

export async function routeTo(origin, destinationAddress) {
  const destination = await geocode(destinationAddress, origin);
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
