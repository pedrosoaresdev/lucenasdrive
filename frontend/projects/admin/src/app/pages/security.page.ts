import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ApiService, DateTimePipe } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Session { id: string; createdAt: string; expiresAt: string; mfaVerifiedAt: string; ip: string | null; userAgent: string | null; }

/** Sessões administrativas ativas (revogáveis) e códigos de recuperação. */
@Component({
  selector: 'admin-security',
  imports: [DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Minha segurança</h1>
    <section class="card stack">
      <h2>Sessões ativas</h2>
      @for (s of sessions(); track s.id) {
        <div class="row between item"><span class="small">Início {{ s.createdAt | dt }} · expira {{ s.expiresAt | dt }} · IP {{ s.ip ?? '—' }}<br />
          <span class="muted">{{ s.userAgent }}</span></span>
          <button class="btn ghost sm" (click)="revoke(s)">Revogar</button></div>
      }
    </section>
    <section class="card stack block">
      <h2>Códigos de recuperação</h2>
      <p class="small muted">Gerar novos códigos invalida os anteriores. Exige confirmação do segundo fator.</p>
      <button class="btn ghost" (click)="regenerate()">Gerar novos códigos</button>
      @if (codes().length) { <ol class="mono codes">@for (c of codes(); track c) { <li>{{ c }}</li> }</ol>
        <p class="alert warn small">Guarde agora: eles não serão exibidos novamente.</p> }
    </section>
  `,
  styles: `.item { padding: 10px 0; border-top: 1px solid var(--ld-border); } .block { margin-top: 16px; } .codes { columns: 2; }`,
})
export default class SecurityPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  protected readonly sessions = signal<Session[]>([]);
  protected readonly codes = signal<string[]>([]);

  async ngOnInit(): Promise<void> {
    this.sessions.set(await this.api.get<Session[]>('/admin/auth/sessions'));
  }

  protected async revoke(s: Session): Promise<void> {
    if (await this.a.exec(() => this.api.post(`/admin/auth/sessions/${s.id}/revoke`), 'Sessão revogada.') !== null) await this.ngOnInit();
  }

  protected async regenerate(): Promise<void> {
    const r = await this.a.exec(() => this.api.post<{ recoveryCodes: string[] }>('/admin/auth/recovery-codes'));
    if (r) this.codes.set(r.recoveryCodes);
  }
}
