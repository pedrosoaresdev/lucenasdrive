import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ApiError, ApiService, AuthService, AvatarComponent, ChatPanelComponent, MapComponent, MapMarker, MinutesPipe,
  MoneyPipe, PaymentLabelPipe, RealtimeService, RideStatusPipe, RideView, StateViewComponent, ToastService,
  rideStatusTone,
} from '@lucena/shared';

const CANCELLABLE = ['SEARCHING_DRIVER', 'ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED'];

/**
 * Acompanhamento em tempo real: listener no documento da corrida (regras do Firestore permitem
 * somente aos participantes). Status, posição do motorista e ETA chegam sem recarregar.
 */
@Component({
  selector: 'app-tracking',
  imports: [FormsModule, RouterLink, MapComponent, AvatarComponent, ChatPanelComponent, MoneyPipe, MinutesPipe,
    RideStatusPipe, PaymentLabelPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!ride()) {
      <ld-state [state]="loadError() ? 'error' : 'loading'" [message]="loadError()" (retry)="subscribe()" />
    } @else {
      @let r = ride()!;
      <div class="layout">
        <ld-map class="map" [markers]="markers()" [polyline]="r.route.encodedPolyline" />
        <section class="sheet panel">
          <div class="row between">
            <span class="badge" [class]="tone()">{{ r.status | rideStatus }}</span>
            <span class="small muted mono">{{ r.code }}</span>
          </div>

          @if (r.status === 'SEARCHING_DRIVER') {
            <div class="searching">
              <span class="radar pulse" aria-hidden="true"></span>
              <div><strong>Procurando um motorista próximo…</strong>
                <p class="small muted">Enviamos sua solicitação aos motoristas disponíveis.</p></div>
            </div>
          }

          @if (r.driver; as d) {
            <div class="driver">
              <ld-avatar [path]="d.photoPath" [name]="d.name" [size]="56" />
              <div class="grow">
                <strong>{{ d.name }}</strong>
                <span class="small muted">★ {{ d.ratingCount ? d.ratingAvg.toFixed(2) : 'Novo' }} · {{ d.vehicleMake }} {{ d.vehicleModel }} · {{ d.vehicleColor }}</span>
              </div>
              <span class="plate" aria-label="Placa">{{ d.plate }}</span>
            </div>
            @if (r.etaSeconds !== null && (r.status === 'ACCEPTED' || r.status === 'DRIVER_ARRIVING')) {
              <p class="eta">Chega em aproximadamente <strong>{{ r.etaSeconds | minutes }}</strong></p>
            }
            @if (r.status === 'DRIVER_ARRIVED') { <p class="alert ok">Seu motorista chegou. Confira a placa antes de embarcar.</p> }
            @if (r.status === 'IN_PROGRESS' && r.etaSeconds !== null) {
              <p class="eta">Chegada ao destino em ~<strong>{{ r.etaSeconds | minutes }}</strong></p>
            }
          }

          <dl class="trip">
            <div><dt>Embarque</dt><dd>{{ r.origin.address }}</dd></div>
            <div><dt>Destino</dt><dd>{{ r.destination.address }}</dd></div>
            <div><dt>Valor</dt><dd><strong>{{ r.fare.finalCents | money }}</strong> · {{ r.paymentMethod | payment }}
              @if (r.fare.discountCents) { <span class="badge ok">cupom −{{ r.fare.discountCents | money }}</span> }</dd></div>
          </dl>

          @if (r.status === 'CANCELLED') {
            <p class="alert err">Corrida cancelada{{ r.cancellation?.feeCents ? ' · taxa de ' : '' }}{{ r.cancellation?.feeCents ? (r.cancellation!.feeCents | money) : '' }}.</p>
            <a class="btn primary block" routerLink="/inicio">Pedir outra corrida</a>
          }

          <div class="row actions">
            @if (r.driver && r.status !== 'CANCELLED') {
              <button class="btn primary grow" (click)="chatOpen.set(!chatOpen())">{{ chatOpen() ? 'Fechar chat' : 'Chat com o motorista' }}</button>
            }
            @if (canCancel()) { <button class="btn ghost" (click)="cancelOpen.set(true)">Cancelar</button> }
            <a class="btn ghost" routerLink="/suporte" [queryParams]="{ corrida: r.id }">Ajuda</a>
          </div>

          @if (cancelOpen()) {
            <div class="card flat stack" role="dialog" aria-label="Cancelar corrida">
              <strong>Cancelar corrida?</strong>
              @if (feeLikely()) { <p class="alert warn small">Pode haver cobrança de taxa de cancelamento de {{ r.fare.cancellationFeeCents | money }}.</p> }
              <select class="input" [(ngModel)]="reason" aria-label="Motivo">
                <option value="CHANGED_MIND">Mudei de ideia</option>
                <option value="TOO_LONG">Motorista demorando</option>
                <option value="WRONG_ADDRESS">Endereço errado</option>
                <option value="SAFETY">Não me senti seguro(a)</option>
                <option value="OTHER">Outro motivo</option>
              </select>
              <div class="row">
                <button class="btn danger" (click)="cancel()" [disabled]="busy()">Confirmar cancelamento</button>
                <button class="btn ghost" (click)="cancelOpen.set(false)">Voltar</button>
              </div>
            </div>
          }

          <details class="safety">
            <summary>Segurança</summary>
            <ul class="small">
              <li>Confira placa, modelo e cor do veículo antes de embarcar.</li>
              <li>Use o chat da corrida: seu telefone não é compartilhado.</li>
              <li>Em emergência, ligue 190 (Polícia) ou 192 (SAMU).</li>
              <li>Relate qualquer ocorrência em Ajuda › Segurança.</li>
            </ul>
          </details>
        </section>
        @if (chatOpen() && r.driver) {
          <aside class="chat card" aria-label="Chat">
            <ld-chat-panel [rideId]="r.id" [myUid]="auth.me()!.uid" [counterpartName]="r.driver.name"
                           [counterpartPhoto]="r.driver.photoPath" [closable]="true" (closed)="chatOpen.set(false)" />
          </aside>
        }
      </div>
    }
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1fr; position: relative; }
    .map { height: 40dvh; }
    .panel { margin-top: -18px; position: relative; z-index: 2; display: flex; flex-direction: column; gap: 14px; }
    @media (min-width: 900px) {
      .layout { grid-template-columns: 400px 1fr; min-height: calc(100dvh - 60px); }
      .map { order: 2; height: auto; } .panel { margin: 0; border-radius: 0; box-shadow: none; }
      .chat { position: fixed; right: 24px; bottom: 24px; width: 380px; height: 560px; }
    }
    .chat { padding: 0; overflow: hidden; height: 70dvh; z-index: 40; }
    @media (max-width: 899px) { .chat { position: fixed; inset: auto 0 0 0; border-radius: 22px 22px 0 0; } }
    .searching { display: flex; gap: 14px; align-items: center; }
    .radar { width: 44px; height: 44px; border-radius: 50%; background: var(--ld-primary-soft); border: 3px solid var(--ld-primary); flex: none; }
    .driver { display: flex; gap: 12px; align-items: center; }
    .grow { flex: 1; display: flex; flex-direction: column; }
    .plate { font: 800 1.05rem var(--ld-font); letter-spacing: .08em; padding: 6px 10px; border: 2px solid var(--ld-text);
      border-radius: 8px; background: var(--ld-surface); }
    .eta { margin: 0; }
    .trip { margin: 0; display: flex; flex-direction: column; gap: 8px; }
    .trip div { display: grid; grid-template-columns: 90px 1fr; gap: 8px; }
    dt { color: var(--ld-text-muted); font-size: .85rem; } dd { margin: 0; }
    .actions .grow { flex: 1; }
    .safety summary { cursor: pointer; font-weight: 600; }
  `,
})
export class TrackingPage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly rt = inject(RealtimeService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  readonly id = input.required<string>();

  protected readonly ride = signal<RideView | null>(null);
  protected readonly loadError = signal('');
  protected readonly chatOpen = signal(false);
  protected readonly cancelOpen = signal(false);
  protected readonly busy = signal(false);
  protected reason = 'CHANGED_MIND';
  private unsub?: () => void;

  protected readonly tone = computed(() => (this.ride() ? rideStatusTone(this.ride()!.status) : ''));
  protected readonly canCancel = computed(() => CANCELLABLE.includes(this.ride()?.status ?? ''));
  protected readonly feeLikely = computed(() => {
    const r = this.ride();
    if (!r?.timeline.acceptedAt) return false;
    return r.status === 'DRIVER_ARRIVED' || Date.now() - new Date(r.timeline.acceptedAt).getTime() > 120_000;
  });
  protected readonly markers = computed<MapMarker[]>(() => {
    const r = this.ride();
    if (!r) return [];
    const m: MapMarker[] = [
      { id: 'o', kind: 'origin', lat: r.origin.lat, lng: r.origin.lng },
      { id: 'd', kind: 'destination', lat: r.destination.lat, lng: r.destination.lng },
    ];
    if (r.driverPosition) {
      m.push({ id: 'car', kind: 'car', lat: r.driverPosition.lat, lng: r.driverPosition.lng, heading: r.driverPosition.heading });
    }
    return m;
  });

  ngOnInit(): void {
    this.subscribe();
    this.destroyRef.onDestroy(() => this.unsub?.());
  }

  protected subscribe(): void {
    this.unsub?.();
    this.loadError.set('');
    const sub = this.rt.doc<RideView>(`rides/${this.id()}`).subscribe({
      next: (r) => {
        if (!r) {
          this.loadError.set('Corrida não encontrada.');
          return;
        }
        const prev = this.ride()?.status;
        this.ride.set(r);
        if (r.status === 'COMPLETED' && r.rating?.status === 'PENDING') {
          void this.router.navigate(['/corrida', r.id, 'avaliar']);
        } else if (prev && prev !== r.status && r.status === 'ACCEPTED') {
          this.toast.ok('Motorista a caminho!');
        }
      },
      error: () => this.loadError.set('Sem acesso a esta corrida ou falha de conexão.'),
    });
    this.unsub = () => sub.unsubscribe();
  }

  protected async cancel(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post(`/rides/${this.id()}/cancel`, { reasonCode: this.reason });
      this.cancelOpen.set(false);
      this.toast.show('Corrida cancelada.');
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
