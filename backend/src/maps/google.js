import { config } from '../config.js';
import { distanceKm, fetchJson, MapsError } from './common.js';

// Google Maps Platform (MAPS_PROVIDER=google): Routes API + Places API (New), both with the
// TWO_WHEELER travel mode (beta; well covered in India). Routes use live traffic.

const post = (url, fieldMask, body) => {
  if (!config.maps.googleApiKey) throw new MapsError('maps_not_configured');
  return fetchJson(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': config.maps.googleApiKey,
      'X-Goog-FieldMask': fieldMask,
    },
    body: JSON.stringify(body),
  });
};

const seconds = (duration) => Number.parseInt(String(duration ?? '').replace(/s$/, ''), 10) || 0;
const latLng = ({ lat, lng }) => ({ latitude: lat, longitude: lng });

const SEARCH_TEXT = {
  fuel: 'petrol pump',
  ev_charging: 'EV charging station',
  atm: 'ATM',
  toilet: 'public toilet',
  food: 'food',
  tea: 'tea stall',
  bike_repair: 'bike puncture repair',
  hospital: 'hospital',
  pharmacy: 'pharmacy',
  police: 'police station',
  parking: 'two wheeler parking',
};

export async function routeTo(origin, to) {
  const json = await post(
    'https://routes.googleapis.com/directions/v2:computeRoutes',
    'routes.distanceMeters,routes.duration,routes.staticDuration',
    {
      origin: { location: { latLng: latLng(origin) } },
      destination: typeof to === 'string' ? { address: to } : { location: { latLng: latLng(to) } },
      travelMode: 'TWO_WHEELER',
      routingPreference: 'TRAFFIC_AWARE',
      regionCode: 'IN',
      units: 'METRIC',
    },
  );
  const route = json.routes?.[0];
  if (!route) throw new MapsError('no_route_found');
  const duration = seconds(route.duration);
  return {
    distanceMeters: route.distanceMeters ?? 0,
    durationSeconds: duration,
    // Same route without current traffic; the difference is today's traffic delay.
    trafficDelaySeconds: Math.max(0, duration - seconds(route.staticDuration)),
    traffic: 'live',
  };
}

export async function placesNear(origin, { category, query }, count = 3) {
  const json = await post(
    'https://places.googleapis.com/v1/places:searchText',
    'places.displayName,places.shortFormattedAddress,places.currentOpeningHours.openNow,routingSummaries',
    {
      textQuery: query || SEARCH_TEXT[category] || category,
      pageSize: count,
      rankPreference: 'DISTANCE',
      locationBias: { circle: { center: latLng(origin), radius: 5000 } },
      regionCode: 'IN',
      routingParameters: { origin: latLng(origin), travelMode: 'TWO_WHEELER' },
    },
  );
  return (json.places ?? []).map((place, i) => {
    const leg = json.routingSummaries?.[i]?.legs?.[0];
    return {
      name: place.displayName?.text ?? null,
      address: place.shortFormattedAddress ?? '',
      openNow: place.currentOpeningHours?.openNow ?? null,
      distanceMeters: leg?.distanceMeters ?? null,
      durationSeconds: leg ? seconds(leg.duration) : null,
      byRoad: Boolean(leg),
    };
  });
}

// Scanned drop addresses via the Geocoding API (it must be enabled on the key). Not yet run against
// the live API: Google billing verification is pending.
export async function geocodeAddress(address, { area, near }) {
  if (!config.maps.googleApiKey) throw new MapsError('maps_not_configured');
  const json = await fetchJson(
    `https://maps.googleapis.com/maps/api/geocode/json?${new URLSearchParams({
      address,
      components: 'country:IN',
      region: 'in',
      key: config.maps.googleApiKey,
    })}`,
  );
  if (json.status === 'ZERO_RESULTS') return { status: 'not_found' };
  if (json.status !== 'OK') throw new MapsError(`maps_geocode_${json.status}`);
  const hits = json.results
    .map((r) => ({ ...r, lat: r.geometry.location.lat, lng: r.geometry.location.lng }))
    .filter((r) => !near || distanceKm(r, near) <= 20);
  const top = hits[0];
  if (!top) return { status: 'not_found' };
  if (hits.some((h) => distanceKm(h, top) > 3)) return { status: 'ambiguous' };
  const exact = !top.partial_match && ['ROOFTOP', 'RANGE_INTERPOLATED'].includes(top.geometry.location_type);
  const locality = top.address_components.find((c) => c.types.includes('sublocality_level_1') || c.types.includes('sublocality'));
  return {
    status: exact ? 'found' : 'approximate',
    lat: top.lat,
    lng: top.lng,
    area: locality?.long_name ?? area ?? null,
    label: top.formatted_address,
  };
}
