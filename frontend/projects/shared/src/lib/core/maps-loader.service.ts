import { Injectable, inject, signal } from '@angular/core';
import { LUCENA_CONFIG } from './config';

/**
 * Carrega a Google Maps JavaScript API sob demanda com a chave de NAVEGADOR (restrita por
 * referenciador HTTP e por API no Google Cloud). Sem chave → estado "integração pendente".
 */
@Injectable({ providedIn: 'root' })
export class MapsLoaderService {
  private readonly config = inject(LUCENA_CONFIG);
  private loading?: Promise<typeof google.maps>;
  readonly status = signal<'idle' | 'loading' | 'ready' | 'unconfigured' | 'error'>(
    this.config.mapsBrowserKey ? 'idle' : 'unconfigured',
  );

  get configured(): boolean {
    return !!this.config.mapsBrowserKey;
  }

  get mapId(): string | undefined {
    return this.config.mapsMapId || undefined;
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
