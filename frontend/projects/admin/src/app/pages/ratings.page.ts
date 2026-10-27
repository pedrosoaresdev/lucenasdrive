import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, DateTimePipe } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Rating { id: string; rideId: string; raterId: string; rateeId: string; stars: number; comment: string | null; tags: string[];
  status: string; reported: boolean; moderation: { action: string; byUserId: string; reason: string; at: string }[]; createdAt: string; }
interface Stats { count: number; average: number; distribution: Record<string, number>; reported: number; }

/** Moderação justificada: nenhuma nota é alterada silenciosamente; avaliações negativas legítimas permanecem. */
@Component({
  selector: 'admin-ratings',
  imports: [FormsModule, RouterLink, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Avaliações</h1>
    @if (stats(); as s) {
      <section class="card row stats">
        <div class="kpi"><span class="label">Média da plataforma</span><span class="value">★ {{ s.count ? s.average.toFixed(2) : '—' }}</span></div>
        <div class="kpi"><span class="label">Avaliações</span><span class="value">{{ s.count }}</span></div>
        <div class="kpi"><span class="label">Contestadas/denunciadas</span><span class="value">{{ s.reported }}</span></div>
        <div class="bars">@for (n of ['5','4','3','2','1']; track n) {
          <div class="bar"><span>{{ n }}★</span><div class="track"><div class="fill" [style.width.%]="s.count ? (s.distribution[n] / s.count) * 100 : 0"></div></div>
            <span class="small mono">{{ s.distribution[n] }}</span></div> }</div>
      </section>
    }
    <div class="row filters">
      <select class="input" [(ngModel)]="status" (change)="load()" aria-label="Status"><option value="">Todos</option>
        <option value="VISIBLE">Visíveis</option><option value="COMMENT_HIDDEN">Comentário oculto</option><option value="INVALIDATED">Invalidadas</option></select>
      <label class="check"><input type="checkbox" [(ngModel)]="reported" (change)="load()" /> Somente contestadas</label>
    </div>
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Data</th><th>Nota</th><th>Comentário</th><th>Motorista</th><th>Corrida</th><th>Status</th><th>Ações</th></tr></thead>
      <tbody>@for (r of list(); track r.id) {
        <tr><td>{{ r.createdAt | dt }}</td><td class="stars">{{ '★'.repeat(r.stars) }}</td><td class="wrap">{{ r.comment }}
            @if (r.moderation.length) { <br /><span class="small muted">{{ r.moderation.at(-1)!.action }}: {{ r.moderation.at(-1)!.reason }}</span> }</td>
          <td><a [routerLink]="['/motoristas', r.rateeId]" class="mono small">{{ r.rateeId }}</a></td>
          <td><a [routerLink]="['/corridas', r.rideId]" class="mono small">ver</a></td>
          <td><span class="badge">{{ r.status }}</span> @if (r.reported) { <span class="badge warn">contestada</span> }</td>
          <td class="row">
            @if (r.status === 'VISIBLE' && r.comment) { <button class="btn ghost sm" (click)="act(r, 'HIDE_COMMENT')">Ocultar comentário</button> }
            @if (r.status !== 'INVALIDATED') { <button class="btn ghost sm" (click)="act(r, 'INVALIDATE')">Invalidar nota</button> }
            @if (r.status !== 'VISIBLE') { <button class="btn ghost sm" (click)="act(r, 'RESTORE')">Restaurar</button> }
            @if (r.reported) { <button class="btn ghost sm" (click)="act(r, 'DISMISS_REPORT')">Encerrar contestação</button> }</td></tr>
      } @empty { <tr><td colspan="7" class="muted">Nenhuma avaliação.</td></tr> }</tbody>
    </table></div>
  `,
  styles: `.stats { gap: 32px; margin: 12px 0; align-items: flex-start; } .bars { flex: 1; min-width: 220px; }
    .bar { display: grid; grid-template-columns: 32px 1fr 40px; gap: 8px; align-items: center; }
    .track { height: 8px; background: var(--ld-surface-2); border-radius: 4px; overflow: hidden; } .fill { height: 100%; background: var(--ld-star); }
    .filters { margin: 12px 0; } .filters .input { max-width: 220px; } .stars { color: var(--ld-star); } .wrap { white-space: normal; max-width: 320px; }`,
})
export default class RatingsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  protected readonly stats = signal<Stats | null>(null);
  protected readonly list = signal<Rating[]>([]);
  protected status = '';
  protected reported = false;
  private readonly titles: Record<string, string> = { HIDE_COMMENT: 'Ocultar comentário (a nota continua valendo)',
    INVALIDATE: 'Invalidar avaliação (remove da média)', RESTORE: 'Restaurar avaliação', DISMISS_REPORT: 'Encerrar contestação' };

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected async load(): Promise<void> {
    this.stats.set(await this.api.get<Stats>('/admin/ratings/stats'));
    this.list.set(await this.api.get<Rating[]>('/admin/ratings', { status: this.status, reported: this.reported || null, limit: 100 }));
  }

  protected async act(r: Rating, action: string): Promise<void> {
    const ok = await this.a.withReason(this.titles[action], (reason) => this.api.post(`/admin/ratings/${r.id}/moderation`, { action, reason }),
      { ok: 'Moderação registrada.', danger: action === 'INVALIDATE' });
    if (ok) await this.load();
  }
}
