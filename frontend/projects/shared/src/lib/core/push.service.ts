import { Injectable, inject, signal } from '@angular/core';
import { getMessaging, getToken, isSupported, onMessage, deleteToken } from 'firebase/messaging';
import { ApiService } from './api.service';
import { AuthService } from './auth.service';
import { LUCENA_CONFIG } from './config';
import { FirebaseService } from './firebase.service';
import { ToastService } from './toast.service';

export type PushState = 'not-configured' | 'unsupported' | 'denied' | 'off' | 'on' | 'working';

const SW_URL = '/firebase-messaging-sw.js';
const SW_SCOPE = '/firebase-cloud-messaging-push-scope';
const TOKEN_KEY = 'lucena.push.tokenId';

/**
 * Web Push via Firebase Cloud Messaging. Só funciona com a chave pública VAPID configurada
 * (LucenaConfig.fcmVapidKey) e um projeto Firebase real — o emulador não entrega push.
 * Sem a chave, o estado é "not-configured" e a interface mostra a integração como pendente.
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly config = inject(LUCENA_CONFIG);
  private readonly fb = inject(FirebaseService);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly state = signal<PushState>('off');
  private listening = false;

  constructor() {
    inject(AuthService).onBeforeLogout(() => this.disable());
  }

  async refresh(): Promise<void> {
    if (!this.config.fcmVapidKey || this.config.useEmulators) return this.state.set('not-configured');
    if (!('Notification' in window) || !('serviceWorker' in navigator) || !(await isSupported().catch(() => false))) {
      return this.state.set('unsupported');
    }
    if (Notification.permission === 'denied') return this.state.set('denied');
    const has = Notification.permission === 'granted' && !!localStorage.getItem(TOKEN_KEY);
    this.state.set(has ? 'on' : 'off');
    if (has) this.listenForeground();
  }

  async enable(): Promise<void> {
    if (this.state() === 'not-configured' || this.state() === 'unsupported') return;
    this.state.set('working');
    try {
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        this.state.set(permission === 'denied' ? 'denied' : 'off');
        return;
      }
      const registration = await navigator.serviceWorker.register(SW_URL, { scope: SW_SCOPE });
      const token = await getToken(getMessaging(this.fb.app), {
        vapidKey: this.config.fcmVapidKey, serviceWorkerRegistration: registration,
      });
      await this.api.post('/me/push-tokens', { token, platform: 'web' });
      localStorage.setItem(TOKEN_KEY, await sha256Hex(token));
      this.state.set('on');
      this.listenForeground();
    } catch {
      this.toast.error('Não foi possível ativar as notificações neste navegador.');
      this.state.set('off');
    }
  }

  /** Remove o token deste dispositivo (também chamado no logout). */
  async disable(): Promise<void> {
    const id = localStorage.getItem(TOKEN_KEY);
    localStorage.removeItem(TOKEN_KEY);
    if (id) await this.api.delete(`/me/push-tokens/${id}`).catch(() => undefined);
    if (this.state() === 'on') {
      await deleteToken(getMessaging(this.fb.app)).catch(() => undefined);
      this.state.set('off');
    }
  }

  /** Com o app aberto o navegador não exibe o push: mostra como aviso na tela. */
  private listenForeground(): void {
    if (this.listening) return;
    this.listening = true;
    onMessage(getMessaging(this.fb.app), (msg) => {
      const n = msg.notification;
      if (n?.title) this.toast.show(n.body ? `${n.title}: ${n.body}` : n.title, 'info');
    });
  }
}

async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest)).map((b) => b.toString(16).padStart(2, '0')).join('');
}
