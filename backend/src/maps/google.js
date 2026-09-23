import { config } from '../config.js';
import { fetchJson, MapsError } from './common.js';

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

export async function routeTo(origin, destinationAddress) {
  const json = await post(
    'https://routes.googleapis.com/directions/v2:computeRoutes',
    'routes.distanceMeters,routes.duration,routes.staticDuration',
    {
      origin: { location: { latLng: latLng(origin) } },
      destination: { address: destinationAddress },
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
