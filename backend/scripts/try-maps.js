// Calls the maps provider (MAPS_PROVIDER) the way the ETA and nearby tools do, from a given position.
//   node scripts/try-maps.js route 28.6304,77.2773 "Laxmi Nagar Metro Station, Delhi"
//   node scripts/try-maps.js places 28.6304,77.2773 fuel "petrol pump"
import { config } from '../src/config.js';
import { PLACE_CATEGORIES, placesNear, routeTo } from '../src/maps/index.js';

const [kind, at, ...rest] = process.argv.slice(2);
const [lat, lng] = String(at ?? '').split(',').map(Number);
const usage = `Usage: node scripts/try-maps.js route <lat>,<lng> "<address>"
       node scripts/try-maps.js places <lat>,<lng> <${PLACE_CATEGORIES.join('|')}> ["<what, in English>"]`;
if (!['route', 'places'].includes(kind) || !Number.isFinite(lat) || !Number.isFinite(lng) || !rest.length) {
  console.log(usage);
  process.exit(1);
}

const origin = { lat, lng };
const startedAt = Date.now();
const result =
  kind === 'route'
    ? await routeTo(origin, rest[0])
    : await placesNear(origin, { category: rest[0], query: rest[1] ?? '' });
console.log(`${config.maps.provider}: ${Date.now() - startedAt} ms`);
console.log(JSON.stringify(result, null, 2));
