import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  ApiError, ApiService, DateTimePipe, KmPipe, MinutesPipe, MoneyPipe, Page, PaymentLabelPipe, RideStatusPipe, RideView,
  StateViewComponent, rideStatusTone,
} from '@lucena/shared';

/** Gestão de corridas: filtros por período, cidade, status, motorista e passageiro. */
@Component({
  selector: 'admin-rides',
  imports: [FormsModule, DateTimePipe, KmPipe, MinutesPipe, MoneyPipe, PaymentLabelPipe, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Corridas</h1>
    <form class="filters" (submit)="$event.preventDefault(); reload()">
      <select class="input" [(ngModel)]="f.status" name="status" aria-label="Status">
        <option value="">Todos os status</option>
        @for (s of statuses; track s) { <option [value]="s">{{ s | rideStatus }}</option> }
      </select>
      <input class="input" [(ngModel)]="f.cityId" name="city" placeholder="ID da cidade" aria-label="Cidade" />
      <input class="input" [(ngModel)]="f.driverId" name="driver" placeholder="ID do motorista" aria-label="Motorista" />
      <input class="input" [(ngModel)]="f.passengerId" name="pax" placeholder="ID do passageiro" aria-label="Passageiro" />
      <input class="input" type="date" [(ngModel)]="f.from" name="from" aria-label="De" />
      <input class="input" type="date" [(ngModel)]="f.to" name="to" aria-label="Até" />
      <button class="btn primary" type="submit">Filtrar</button>
    </form>
    @if (error()) { <ld-state state="error" [message]="error()" (retry)="reload()" /> }
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Código</th><th>Data/hora</th><th>Passageiro</th><th>Motorista</th><th>Origem</th><th>Destino</th>
        <th>Distância</th><th>Duração</th><th>Tarifa</th><th>Comissão</th><th>Pagamento</th><th>Status</th><th>Nota</th></tr></thead>
      <tbody>@for (r of items(); track r.id) {
        <tr class="clickable" tabindex="0" (click)="open(r)" (keydown.enter)="open(r)">
          <td class="mono">{{ r.code }}</td><td>{{ r.createdAt | dt }}</td><td>{{ r.passenger?.name }}</td><td>{{ r.driver?.name ?? '—' }}</td>
          <td class="trunc">{{ r.origin.address }}</td><td class="trunc">{{ r.destination.address }}</td>
          <td>{{ r.route.distanceMeters | km }}</td><td>{{ r.route.durationSeconds | minutes }}</td><td class="mono">{{ r.fare.finalCents | money }}</td>
          <td class="mono">{{ r.fare.commissionCents | money }}</td><td>{{ r.paymentMethod | payment }}</td>
          <td><span class="badge" [class]="tone(r.status)">{{ r.status | rideStatus }}</span></td>
          <td>{{ r.rating?.stars ? '★' + r.rating!.stars : '—' }}</td></tr>
      } @empty { @if (!loading()) { <tr><td colspan="13" class="muted">Nenhuma corrida encontrada.</td></tr> } }</tbody>
    </table></div>
    @if (loading()) { <ld-state state="loading" /> }
    @if (cursor()) { <button class="btn ghost more" (click)="more()">Carregar mais</button> }
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 16px; } .filters .input { width: auto; min-width: 150px; flex: 1; }
    .trunc { max-width: 200px; overflow: hidden; text-overflow: ellipsis; } .more { margin-top: 12px; }
  `,
})
export default class RidesPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  readonly driverId = input<string>();
  readonly passengerId = input<string>();
  protected readonly statuses = ['SEARCHING_DRIVER', 'ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'] as const;
  protected f = { status: '', cityId: '', driverId: '', passengerId: '', from: '', to: '' };
  protected readonly items = signal<RideView[]>([]);
  protected readonly cursor = signal<string | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly tone = rideStatusTone;

  ngOnInit(): void {
    this.f.driverId = this.driverId() ?? '';
    this.f.passengerId = this.passengerId() ?? '';
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.items.set([]);
    this.cursor.set(null);
    await this.more();
  }

  protected async more(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const iso = (d: string, end = false) => (d ? new Date(d + (end ? 'T23:59:59' : 'T00:00:00')).toISOString() : null);
      const page = await this.api.get<Page<RideView>>('/admin/rides', {
        status: this.f.status, cityId: this.f.cityId, driverId: this.f.driverId, passengerId: this.f.passengerId,
        from: iso(this.f.from), to: iso(this.f.to, true), cursor: this.cursor(), limit: 25,
      });
      this.items.update((l) => [...l, ...page.items]);
      this.cursor.set(page.nextCursor);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.loading.set(false);
    }
  }

  protected open(r: RideView): void {
    void this.router.navigate(['/corridas', r.id]);
  }
}
