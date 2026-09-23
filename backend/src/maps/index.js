import { config } from '../config.js';
import * as geoapify from './geoapify.js';
import * as google from './google.js';

export { MapsError, PLACE_CATEGORIES } from './common.js';

// One maps interface, two providers (MAPS_PROVIDER). Both take {lat, lng} origins and return:
//   routeTo    → { distanceMeters, durationSeconds, trafficDelaySeconds | null, traffic: 'live' | 'typical_estimate' }
//   placesNear → [{ name | null, address, openNow | null, distanceMeters, durationSeconds | null, byRoad }]
const providers = { geoapify, google };

export const routeTo = (origin, destinationAddress) => providers[config.maps.provider].routeTo(origin, destinationAddress);

/** `place` is { category: one of PLACE_CATEGORIES, query: what the rider asked for, in English }. */
export const placesNear = (origin, place) => providers[config.maps.provider].placesNear(origin, place);
