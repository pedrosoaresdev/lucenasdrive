import { InjectionToken } from '@angular/core';

/** Configuração de cada portal (vem de src/environments). Nenhum segredo aqui: só identificadores públicos. */
export interface LucenaConfig {
  portal: 'passenger' | 'driver' | 'admin';
  apiBaseUrl: string;
  firebase: {
    apiKey: string;
    authDomain: string;
    projectId: string;
    storageBucket: string;
    appId: string;
    messagingSenderId?: string;
  };
  /** Conecta Auth/Firestore/Storage ao Firebase Emulator Suite (desenvolvimento). */
  useEmulators: boolean;
  emulatorHost?: string;
  /** Chave de NAVEGADOR do Google Maps (restrita por domínio e por API). Vazia = integração pendente. */
  mapsBrowserKey: string;
  mapsMapId?: string;
  /**
   * Provedor de mapas: 'google' (exige mapsBrowserKey), 'osm' (OpenStreetMap, gratuito, sem chave) ou
   * 'auto' (padrão): Google quando houver chave; senão OpenStreetMap.
   */
  mapsProvider?: 'auto' | 'google' | 'osm';
  /** Servidor de mapas (tiles) do OpenStreetMap. Padrão: tile.openstreetmap.org (uso moderado; ver política). */
  osmTileUrl?: string;
  /** Busca de endereços sem Google (Photon/Komoot, baseado em OpenStreetMap). */
  osmGeocoderUrl?: string;
  /** Chave pública VAPID do FCM (Web Push). Vazia = push desativado. */
  fcmVapidKey?: string;
}

export const LUCENA_CONFIG = new InjectionToken<LucenaConfig>('LUCENA_CONFIG');
