import { Place } from './models';

/** Resultado da busca de endereços sem Google (Photon, baseado em OpenStreetMap). */
export interface OsmSuggestion { text: string; place: Place; }

interface PhotonFeature {
  geometry: { coordinates: [number, number] };
  properties: Record<string, string | undefined>;
}

/** Monta um endereço legível em português a partir das propriedades do Photon. */
export function photonAddress(p: Record<string, string | undefined>): string {
  const street = p['street'] ?? (p['type'] === 'street' ? p['name'] : undefined);
  const first = [street, p['housenumber']].filter(Boolean).join(', ');
  const name = p['name'] && p['name'] !== street ? p['name'] : undefined;
  const area = p['district'] ?? p['locality'];
  const city = p['city'] ?? p['county'];
  const state = p['state'];
  return [name, first, area, city, state].filter((v, i, a) => !!v && a.indexOf(v) === i).join(' - ');
}

/** Converte a resposta GeoJSON do Photon em sugestões; mantém apenas resultados no Brasil. */
export function parsePhoton(json: { features?: PhotonFeature[] }): OsmSuggestion[] {
  const out: OsmSuggestion[] = [];
  for (const f of json.features ?? []) {
    const props = f.properties ?? {};
    if (props['countrycode'] && props['countrycode'].toUpperCase() !== 'BR') continue;
    const [lng, lat] = f.geometry?.coordinates ?? [];
    if (typeof lat !== 'number' || typeof lng !== 'number') continue;
    const text = photonAddress(props);
    if (!text || out.some((o) => o.text === text)) continue;
    out.push({ text, place: { lat, lng, address: text, placeId: props['osm_id'] ? `osm:${props['osm_type']}${props['osm_id']}` : undefined } });
  }
  return out;
}

/** Decodifica polyline no formato Google/OSRM (precisão 5). */
export function decodePolyline(encoded: string): { lat: number; lng: number }[] {
  const pts: { lat: number; lng: number }[] = [];
  let index = 0, lat = 0, lng = 0;
  const next = (): number => {
    let result = 0, shift = 0, b: number;
    do {
      b = encoded.charCodeAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
    } while (b >= 0x20);
    return result & 1 ? ~(result >> 1) : result >> 1;
  };
  while (index < encoded.length) {
    lat += next();
    lng += next();
    pts.push({ lat: lat / 1e5, lng: lng / 1e5 });
  }
  return pts;
}

let last = 0;
/** Busca no Photon com intervalo mínimo entre chamadas (uso justo do serviço público). */
export async function photonSearch(baseUrl: string, q: string, bias?: { lat: number; lng: number } | null,
                                   signal?: AbortSignal): Promise<OsmSuggestion[]> {
  const wait = Math.max(0, last + 350 - Date.now());
  if (wait) await new Promise((r) => setTimeout(r, wait));
  last = Date.now();
  const params = new URLSearchParams({ q, limit: '8' });
  if (bias) { params.set('lat', String(bias.lat)); params.set('lon', String(bias.lng)); }
  const res = await fetch(`${baseUrl}/api/?${params}`, { signal });
  if (!res.ok) throw new Error('GEOCODER_' + res.status);
  return parsePhoton(await res.json()).slice(0, 6);
}

export async function photonReverse(baseUrl: string, p: { lat: number; lng: number }): Promise<string | null> {
  const res = await fetch(`${baseUrl}/reverse?lat=${p.lat}&lon=${p.lng}&limit=1`);
  if (!res.ok) return null;
  return parsePhoton(await res.json())[0]?.text ?? null;
}
