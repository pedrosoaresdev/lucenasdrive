import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';

export type PresenceKind = 'PASSENGER' | 'DRIVER';
interface ConsentView { enabled: boolean; version: string | null; changedAt: string | null; currentVersion: string; }

const DISMISS_KEY = 'lucena.locationPrompt.dismissedAt';
const SEND_EVERY_MS = 20_000;

/**
 * Compartilhamento OPCIONAL da localização com a equipe (painel "O olho de Deus" do admin).
 * Só envia posições se o usuário ativou e o navegador permitiu; desativar apaga a posição no servidor.
 */
@Injectable({ providedIn: 'root' })
export class PresenceService {
  private readonly api = inject(ApiService);
  readonly enabled = signal(false);
  readonly askNeeded = signal(false);
  readonly browserDenied = signal(false);
  private kind: PresenceKind | null = null;
  private watchId: number | null = null;
  private lastSent = 0;

  constructor() {
    inject(AuthService).onBeforeLogout(async () => this.stopWatching());
  }

  /** Chamado pelo shell do app após o login. Não pede permissão sozinho: só mostra o convite. */
  async start(kind: PresenceKind): Promise<void> {
    this.kind = kind;
    const c = await this.api.get<ConsentView>('/me/location-sharing').catch(() => null);
    if (!c) return;
    this.enabled.set(c.enabled);
    if (c.enabled) {
      this.watch();
    } else if (!c.changedAt && !this.recentlyDismissed()) {
      this.askNeeded.set(true);
    }
  }

  async enable(): Promise<void> {
    this.askNeeded.set(false);
    await this.api.put('/me/location-sharing', { enabled: true });
    this.enabled.set(true);
    this.watch();
  }

  async disable(): Promise<void> {
    this.askNeeded.set(false);
    this.stopWatching();
    await this.api.put('/me/location-sharing', { enabled: false });
    this.enabled.set(false);
  }

  /** "Agora não": não pergunta de novo por 7 dias. */
  dismiss(): void {
    this.askNeeded.set(false);
    try { localStorage.setItem(DISMISS_KEY, String(Date.now())); } catch { /* armazenamento indisponível */ }
  }

  private recentlyDismissed(): boolean {
    try {
      const at = Number(localStorage.getItem(DISMISS_KEY) ?? 0);
      return Date.now() - at < 7 * 24 * 3600_000;
    } catch {
      return false;
    }
  }

  private watch(): void {
    if (this.watchId !== null || !this.kind || !('geolocation' in navigator)) return;
    this.watchId = navigator.geolocation.watchPosition(
      (p) => {
        this.browserDenied.set(false);
        const now = Date.now();
        if (now - this.lastSent < SEND_EVERY_MS) return;
        this.lastSent = now;
        void this.api.post('/me/presence', {
          app: this.kind, lat: p.coords.latitude, lng: p.coords.longitude, accuracyM: p.coords.accuracy,
        }).catch(() => undefined);
      },
      (err) => {
        if (err.code === err.PERMISSION_DENIED) {
          this.browserDenied.set(true);
          this.stopWatching();
        }
      },
      { enableHighAccuracy: false, maximumAge: 15_000, timeout: 30_000 },
    );
  }

  private stopWatching(): void {
    if (this.watchId !== null) navigator.geolocation.clearWatch(this.watchId);
    this.watchId = null;
  }
}
