import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from '@lucena/shared';

/**
 * Compartilhamento de localização do motorista, somente enquanto online e com permissão explícita
 * do navegador. Frequência adaptativa: ~4 s em corrida, ~15 s disponível. O servidor também limita.
 */
@Injectable({ providedIn: 'root' })
export class DriverLocationService {
  private readonly api = inject(ApiService);
  readonly position = signal<GeolocationPosition | null>(null);
  readonly error = signal('');
  readonly sharing = signal(false);
  private watchId: number | null = null;
  private lastSent = 0;
  private inRide = false;

  setInRide(v: boolean): void {
    this.inRide = v;
  }

  /** Obtém a posição atual (pedido de permissão ao usuário). */
  current(): Promise<GeolocationPosition> {
    return new Promise((resolve, reject) => {
      if (!('geolocation' in navigator)) {
        reject(new Error('Geolocalização indisponível neste dispositivo.'));
        return;
      }
      navigator.geolocation.getCurrentPosition(resolve, (e) => reject(new Error(
        e.code === e.PERMISSION_DENIED ? 'Permita o acesso à localização para ficar online.' : 'Não foi possível obter sua localização.')),
      { enableHighAccuracy: true, timeout: 15000 });
    });
  }

  start(): void {
    if (this.watchId !== null || !('geolocation' in navigator)) return;
    this.sharing.set(true);
    this.watchId = navigator.geolocation.watchPosition((pos) => {
      this.position.set(pos);
      this.error.set('');
      const interval = this.inRide ? 4000 : 15000;
      if (Date.now() - this.lastSent >= interval) {
        this.lastSent = Date.now();
        void this.api.post('/driver/location', {
          lat: pos.coords.latitude, lng: pos.coords.longitude,
          heading: pos.coords.heading ?? null, speedMps: pos.coords.speed ?? null, accuracyM: pos.coords.accuracy,
        }).catch(() => undefined);
      }
    }, () => this.error.set('Sinal de localização perdido. Verifique a permissão e o GPS.'),
    { enableHighAccuracy: true, maximumAge: 3000, timeout: 20000 });
  }

  stop(): void {
    if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
    this.sharing.set(false);
  }
}
