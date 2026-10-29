import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  ApiError, ApiService, AuthService, AvatarComponent, ChatPanelComponent, MapComponent, MapMarker, MoneyPipe,
  PaymentLabelPipe, RealtimeService, RideStatus, RideStatusPipe, RideView, SafetyPanelComponent, StateViewComponent,
  ToastService,
} from '@lucena/shared';
import { DriverLocationService } from '../location.service';
import { RatePassengerComponent } from './rate-passenger.component';

const NEXT: Partial<Record<RideStatus, { to: RideStatus; label: string }>> = {
  ACCEPTED: { to: 'DRIVER_ARRIVING', label: 'Iniciar deslocamento ao embarque' },
  DRIVER_ARRIVING: { to: 'DRIVER_ARRIVED', label: 'Cheguei ao embarque' },
  DRIVER_ARRIVED: { to: 'IN_PROGRESS', label: 'Iniciar viagem' },
  IN_PROGRESS: { to: 'COMPLETED', label: 'Finalizar viagem' },
};

/** Execução da corrida pelo motorista: navegação, etapas validadas no servidor, chat e cancelamento. */
@Component({
  selector: 'app-driver-ride',
  imports: [FormsModule, MapComponent, AvatarComponent, ChatPanelComponent, MoneyPipe, RideStatusPipe, PaymentLabelPipe,
    StateViewComponent, RatePassengerComponent, SafetyPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!ride()) { <ld-state [state]="error() ? 'error' : 'loading'" [message]="error()" [retryable]="false" /> }
    @else {
      @let r = ride()!;
      <div class="layout">
        <ld-map class="map" [markers]="markers()" [polyline]="r.route.encodedPolyline" />
        <section class="sheet panel">
          <div class="row between"><span class="badge brand">{{ r.status | rideStatus }}</span><span class="mono small muted">{{ r.code }}</span></div>
          <div class="row">
            <ld-avatar [path]="r.passenger?.photoPath" [name]="r.passenger?.name ?? ''" [size]="48" />
            <div><strong>{{ r.passenger?.name }}</strong><p class="small muted">{{ r.paymentMethod | payment }}
              @if (r.paymentMethod === 'CASH') { · receber <strong>{{ r.fare.finalCents | money }}</strong> em dinheiro }</p></div>
          </div>
          <dl class="trip">
            <div><dt>Embarque</dt><dd>{{ r.origin.address }}</dd></div>
            <div><dt>Destino</dt><dd>{{ r.destination.address }}</dd></div>
            <div><dt>Seu ganho</dt><dd><strong>{{ r.fare.driverEarningCents | money }}</strong> (comissão {{ r.fare.commissionCents | money }})</dd></div>
          </dl>
          @if (r.fare.discountCents && r.paymentMethod === 'CASH') {
            <p class="alert info small">Cupom de {{ r.fare.discountCents | money }} aplicado: cobre do passageiro somente o valor final; o desconto é repassado pela plataforma.</p>
          }
          @if (navTarget(); as t) {
            <a class="btn ghost block" [href]="navUrl(t)" target="_blank" rel="noopener">Navegar até {{ r.status === 'IN_PROGRESS' ? 'o destino' : 'o embarque' }} ↗</a>
          }
          @if (next(); as n) {
            <button class="btn primary lg block" (click)="advance(n.to)" [disabled]="busy()">{{ n.label }}</button>
          }
          @if (r.status === 'COMPLETED') {
            <p class="alert ok">Viagem finalizada.</p>
            @if (r.driverRating?.status === 'PENDING' && !rated()) {
              <app-rate-passenger [rideId]="r.id" [name]="r.passenger?.name ?? 'o passageiro'" (done)="onRated()" />
            } @else if (rated() || r.driverRating?.status === 'SUBMITTED') {
              <p class="small muted">Avaliação do passageiro enviada. Obrigado!</p>
            }
            <button class="btn primary block" (click)="router.navigateByUrl('/painel')">Voltar ao painel</button>
          }
          @if (r.status === 'CANCELLED') {
            <p class="alert err">Corrida cancelada.</p>
            <button class="btn primary block" (click)="router.navigateByUrl('/painel')">Voltar ao painel</button>
          }
          <div class="row">
            @if (r.status !== 'CANCELLED') { <button class="btn ghost grow" (click)="chatOpen.set(!chatOpen())">Chat</button> }
            @if (cancellable()) { <button class="btn ghost" (click)="cancelOpen.set(!cancelOpen())">Cancelar</button> }
            @if (r.status !== 'CANCELLED' && r.status !== 'COMPLETED') {
              <button class="btn danger" (click)="safetyOpen.set(!safetyOpen())">Segurança</button>
            }
          </div>
          @if (safetyOpen()) { <ld-safety-panel [rideId]="r.id" (closed)="safetyOpen.set(false)" /> }
          @if (cancelOpen()) {
            <div class="card flat stack">
              <select class="input" [(ngModel)]="reason" aria-label="Motivo do cancelamento">
                <option value="PASSENGER_NO_SHOW">Passageiro não compareceu</option>
                <option value="UNSAFE_LOCATION">Local inseguro</option>
                <option value="VEHICLE_PROBLEM">Problema no veículo</option>
                <option value="OTHER">Outro</option>
              </select>
              <button class="btn danger" (click)="cancel()" [disabled]="busy()">Confirmar cancelamento</button>
              <p class="small muted">Antes do embarque, a corrida é oferecida a outro motorista. Desistências frequentes afetam sua conta.</p>
            </div>
          }
        </section>
        @if (chatOpen()) {
          <aside class="chat card"><ld-chat-panel [rideId]="r.id" [myUid]="auth.me()!.uid" [counterpartName]="r.passenger?.name ?? ''"
            [counterpartPhoto]="r.passenger?.photoPath" [closable]="true" (closed)="chatOpen.set(false)" /></aside>
        }
      </div>
    }
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1fr; }
    .map { height: 40dvh; }
    .panel { margin-top: -18px; position: relative; z-index: 2; display: flex; flex-direction: column; gap: 14px; }
    @media (min-width: 900px) {
      .layout { grid-template-columns: 400px 1fr; min-height: calc(100dvh - 60px); }
      .map { order: 2; height: auto; } .panel { margin: 0; border-radius: 0; box-shadow: none; }
      .chat { position: fixed; right: 24px; bottom: 24px; width: 380px; height: 560px; }
    }
    .chat { padding: 0; overflow: hidden; height: 70dvh; z-index: 40; }
    @media (max-width: 899px) { .chat { position: fixed; inset: auto 0 0 0; border-radius: 22px 22px 0 0; } }
    .trip { margin: 0; display: flex; flex-direction: column; gap: 6px; }
    .trip div { display: grid; grid-template-columns: 90px 1fr; gap: 8px; }
    dt { color: var(--ld-text-muted); font-size: .85rem; } dd { margin: 0; }
    .grow { flex: 1; }
  `,
})
export class RidePage implements OnInit {
  protected readonly auth = inject(AuthService);
  protected readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly rt = inject(RealtimeService);
  private readonly toast = inject(ToastService);
  private readonly loc = inject(DriverLocationService);
  private readonly destroyRef = inject(DestroyRef);
  readonly id = input.required<string>();
  protected readonly ride = signal<RideView | null>(null);
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly chatOpen = signal(false);
  protected readonly cancelOpen = signal(false);
  protected readonly rated = signal(false);
  protected readonly safetyOpen = signal(false);
  protected reason = 'PASSENGER_NO_SHOW';

  protected readonly next = computed(() => (this.ride() ? NEXT[this.ride()!.status] : undefined));
  protected readonly cancellable = computed(() => ['ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED'].includes(this.ride()?.status ?? ''));
  protected readonly navTarget = computed(() => {
    const r = this.ride();
    if (!r || r.status === 'COMPLETED' || r.status === 'CANCELLED') return null;
    return r.status === 'IN_PROGRESS' ? r.destination : r.origin;
  });
  protected readonly markers = computed<MapMarker[]>(() => {
    const r = this.ride();
    if (!r) return [];
    const m: MapMarker[] = [{ id: 'o', kind: 'origin', lat: r.origin.lat, lng: r.origin.lng },
      { id: 'd', kind: 'destination', lat: r.destination.lat, lng: r.destination.lng }];
    const p = this.loc.position();
    if (p) m.push({ id: 'me', kind: 'car', lat: p.coords.latitude, lng: p.coords.longitude, heading: p.coords.heading ?? 0 });
    return m;
  });

  ngOnInit(): void {
    this.loc.setInRide(true);
    this.loc.start();
    const sub = this.rt.doc<RideView>(`rides/${this.id()}`).subscribe({
      next: (r) => {
        this.ride.set(r);
        if (r && (r.status === 'CANCELLED' || r.status === 'COMPLETED')) this.loc.setInRide(false);
      },
      error: () => this.error.set('Sem acesso a esta corrida.'),
    });
    this.destroyRef.onDestroy(() => sub.unsubscribe());
  }

  /** Link universal do Google Maps (abre o app de navegação do dispositivo). */
  protected navUrl(t: { lat: number; lng: number }): string {
    return `https://www.google.com/maps/dir/?api=1&destination=${t.lat},${t.lng}&travelmode=driving`;
  }

  protected async advance(to: RideStatus): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post(`/driver/rides/${this.id()}/status`, { status: to });
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected onRated(): void {
    this.rated.set(true);
    this.toast.ok('Avaliação enviada.');
  }

  protected async cancel(): Promise<void> {
    this.busy.set(true);
    try {
      const r = await this.api.post<RideView>(`/driver/rides/${this.id()}/cancel`, { reasonCode: this.reason });
      this.cancelOpen.set(false);
      if (r?.status === 'SEARCHING_DRIVER') {
        // A corrida voltou à busca para outro motorista; este motorista perde o acesso a ela.
        this.loc.setInRide(false);
        this.toast.show('Corrida devolvida para outro motorista. Desistências frequentes afetam sua conta.', 'info', 7000);
        await this.router.navigateByUrl('/painel');
      }
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
