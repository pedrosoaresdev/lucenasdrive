import { describe, expect, it } from 'vitest';
import { decodePolyline, parsePhoton, photonAddress } from './osm-geocoder';

describe('OpenStreetMap (Photon / polyline)', () => {
  it('decodifica polyline no formato do Google/OSRM', () => {
    // exemplo oficial da documentação do Google
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@')).toEqual([
      { lat: 38.5, lng: -120.2 }, { lat: 40.7, lng: -120.95 }, { lat: 43.252, lng: -126.453 },
    ]);
  });

  it('monta endereço legível e filtra resultados fora do Brasil', () => {
    const out = parsePhoton({ features: [
      { geometry: { coordinates: [-34.845, -7.1195] }, properties: { countrycode: 'BR', street: 'Avenida Epitácio Pessoa',
        housenumber: '1000', district: 'Tambauzinho', city: 'João Pessoa', state: 'Paraíba', osm_id: '1', osm_type: 'N' } },
      { geometry: { coordinates: [2.35, 48.85] }, properties: { countrycode: 'FR', name: 'Paris' } },
    ] });
    expect(out).toHaveLength(1);
    expect(out[0].text).toBe('Avenida Epitácio Pessoa, 1000 - Tambauzinho - João Pessoa - Paraíba');
    expect(out[0].place).toMatchObject({ lat: -7.1195, lng: -34.845, placeId: 'osm:N1' });
  });

  it('usa o nome do local quando não há rua', () => {
    expect(photonAddress({ name: 'Shopping Manaíra', city: 'João Pessoa', state: 'Paraíba' }))
      .toBe('Shopping Manaíra - João Pessoa - Paraíba');
  });
});
