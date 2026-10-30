import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { where } from 'firebase/firestore';
import {
  ApiError, ApiService, AuthService, HeatCell, KmPipe, MapComponent, MapMarker, MinutesPipe, MoneyPipe, RealtimeService,
  RideOffer, RideView, StateViewComponent, ToastService, toDate,
} from '@lucena/shared';
import { DriverLocationService } from '../location.service';
import { DRIVER_STATUS_LABEL } from './onboarding.page';

interface Overview { profile: { status: string; statusReason: string | null; ratingAvg: number; ratingCount: number; completedRides: number };
  online: boolean; currentRideId: string | null; missingRequirements: string[]; }
interface Finance { periods: { period: string; rides: number; earningsCents: number }[]; }

/** Painel do motorista: disponibilidade, ofertas em tempo real, corrida atual e indicadores. */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, MapComponent, MoneyPipe, KmPipe, MinutesPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!overview()) { <ld-state [state]="error() ? 'error' : 'loading'" [message]="error()" (retry)="load()" /> }
    @else {
      @let o = overview()!;
      <div class="layout">
        <ld-map class="map" [markers]="markers()" [center]="center()" [heat]="showHeat() ? heat() : []" />
        <section class="sheet panel">
          @if (o.profile.status !== 'APPROVED') {
            <div class="alert warn">
              <strong>{{ statusLabel[o.profile.status] }}</strong>
              @if (o.profile.statusReason) { <span> · {{ o.profile.statusReason }}</span> }
              <p class="small">Você poderá ficar online após a aprovação do cadastro.</p>
              <a class="btn sm primary" routerLink="/documentos">Ver documentos</a>
            </div>
          }

          <div class="toggle-row">
            <div>
              <strong>{{ online() ? 'Você está online' : 'Você está offline' }}</strong>
              <p class="small muted">{{ online() ? 'Recebendo ofertas de corrida próximas.' : 'Fique online para receber corridas.' }}</p>
            </div>
            <button class="switch" role="switch" [attr.aria-checked]="online()" [disabled]="busy() || o.profile.status !== 'APPROVED'"
                    (click)="toggle()" aria-label="Disponibilidade"><span></span></button>
          </div>
          @if (loc.error()) { <p class="alert warn small">{{ loc.error() }}</p> }

          @if (o.currentRideId) {
            <a class="btn primary lg block" [routerLink]="['/corrida', o.currentRideId]">Abrir corrida em andamento</a>
          }

          @for (offer of offers(); track offer.id) {
            <article class="offer card fade-in" aria-live="assertive">
              <div class="row between"><span class="badge brand">Nova corrida · {{ offer.category }}</span>
                <span class="timer" [class.low]="secondsLeft(offer) <= 5">{{ secondsLeft(offer) }}s</span></div>
              <div class="earn">{{ offer.estimatedEarningCents | money }} <span class="small muted">ganho estimado</span></div>
              <p class="small"><strong>Embarque</strong>
                @if (offer.etaToPickupSeconds) { a ~{{ offer.etaToPickupSeconds | minutes }} ({{ offer.distanceToPickupMeters | km }}) }
                @else { a {{ offer.distanceToPickupMeters | km }} }
                · {{ offer.pickupAddress }}</p>
              <p class="small"><strong>Destino</strong> {{ offer.destinationAddress }} · {{ offer.tripDistanceMeters | km }} · {{ offer.tripDurationSeconds | minutes }}</p>
              <p class="small muted">Passageiro(a): {{ offer.passengerName }}
                @if (offer.passengerRating) { · ★ {{ offer.passengerRating.toFixed(2) }} } @else { · sem avaliações }</p>
              <div class="row">
                <button class="btn accent lg grow" (click)="accept(offer)" [disabled]="busy()">Aceitar</button>
                <button class="btn ghost lg" (click)="decline(offer)" [disabled]="busy()">Recusar</button>
              </div>
            </article>
          }
          @if (online() && !offers().length && !o.currentRideId) {
            <p class="waiting muted small pulse">Aguardando ofertas…</p>
          }
          @if (online()) {
            <label class="check small heat-toggle"><input type="checkbox" [checked]="showHeat()" (change)="showHeat.set(!showHeat())" />
              <span>Mapa de calor de demanda
                {{ heat().length ? '(' + heat().length + ' região(ões) com pedidos nos últimos 30 min)' : '(sem concentração de pedidos agora)' }}</span></label>
          }

          <div class="grid kpis">
            <div class="kpi card flat"><span class="label">Hoje</span><span class="value">{{ today()?.earningsCents ?? 0 | money }}</span>
              <span class="small muted">{{ today()?.rides ?? 0 }} corrida(s)</span></div>
            <div class="kpi card flat"><span class="label">Avaliação</span>
              <span class="value">★ {{ o.profile.ratingCount ? o.profile.ratingAvg.toFixed(2) : '—' }}</span>
              <span class="small muted">{{ o.profile.ratingCount }} avaliações</span></div>
            <div class="kpi card flat"><span class="label">Viagens</span><span class="value">{{ o.profile.completedRides }}</span>
              <span class="small muted">concluídas</span></div>
          </div>
        </section>
      </div>
    }
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1fr; }
    .map { height: 38dvh; }
    .panel { margin-top: -18px; position: relative; z-index: 2; display: flex; flex-direction: column; gap: 14px; }
    @media (min-width: 900px) {
      .layout { grid-template-columns: 420px 1fr; min-height: calc(100dvh - 60px); }
      .map { order: 2; height: auto; } .panel { margin: 0; border-radius: 0; box-shadow: none; }
    }
    .toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
    .switch { width: 64px; height: 36px; border-radius: 18px; border: 0; background: var(--ld-border); position: relative; cursor: pointer; flex: none; }
    .switch span { position: absolute; top: 4px; left: 4px; width: 28px; height: 28px; border-radius: 50%; background: #fff; transition: transform .2s; box-shadow: var(--ld-shadow); }
    .switch[aria-checked='true'] { background: var(--ld-accent); }
    .switch[aria-checked='true'] span { transform: translateX(28px); }
    .switch:disabled { opacity: .5; cursor: not-allowed; }
    .offer { border: 2px solid var(--ld-primary); display: flex; flex-direction: column; gap: 8px; }
    .earn { font-size: 1.6rem; font-weight: 800; }
    .timer { font-weight: 800; } .timer.low { color: var(--ld-danger); }
    .grow { flex: 1; } .waiting { text-align: center; }
    .kpis { grid-template-columns: repeat(3, 1fr); gap: 8px; } .kpi { padding: 12px; } .kpi .value { font-size: 1.15rem; }
  `,
})
export class DashboardPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly rt = inject(RealtimeService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly loc = inject(DriverLocationService);
  protected readonly statusLabel = DRIVER_STATUS_LABEL;

  protected readonly overview = signal<Overview | null>(null);
  protected readonly finance = signal<Finance | null>(null);
  protected readonly offers = signal<RideOffer[]>([]);
  protected readonly online = signal(false);
  /** Regiões com pedidos recentes (agregadas, sem identificar passageiros). */
  protected readonly heat = signal<HeatCell[]>([]);
  protected readonly showHeat = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  private readonly now = signal(Date.now());

  protected readonly today = computed(() => this.finance()?.periods.find((p) => p.period === 'DAY'));
  protected readonly center = computed(() => {
    const p = this.loc.position();
    return p ? { lat: p.coords.latitude, lng: p.coords.longitude } : { lat: -14.235, lng: -51.925 };
  });
  protected readonly markers = computed<MapMarker[]>(() => {
    const p = this.loc.position();
    const list: MapMarker[] = p ? [{ id: 'me', kind: 'car', lat: p.coords.latitude, lng: p.coords.longitude,
      heading: p.coords.heading ?? 0, title: 'Você' }] : [];
    for (const o of this.offers()) list.push({ id: o.id, kind: 'origin', lat: o.pickupLat, lng: o.pickupLng, title: 'Embarque' });
    return list;
  });

  protected loadHeat(): void {
    if (!this.online()) return;
    void this.api.get<HeatCell[]>('/driver/demand-heatmap').then((h) => this.heat.set(h)).catch(() => undefined);
  }

  ngOnInit(): void {
    void this.load();
    const uid = this.auth.me()!.uid;
    const sub = this.rt.query<RideOffer>('rideOffers', where('driverId', '==', uid), where('status', '==', 'PENDING'))
      .subscribe({ next: (list) => {
        const fresh = list.filter((o) => (toDate(o.expiresAt)?.getTime() ?? 0) > Date.now());
        if (fresh.length > this.offers().length) navigator.vibrate?.(300);
        this.offers.set(fresh);
      }, error: () => undefined });
    this.loadHeat();
    const heatTimer = setInterval(() => this.loadHeat(), 60_000);
    this.destroyRef.onDestroy(() => clearInterval(heatTimer));
    const timer = setInterval(() => {
      this.now.set(Date.now());
      this.offers.update((l) => l.filter((o) => (toDate(o.expiresAt)?.getTime() ?? 0) > Date.now()));
    }, 1000);
    this.destroyRef.onDestroy(() => {
      sub.unsubscribe();
      clearInterval(timer);
    });
  }

  protected async load(): Promise<void> {
    this.error.set('');
    try {
      const o = await this.api.get<Overview>('/drivers/me');
      this.overview.set(o);
      this.online.set(o.online);
      this.loadHeat();
      this.loc.setInRide(!!o.currentRideId);
      if (o.online) this.loc.start();
      this.finance.set(await this.api.get<Finance>('/drivers/me/finance').catch(() => null));
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }

  protected secondsLeft(o: RideOffer): number {
    return Math.max(0, Math.round(((toDate(o.expiresAt)?.getTime() ?? 0) - this.now()) / 1000));
  }

  protected async toggle(): Promise<void> {
    this.busy.set(true);
    try {
      if (this.online()) {
        await this.api.post('/drivers/me/offline');
        this.loc.stop();
        this.online.set(false);
      } else {
        const pos = await this.loc.current();
        this.loc.position.set(pos);
        await this.api.post('/drivers/me/online', { lat: pos.coords.latitude, lng: pos.coords.longitude });
        this.loc.start();
        this.online.set(true);
        this.loadHeat();
        this.toast.ok('Você está online.');
      }
    } catch (e) {
      this.toast.error(e instanceof Error && !(e as ApiError).code ? e.message : ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async accept(o: RideOffer): Promise<void> {
    this.busy.set(true);
    try {
      const ride = await this.api.post<RideView>(`/driver/offers/${o.rideId}/accept`);
      this.loc.setInRide(true);
      await this.router.navigate(['/corrida', ride.id]);
    } catch (e) {
      const err = ApiError.from(e);
      this.toast.error(err.code === 'RIDE_ALREADY_ACCEPTED' ? 'Outro motorista aceitou primeiro.' : err.message);
      this.offers.update((l) => l.filter((x) => x.id !== o.id));
    } finally {
      this.busy.set(false);
    }
  }

  protected async decline(o: RideOffer): Promise<void> {
    this.offers.update((l) => l.filter((x) => x.id !== o.id));
    await this.api.post(`/driver/offers/${o.rideId}/decline`).catch(() => undefined);
  }
}
