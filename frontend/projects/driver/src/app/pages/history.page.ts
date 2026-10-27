import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import {
  ApiService, DateTimePipe, KmPipe, MoneyPipe, Page, RideStatusPipe, RideView, StateViewComponent, rideStatusTone,
} from '@lucena/shared';

@Component({
  selector: 'app-driver-history',
  imports: [MoneyPipe, DateTimePipe, KmPipe, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Histórico de corridas</h1>
      @for (r of items(); track r.id) {
        <div class="card flat stack ride">
          <div class="row between"><strong>{{ r.createdAt | dt }}</strong><span class="badge" [class]="tone(r.status)">{{ r.status | rideStatus }}</span></div>
          <span class="small">{{ r.origin.address }} → {{ r.destination.address }}</span>
          <div class="row between small muted"><span>{{ r.route.distanceMeters | km }} · {{ r.passenger?.name }}</span>
            <span>Ganho <strong>{{ r.status === 'COMPLETED' ? (r.fare.driverEarningCents | money) : '—' }}</strong>
              @if (r.rating?.stars) { · ★ {{ r.rating!.stars }} }</span></div>
        </div>
      } @empty { @if (!loading()) { <ld-state state="empty" title="Nenhuma corrida ainda" /> } }
      @if (loading()) { <ld-state state="loading" /> }
      @if (cursor()) { <button class="btn ghost" (click)="more()">Carregar mais</button> }
    </div>
  `,
  styles: `.page { max-width: 760px; padding-top: 24px; padding-bottom: 24px; } .ride { gap: 4px; }`,
})
export class HistoryPage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly items = signal<RideView[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly tone = rideStatusTone;

  ngOnInit(): void {
    void this.more();
  }

  protected async more(): Promise<void> {
    this.loading.set(true);
    try {
      const p = await this.api.get<Page<RideView>>('/rides', { as: 'driver', cursor: this.cursor(), limit: 20 });
      this.items.update((l) => [...l, ...p.items]);
      this.cursor.set(p.nextCursor);
    } finally {
      this.loading.set(false);
    }
  }
}
