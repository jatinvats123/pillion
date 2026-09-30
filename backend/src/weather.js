import { config } from './config.js';

// Weather at the rider's position for the getWeather tool: OpenWeather when OPENWEATHER_API_KEY is
// set, Open-Meteo (free, no key) if that fails or there's no key. One answer per ~1 km and 10 min.

const TIMEOUT_MS = 3000;
const CACHE_MS = 10 * 60 * 1000;
const cache = new Map();

export class WeatherError extends Error {}

export async function weatherAt({ lat, lng }) {
  const key = `${lat.toFixed(2)},${lng.toFixed(2)}`;
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < CACHE_MS) return hit.weather;

  let weather = null;
  if (config.weather.openWeatherApiKey) {
    weather = await fromOpenWeather(lat, lng).catch((error) => {
      console.warn(`[weather] OpenWeather failed (${error.message}), using Open-Meteo`);
      return null;
    });
  }
  weather ??= await fromOpenMeteo(lat, lng).catch((error) => {
    throw new WeatherError(`weather_unavailable: ${error.message}`);
  });
  cache.set(key, { at: Date.now(), weather });
  return weather;
}

async function getJson(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

async function fromOpenWeather(lat, lng) {
  const query = `lat=${lat}&lon=${lng}&units=metric&appid=${config.weather.openWeatherApiKey}`;
  const [now, next] = await Promise.all([
    getJson(`https://api.openweathermap.org/data/2.5/weather?${query}`),
    // The next two 3-hour blocks: chance of rain soon.
    getJson(`https://api.openweathermap.org/data/2.5/forecast?${query}&cnt=2`),
  ]);
  const rainChance = Math.max(0, ...(next.list ?? []).map((block) => block.pop ?? 0));
  return {
    temperature_c: Math.round(now.main.temp),
    feels_like_c: Math.round(now.main.feels_like),
    humidity_percent: now.main.humidity,
    conditions: now.weather?.[0]?.description ?? null,
    raining_now: Boolean(now.rain?.['1h']) || /rain|drizzle|thunder/i.test(now.weather?.[0]?.main ?? ''),
    rain_chance_next_hours_percent: Math.round(rainChance * 100),
    source: 'openweather',
  };
}

async function fromOpenMeteo(lat, lng) {
  const data = await getJson(
    `https://api.open-meteo.com/v1/forecast?latitude=${lat}&longitude=${lng}` +
      '&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code' +
      '&hourly=precipitation_probability&forecast_hours=3&timezone=auto',
  );
  const now = data.current;
  return {
    temperature_c: Math.round(now.temperature_2m),
    feels_like_c: Math.round(now.apparent_temperature),
    humidity_percent: now.relative_humidity_2m,
    conditions: WMO[now.weather_code] ?? null,
    raining_now: now.precipitation > 0,
    rain_chance_next_hours_percent: Math.max(0, ...(data.hourly?.precipitation_probability ?? [])),
    source: 'open-meteo',
  };
}

// Open-Meteo's WMO weather codes, in words the LLM can say.
const WMO = {
  0: 'clear sky', 1: 'mostly clear', 2: 'partly cloudy', 3: 'overcast', 45: 'fog', 48: 'fog',
  51: 'light drizzle', 53: 'drizzle', 55: 'heavy drizzle', 61: 'light rain', 63: 'rain', 65: 'heavy rain',
  80: 'rain showers', 81: 'rain showers', 82: 'heavy rain showers', 95: 'thunderstorm', 96: 'thunderstorm with hail',
  99: 'thunderstorm with hail',
};
