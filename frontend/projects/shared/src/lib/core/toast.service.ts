import { Injectable, signal } from '@angular/core';

export interface Toast { id: number; kind: 'ok' | 'err' | 'info' | 'warn'; text: string; }

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private seq = 0;

  show(text: string, kind: Toast['kind'] = 'info', ms = 4500): void {
    const t = { id: ++this.seq, kind, text };
    this.toasts.update((list) => [...list, t].slice(-4));
    setTimeout(() => this.dismiss(t.id), ms);
  }

  ok(text: string): void { this.show(text, 'ok'); }
  error(text: string): void { this.show(text, 'err', 6500); }

  dismiss(id: number): void {
    this.toasts.update((list) => list.filter((t) => t.id !== id));
  }
}
