import { config } from '../config.js';
import * as geoapify from './geoapify.js';
import * as google from './google.js';

export { MapsError, PLACE_CATEGORIES } from './common.js';

// One maps interface, two providers (MAPS_PROVIDER). Both take {lat, lng} origins and return:
//   routeTo        → { distanceMeters, durationSeconds, trafficDelaySeconds | null, traffic: 'live' | 'typical_estimate' }
//   placesNear     → [{ name | null, address, openNow | null, distanceMeters, durationSeconds | null, byRoad }]
//   geocodeAddress → { status: 'found' | 'approximate' | 'ambiguous' | 'not_found', lat?, lng?, area?, label? }
const providers = { geoapify, google };

/** `destination` is an address, or a {lat, lng} point from geocodeAddress. */
export const routeTo = (origin, destination) => providers[config.maps.provider].routeTo(origin, destination);

/** `place` is { category: one of PLACE_CATEGORIES, query: what the rider asked for, in English }. */
export const placesNear = (origin, place) => providers[config.maps.provider].placesNear(origin, place);

/**
 * A scanned drop address → a point, only when the provider's answer agrees with the address's PIN
 * code or the rider's surroundings. `area` is the locality read from the order; `near` the rider's
 * position, if known.
 */
export const geocodeAddress = (address, { area = null, near = null } = {}) =>
  providers[config.maps.provider].geocodeAddress(address, { area, near });
