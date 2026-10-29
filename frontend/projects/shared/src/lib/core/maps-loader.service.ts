import { Injectable, inject, signal } from '@angular/core';
import { LUCENA_CONFIG } from './config';

export type MapsProvider = 'google' | 'osm';

/**
 * Carrega o provedor de mapas sob demanda:
 * - Google Maps JavaScript API, com a chave de NAVEGADOR (restrita por referenciador e por API);
 * - OpenStreetMap via Leaflet (gratuito, sem chave), usado quando não há chave do Google ou quando configurado.
 */
@Injectable({ providedIn: 'root' })
export class MapsLoaderService {
  private readonly config = inject(LUCENA_CONFIG);
  private loading?: Promise<typeof google.maps>;
  private leaflet?: Promise<typeof import('leaflet')>;
  readonly provider: MapsProvider = this.config.mapsProvider === 'osm'
    || (this.config.mapsProvider !== 'google' && !this.config.mapsBrowserKey) ? 'osm' : 'google';
  readonly status = signal<'idle' | 'loading' | 'ready' | 'unconfigured' | 'error'>(
    this.provider === 'osm' || this.config.mapsBrowserKey ? 'idle' : 'unconfigured',
  );

  /** Google Maps disponível (chave configurada e provedor Google). */
  get configured(): boolean {
    return this.provider === 'google' && !!this.config.mapsBrowserKey;
  }

  /** Há algum mapa interativo disponível (Google ou OpenStreetMap). */
  get hasMap(): boolean {
    return this.provider === 'osm' || this.configured;
  }

  get mapId(): string | undefined {
    return this.config.mapsMapId || undefined;
  }

  get osmTileUrl(): string {
    return this.config.osmTileUrl || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
  }

  get osmGeocoderUrl(): string {
    return (this.config.osmGeocoderUrl || 'https://photon.komoot.io').replace(/\/$/, '');
  }

  loadLeaflet(): Promise<typeof import('leaflet')> {
    this.leaflet ??= import('leaflet').then((m) => {
      this.status.set('ready');
      return (m as unknown as { default?: typeof import('leaflet') }).default ?? m;
    }).catch((e) => {
      this.status.set('error');
      this.leaflet = undefined;
      throw e;
    });
    return this.leaflet;
  }

  load(): Promise<typeof google.maps> {
    if (!this.configured) return Promise.reject(new Error('MAPS_NOT_CONFIGURED'));
    if (this.loading) return this.loading;
    this.status.set('loading');
    this.loading = new Promise<typeof google.maps>((resolve, reject) => {
      const cb = '__lucenaMapsReady';
      (window as unknown as Record<string, unknown>)[cb] = () => {
        this.status.set('ready');
        resolve(google.maps);
      };
      const s = document.createElement('script');
      s.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(this.config.mapsBrowserKey)}`
        + `&libraries=places,geometry,marker&language=pt-BR&region=BR&loading=async&callback=${cb}`;
      s.async = true;
      s.onerror = () => {
        this.status.set('error');
        this.loading = undefined;
        reject(new Error('MAPS_LOAD_FAILED'));
      };
      document.head.appendChild(s);
    });
    return this.loading;
  }
}
