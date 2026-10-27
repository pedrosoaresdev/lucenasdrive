import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  ApiService, ChatMessage, DateTimePipe, KmPipe, LedgerEntry, MapComponent, MinutesPipe, MoneyPipe, Page, PaymentLabelPipe,
  RideStatusPipe, RideView, StateViewComponent, newIdempotencyKey,
} from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Detail {
  ride: RideView;
  events: { id: string; type: string; fromStatus: string | null; toStatus: string | null; actorRole: string; actorId: string; at: number }[];
  ledger: LedgerEntry[];
  payments: { id: string; method: string; status: string; amountCents: number; providerChargeId: string | null }[];
  rating: { id: string; stars: number; comment: string | null; status: string } | null;
}

/** Detalhe operacional e financeiro da corrida; correções viram lançamentos de ajuste (sem apagar histórico). */
@Component({
  selector: 'admin-ride-detail',
  imports: [FormsModule, RouterLink, DateTimePipe, KmPipe, MinutesPipe, MoneyPipe, PaymentLabelPipe, RideStatusPipe,
    StateViewComponent, MapComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/corridas" class="small">← Corridas</a>
    @if (!d()) { <ld-state state="loading" /> }
    @else {
      @let r = d()!.ride;
      <div class="row between head"><h1>Corrida <span class="mono">{{ r.code }}</span></h1><span class="badge brand">{{ r.status | rideStatus }}</span></div>
      <div class="grid cols">
        <section class="card stack">
          <h2>Resumo</h2>
          <p class="small">{{ r.createdAt | dt }} · {{ r.categoryName }} · {{ r.route.distanceMeters | km }} · {{ r.route.durationSeconds | minutes }}
            · rota {{ r.route.source === 'GOOGLE_ROUTES' ? 'Google Routes' : 'estimativa aproximada' }}</p>
          <p class="small"><strong>Passageiro:</strong> <a [routerLink]="['/passageiros', r.passenger?.userId]">{{ r.passenger?.name }}</a></p>
          @if (r.driver) { <p class="small"><strong>Motorista:</strong> <a [routerLink]="['/motoristas', r.driver.userId]">{{ r.driver.name }}</a>
            · {{ r.driver.vehicleMake }} {{ r.driver.vehicleModel }} · {{ r.driver.plate }}</p> }
          <p class="small"><strong>Origem:</strong> {{ r.origin.address }}<br /><strong>Destino:</strong> {{ r.destination.address }}</p>
          <p class="small">Tarifa {{ r.fare.totalCents | money }} · desconto {{ r.fare.discountCents | money }} · pago {{ r.fare.finalCents | money }}
            · comissão {{ r.fare.commissionCents | money }} · motorista {{ r.fare.driverEarningCents | money }}<br />
            Tarifa aplicada: <span class="mono">{{ r.fare.fareConfigId }} v{{ r.fare.fareConfigVersion }}</span> · multiplicador {{ r.fare.multiplierBp / 10000 }}×</p>
          <p class="small">{{ r.paymentMethod | payment }} · {{ r.paymentStatus | payment }} @if (r.couponCode) { · cupom {{ r.couponCode }} }</p>
          @if (r.cancellation) { <p class="alert err small">Cancelada por {{ r.cancellation.byRole }} · {{ r.cancellation.reasonCode }} {{ r.cancellation.reasonText }} · taxa {{ r.cancellation.feeCents | money }}</p> }
          <div class="row">
            @if (active()) { <button class="btn danger sm" (click)="cancel()">Cancelar corrida</button> }
            <button class="btn ghost sm" (click)="openChat()">Consultar chat (auditado)</button>
          </div>
        </section>
        <section class="card map-card"><ld-map [markers]="[{ id: 'o', kind: 'origin', lat: r.origin.lat, lng: r.origin.lng },
          { id: 'd', kind: 'destination', lat: r.destination.lat, lng: r.destination.lng }]" [polyline]="r.route.encodedPolyline" /></section>
        <section class="card stack">
          <h2>Linha do tempo</h2>
          @for (e of d()!.events; track e.id) {
            <p class="small"><span class="mono">{{ e.at | dt }}</span> · {{ e.type }} · {{ e.actorRole }}</p>
          }
        </section>
        <section class="card stack">
          <h2>Avaliação</h2>
          @if (d()!.rating; as rt) { <p>{{ '★'.repeat(rt.stars) }} <span class="small muted">({{ rt.status }})</span></p> <p class="small">{{ rt.comment }}</p> }
          @else { <p class="small muted">{{ r.rating?.status === 'SKIPPED' ? 'Passageiro pulou a avaliação.' : 'Sem avaliação.' }}</p> }
        </section>
      </div>

      <section class="stack block">
        <h2>Lançamentos financeiros</h2>
        <div class="table-wrap"><table class="table">
          <thead><tr><th>Data</th><th>Tipo</th><th>Conta</th><th>Direção</th><th>Valor</th><th>Status</th><th>Descrição</th></tr></thead>
          <tbody>@for (e of d()!.ledger; track e.id) {
            <tr><td>{{ e.createdAt | dt }}</td><td>{{ e.type }}</td><td class="mono">{{ e.account }}</td><td>{{ e.direction }}</td>
              <td class="mono">{{ e.amountCents | money }}</td><td>{{ e.status }}</td><td>{{ e.description }}</td></tr>
          } @empty { <tr><td colspan="7" class="muted">Sem lançamentos.</td></tr> }</tbody>
        </table></div>
        @if (a.session.can('FINANCE', 'ADMIN', 'OWNER')) {
          <details><summary>Registrar ajuste financeiro</summary>
            <div class="row adj">
              <select class="input" [(ngModel)]="adj.account" aria-label="Conta"><option value="DRIVER">Motorista</option><option value="PLATFORM">Plataforma</option></select>
              <select class="input" [(ngModel)]="adj.direction" aria-label="Direção"><option value="CREDIT">Crédito</option><option value="DEBIT">Débito</option></select>
              <input class="input" type="number" step="0.01" min="0.01" [(ngModel)]="adj.amount" placeholder="Valor (R$)" aria-label="Valor" />
              <button class="btn primary" (click)="adjust()" [disabled]="!adj.amount">Registrar</button>
            </div>
            <p class="small muted">Ajustes criam novo lançamento; os originais nunca são alterados.</p>
          </details>
        }
      </section>

      @if (chat().length) {
        <section class="card stack block">
          <h2>Chat da corrida (acesso registrado)</h2>
          @for (m of chat(); track m.id) { <p class="small"><span class="mono">{{ m.createdAt | dt }}</span> · <strong>{{ m.senderRole }}</strong>: {{ m.text }}</p> }
        </section>
      }
    }
  `,
  styles: `.head { margin: 8px 0 16px; } .cols { grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); }
    .map-card { padding: 0; overflow: hidden; min-height: 300px; } .map-card ld-map { height: 100%; min-height: 300px; }
    .block { margin-top: 20px; } .adj { margin-top: 8px; } .adj .input { width: auto; } summary { cursor: pointer; font-weight: 600; }`,
})
export default class RideDetailPage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly a = adminActions();
  readonly rideId = input.required<string>();
  protected readonly d = signal<Detail | null>(null);
  protected readonly chat = signal<ChatMessage[]>([]);
  protected adj = { account: 'DRIVER', direction: 'CREDIT', amount: null as number | null };
  protected readonly active = () => ['SEARCHING_DRIVER', 'ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'IN_PROGRESS']
    .includes(this.d()?.ride.status ?? '');

  async ngOnInit(): Promise<void> {
    this.d.set(await this.api.get<Detail>(`/admin/rides/${this.rideId()}`));
  }

  protected async cancel(): Promise<void> {
    const ok = await this.a.withReason('Cancelar corrida', (reason) => this.api.post(`/admin/rides/${this.rideId()}/cancel`, { reason }),
      { danger: true, ok: 'Corrida cancelada.', message: 'Passageiro e motorista serão notificados.' });
    if (ok) await this.ngOnInit();
  }

  protected async openChat(): Promise<void> {
    const page = await this.a.withReason('Acessar conversa da corrida',
      (reason) => this.api.get<Page<ChatMessage>>(`/admin/rides/${this.rideId()}/chat`, { reason, limit: 100 }),
      { message: 'Acesso restrito a apuração de ocorrências. Ficará registrado na auditoria.' });
    if (page) this.chat.set([...page.items].reverse());
  }

  protected async adjust(): Promise<void> {
    const key = newIdempotencyKey();
    const ok = await this.a.withReason('Registrar ajuste', (reason) => this.api.post(`/admin/rides/${this.rideId()}/adjustments`,
      { account: this.adj.account, direction: this.adj.direction, amountCents: Math.round(Number(this.adj.amount) * 100), reason },
      { idempotencyKey: key }), { ok: 'Ajuste registrado.' });
    if (ok) {
      this.adj.amount = null;
      await this.ngOnInit();
    }
  }
}
