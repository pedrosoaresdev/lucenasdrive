import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { RidePaymentComponent } from '../components/ride-payment.component';
import {
  ApiError, ApiService, DateTimePipe, KmPipe, MinutesPipe, MoneyPipe, PaymentLabelPipe, RideStatusPipe, RideView,
  StateViewComponent,
} from '@lucena/shared';

/** Detalhes e recibo da corrida. */
@Component({
  selector: 'app-ride-detail',
  imports: [RouterLink, RidePaymentComponent, MoneyPipe, DateTimePipe, KmPipe, MinutesPipe, PaymentLabelPipe, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page">
      @if (!ride()) { <ld-state [state]="error() ? 'error' : 'loading'" [message]="error()" [retryable]="false" /> }
      @else {
        @let r = ride()!;
        @if (r.status === 'COMPLETED' && r.paymentMethod !== 'CASH') {
          <div class="noprint"><app-ride-payment [rideId]="r.id" /></div>
        }
        <article class="card stack receipt">
          <div class="row between"><h1>Recibo</h1><span class="mono small muted">{{ r.code }}</span></div>
          <p class="small muted">{{ r.createdAt | dt }} · {{ r.status | rideStatus }} · {{ r.categoryName }}</p>
          <dl>
            <div><dt>Embarque</dt><dd>{{ r.origin.address }}</dd></div>
            <div><dt>Destino</dt><dd>{{ r.destination.address }}</dd></div>
            <div><dt>Distância / duração</dt><dd>{{ r.route.distanceMeters | km }} · {{ r.route.durationSeconds | minutes }}</dd></div>
            @if (r.driver) { <div><dt>Motorista</dt><dd>{{ r.driver.name }} · {{ r.driver.vehicleMake }} {{ r.driver.vehicleModel }} · {{ r.driver.plate }}</dd></div> }
          </dl>
          <hr />
          <dl class="money">
            <div><dt>Valor da corrida</dt><dd>{{ r.fare.totalCents | money }}</dd></div>
            @if (r.fare.discountCents) { <div><dt>Cupom {{ r.couponCode }}</dt><dd>−{{ r.fare.discountCents | money }}</dd></div> }
            @if (r.status === 'CANCELLED') {
              <div class="total"><dt>Taxa de cancelamento</dt><dd>{{ (r.cancellation?.feeCents ?? 0) | money }}</dd></div>
            } @else {
              <div class="total"><dt>Total</dt><dd>{{ r.fare.finalCents | money }}</dd></div>
            }
            <div><dt>Pagamento</dt><dd>{{ r.paymentMethod | payment }}</dd></div>
            <div><dt>Status financeiro</dt><dd>{{ r.paymentStatus | payment }}</dd></div>
            @if (r.rating?.stars) { <div><dt>Sua avaliação</dt><dd>{{ '★'.repeat(r.rating!.stars!) }}</dd></div> }
          </dl>
          <div class="row noprint">
            <button class="btn ghost" (click)="print()">Imprimir / salvar PDF</button>
            @if (r.rating?.status === 'PENDING') { <a class="btn primary" [routerLink]="['/corrida', r.id, 'avaliar']">Avaliar</a> }
            <a class="btn ghost" routerLink="/suporte" [queryParams]="{ corrida: r.id }">Relatar problema</a>
          </div>
        </article>
      }
    </div>
  `,
  styles: `
    .page { max-width: 640px; padding-top: 24px; padding-bottom: 24px; }
    dl { margin: 0; display: flex; flex-direction: column; gap: 8px; }
    dl div { display: flex; justify-content: space-between; gap: 16px; }
    dt { color: var(--ld-text-muted); } dd { margin: 0; text-align: right; }
    .total { font-weight: 800; font-size: 1.1rem; } .total dt { color: var(--ld-text); }
    hr { border: 0; border-top: 1px dashed var(--ld-border); width: 100%; }
    @media print { .noprint { display: none; } }
  `,
})
export class RideDetailPage implements OnInit {
  private readonly api = inject(ApiService);
  readonly id = input.required<string>();
  protected readonly ride = signal<RideView | null>(null);
  protected readonly error = signal('');

  async ngOnInit(): Promise<void> {
    try {
      this.ride.set(await this.api.get<RideView>(`/rides/${this.id()}`));
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }

  protected print(): void {
    window.print();
  }
}
