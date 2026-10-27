import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError, ApiService, AuthService, DateTimePipe, StarRatingComponent, ToastService } from '@lucena/shared';

interface Summary { average: number; count: number; starCounts: Record<string, number>; }
interface Rating { id: string; stars: number; comment: string | null; tags: string[]; status: string; reported: boolean; createdAt: string; }

/** Avaliações recebidas: média, distribuição, comentários e contestação. */
@Component({
  selector: 'app-ratings',
  imports: [FormsModule, DateTimePipe, StarRatingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Minhas avaliações</h1>
      @if (summary(); as s) {
        <section class="card row summary">
          <div class="avg"><span class="value">{{ s.count ? s.average.toFixed(2) : '—' }}</span>
            <ld-star-rating [value]="round(s.average)" [readonly]="true" label="Média" style="--size: 24px" />
            <span class="small muted">{{ s.count }} avaliações</span></div>
          <div class="bars grow">
            @for (n of [5, 4, 3, 2, 1]; track n) {
              <div class="bar"><span>{{ n }}★</span><div class="track"><div class="fill" [style.width.%]="pct(s, n)"></div></div>
                <span class="small muted">{{ s.starCounts[n] ?? 0 }}</span></div>
            }
          </div>
        </section>
      }
      @for (r of ratings(); track r.id) {
        <div class="card flat stack">
          <div class="row between"><span class="stars">{{ '★'.repeat(r.stars) }}{{ '☆'.repeat(5 - r.stars) }}</span>
            <span class="small muted">{{ r.createdAt | dt }}</span></div>
          @if (r.comment) { <p>{{ r.comment }}</p> }
          @if (r.status === 'COMMENT_HIDDEN') { <p class="small muted">Comentário removido pela moderação.</p> }
          @if (r.status === 'INVALIDATED') { <span class="badge">Desconsiderada na média</span> }
          @if (r.reported) { <span class="badge warn">Contestação em análise</span> }
          @else if (disputing() === r.id) {
            <textarea class="input" [(ngModel)]="reason" placeholder="Explique por que a avaliação deve ser revista (mín. 10 caracteres)"></textarea>
            <div class="row"><button class="btn primary sm" (click)="dispute(r)" [disabled]="reason.trim().length < 10">Enviar contestação</button>
              <button class="btn ghost sm" (click)="disputing.set(null)">Cancelar</button></div>
          } @else if (r.stars <= 3) {
            <button class="btn link" (click)="disputing.set(r.id)">Contestar avaliação</button>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .page { max-width: 760px; padding-top: 24px; padding-bottom: 24px; }
    .summary { gap: 24px; align-items: center; } .avg { display: flex; flex-direction: column; align-items: center; }
    .value { font-size: 2.4rem; font-weight: 800; } .grow { flex: 1; min-width: 200px; }
    .bar { display: grid; grid-template-columns: 32px 1fr 32px; gap: 8px; align-items: center; }
    .track { height: 8px; background: var(--ld-surface-2); border-radius: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--ld-star); } .stars { color: var(--ld-star); letter-spacing: 2px; }
  `,
})
export class RatingsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  protected readonly summary = signal<Summary | null>(null);
  protected readonly ratings = signal<Rating[]>([]);
  protected readonly disputing = signal<string | null>(null);
  protected reason = '';

  async ngOnInit(): Promise<void> {
    this.summary.set(await this.api.get<Summary>(`/drivers/${this.auth.me()!.uid}/ratings/summary`).catch(() => null));
    this.ratings.set(await this.api.get<Rating[]>('/drivers/me/ratings').catch(() => []));
  }

  protected round(v: number): number {
    return Math.round(v);
  }

  protected pct(s: Summary, n: number): number {
    return s.count ? ((s.starCounts[n] ?? 0) / s.count) * 100 : 0;
  }

  protected async dispute(r: Rating): Promise<void> {
    try {
      await this.api.post(`/drivers/me/ratings/${r.id}/dispute`, { reason: this.reason });
      this.toast.ok('Contestação enviada ao suporte.');
      this.disputing.set(null);
      this.reason = '';
      this.ratings.set(await this.api.get<Rating[]>('/drivers/me/ratings'));
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    }
  }
}
