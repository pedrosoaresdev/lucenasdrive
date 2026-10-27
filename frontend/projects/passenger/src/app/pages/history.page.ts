import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ApiError, ApiService, DateTimePipe, MoneyPipe, Page, RideStatus, RideStatusPipe, RideView, StateViewComponent,
  rideStatusTone,
} from '@lucena/shared';

/** Histórico paginado (concluídas, canceladas, em andamento). */
@Component({
  selector: 'app-history',
  imports: [RouterLink, MoneyPipe, DateTimePipe, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Minhas corridas</h1>
      <div class="nav-tabs" role="tablist">
        @for (f of filters; track f.label) {
          <a role="tab" href="#" [class.active]="status() === f.value" [attr.aria-selected]="status() === f.value"
             (click)="$event.preventDefault(); setFilter(f.value)">{{ f.label }}</a>
        }
      </div>
      @if (state() === 'loading' && !items().length) { <ld-state state="loading" /> }
      @if (state() === 'error') { <ld-state state="error" [message]="error()" (retry)="reload()" /> }
      @for (r of items(); track r.id) {
        <a class="card flat ride" [routerLink]="['/historico', r.id]">
          <div class="row between"><strong>{{ r.createdAt | dt }}</strong>
            <span class="badge" [class]="tone(r.status)">{{ r.status | rideStatus }}</span></div>
          <span class="small"><span class="dot o"></span>{{ r.origin.address }}</span>
          <span class="small"><span class="dot d"></span>{{ r.destination.address }}</span>
          <div class="row between small muted">
            <span>{{ r.driver ? r.driver.name + ' · ' + r.driver.plate : 'Sem motorista' }}</span>
            <strong class="price">{{ r.status === 'CANCELLED' ? (r.cancellation?.feeCents ? (r.cancellation!.feeCents | money) : '—') : (r.fare.finalCents | money) }}</strong>
          </div>
        </a>
      } @empty {
        @if (state() === 'ready') { <ld-state state="empty" title="Nenhuma corrida encontrada" message="Suas viagens aparecerão aqui." /> }
      }
      @if (cursor()) { <button class="btn ghost" (click)="more()" [disabled]="state() === 'loading'">Carregar mais</button> }
    </div>
  `,
  styles: `
    .page { max-width: 760px; padding-top: 24px; padding-bottom: 24px; }
    .ride { display: flex; flex-direction: column; gap: 6px; color: inherit; }
    .ride:hover { text-decoration: none; border-color: var(--ld-primary); }
    .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 8px; }
    .dot.o { background: var(--ld-accent); } .dot.d { background: var(--ld-primary); }
    .price { color: var(--ld-text); }
  `,
})
export class HistoryPage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly filters: { label: string; value: RideStatus | null }[] = [
    { label: 'Todas', value: null }, { label: 'Concluídas', value: 'COMPLETED' }, { label: 'Canceladas', value: 'CANCELLED' },
    { label: 'Em andamento', value: 'IN_PROGRESS' },
  ];
  protected readonly status = signal<RideStatus | null>(null);
  protected readonly items = signal<RideView[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly error = signal('');
  protected readonly tone = rideStatusTone;

  ngOnInit(): void {
    void this.reload();
  }

  protected setFilter(s: RideStatus | null): void {
    this.status.set(s);
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.items.set([]);
    this.cursor.set(null);
    await this.more();
  }

  protected async more(): Promise<void> {
    this.state.set('loading');
    try {
      const page = await this.api.get<Page<RideView>>('/rides', { as: 'passenger', status: this.status(), cursor: this.cursor(), limit: 15 });
      this.items.update((l) => [...l, ...page.items]);
      this.cursor.set(page.nextCursor);
      this.state.set('ready');
    } catch (e) {
      this.error.set(ApiError.from(e).message);
      this.state.set('error');
    }
  }
}
