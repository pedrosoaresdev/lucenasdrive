import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  ApiError, ApiService, Estimate, IntegrationPendingComponent, KmPipe, MapComponent, MapMarker, MapsLoaderService,
  MinutesPipe, MoneyPipe, PassengerProfile, PaymentMethod, Place, PlaceSearchComponent, PublicConfig, Quote, RideView,
  StateViewComponent, ToastService, newIdempotencyKey, reverseGeocode,
} from '@lucena/shared';

/** Tela inicial: escolher origem/destino, ver preços por categoria, aplicar cupom e solicitar. */
@Component({
  selector: 'app-home',
  imports: [FormsModule, MapComponent, PlaceSearchComponent, MoneyPipe, KmPipe, MinutesPipe, StateViewComponent,
    IntegrationPendingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="layout">
      <ld-map class="map" [markers]="markers()" [center]="mapCenter()" [polyline]="estimate()?.route?.encodedPolyline"
              (mapClick)="onMapClick($event)" />
      <section class="sheet panel" aria-label="Solicitar corrida">
        @if (pendingRating(); as r) {
          <button class="alert info block-btn" (click)="router.navigate(['/corrida', r.id, 'avaliar'])">
            ★ Avalie sua última viagem com {{ r.driver?.name }}
          </button>
        }
        <h2>Para onde vamos?</h2>
        <div class="stack">
          <ld-place-search #originSearch placeholder="Local de embarque" label="Local de embarque" color="#10b981"
                           inputId="origin" [bias]="origin()" (selected)="setOrigin($event)" />
          <ld-place-search #destSearch placeholder="Destino" label="Destino" color="#4338ca" inputId="destination"
                           [allowGeolocation]="false" [bias]="origin()" (selected)="setDestination($event)" />
        </div>

        @if (!estimate()) {
          @if (shortcuts().length) {
            <div class="chips" aria-label="Locais salvos e recentes">
              @for (s of shortcuts(); track $index) {
                <button type="button" class="chip" (click)="useShortcut(s.place, s.label)">
                  <span aria-hidden="true">{{ s.icon }}</span> {{ s.label }}
                </button>
              }
            </div>
          }
          <p class="small muted">Toque no mapa para marcar embarque e destino.</p>
        }

        <div class="row coupon">
          <input class="input" [(ngModel)]="coupon" placeholder="Cupom (opcional)" aria-label="Cupom de desconto"
                 maxlength="30" autocapitalize="characters" />
          <button class="btn primary" type="button" (click)="quote()" [disabled]="!canQuote() || quoting()">
            {{ quoting() ? 'Calculando…' : estimate() ? 'Atualizar' : 'Ver preços' }}</button>
        </div>

        @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
        @if (quoting()) { <ld-state state="loading" title="Calculando rota e preço…" /> }

        @if (estimate(); as est) {
          <div class="stack fade-in">
            <div class="row between small muted">
              <span>{{ est.route.distanceMeters | km }} · {{ est.route.durationSeconds | minutes }}</span>
              <span>{{ est.cityName }}</span>
            </div>
            @if (est.approximateRoute) {
              <p class="alert warn small">Estimativa aproximada (linha reta): o serviço de rotas não respondeu agora.
                O valor exibido é o que será cobrado nesta corrida.</p>
            }
            <div class="options" role="radiogroup" aria-label="Categoria">
              @for (q of est.quotes; track q.quoteId) {
                <button type="button" role="radio" class="opt" [class.sel]="selected()?.quoteId === q.quoteId"
                        [attr.aria-checked]="selected()?.quoteId === q.quoteId" (click)="selected.set(q)">
                  <span class="car" aria-hidden="true">🚗</span>
                  <span class="info">
                    <strong>{{ q.categoryName }}</strong>
                    <span class="small muted">{{ q.availableDrivers ? q.availableDrivers + ' motorista(s) por perto' : 'Sem motoristas próximos agora' }}</span>
                  </span>
                  <span class="price">
                    @if (q.discountCents) { <s class="small muted">{{ q.totalCents | money }}</s> }
                    <strong>{{ q.finalCents | money }}</strong>
                  </span>
                </button>
              }
            </div>
            @if (selected()?.couponMessage; as m) {
              <p class="small" [class.ok]="selected()?.discountCents" [class.bad]="!selected()?.discountCents">{{ m }}</p>
            }
            <div class="field">
              <label for="pay">Pagamento</label>
              <select id="pay" class="input" [(ngModel)]="payment">
                @for (m of config()?.paymentMethods ?? ['CASH']; track m) { <option [value]="m">{{ payLabel[m] }}</option> }
              </select>
            </div>
            @if (config() && !config()!.paymentsConfigured) {
              <ld-integration-pending name="Pix e cartão" detail="Pagamentos digitais dependem da contratação do provedor. Disponível: dinheiro ao motorista." />
            }
            <button class="btn primary lg block" (click)="request()" [disabled]="!selected() || requesting()">
              {{ requesting() ? 'Solicitando…' : 'Solicitar ' + (selected()?.categoryName ?? '') }}</button>
            <p class="small muted center">Preço válido por 5 minutos. Ao confirmar, ele não muda durante a corrida.</p>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1fr; min-height: calc(100dvh - 124px); }
    .map { height: 42dvh; }
    .panel { margin-top: -18px; position: relative; z-index: 2; display: flex; flex-direction: column; gap: 14px; }
    @media (min-width: 900px) {
      .layout { grid-template-columns: 420px 1fr; min-height: calc(100dvh - 60px); }
      .map { order: 2; height: auto; }
      .panel { margin: 0; border-radius: 0; box-shadow: none; border-right: 1px solid var(--ld-border); overflow-y: auto; max-height: calc(100dvh - 60px); }
    }
    .chips { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px; }
    .chip { flex: none; border: 1px solid var(--ld-border); background: var(--ld-surface); border-radius: 999px; padding: 8px 14px;
      font: 600 .88rem var(--ld-font); color: var(--ld-text); cursor: pointer; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .coupon { flex-wrap: nowrap; } .coupon .input { text-transform: uppercase; }
    .options { display: flex; flex-direction: column; gap: 8px; }
    .opt { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border: 2px solid var(--ld-border); border-radius: 14px;
      background: var(--ld-surface); cursor: pointer; text-align: left; font: inherit; color: var(--ld-text); }
    .opt.sel { border-color: var(--ld-primary); background: var(--ld-primary-soft); }
    .car { font-size: 1.6rem; } .info { display: flex; flex-direction: column; flex: 1; }
    .price { display: flex; flex-direction: column; align-items: flex-end; }
    .ok { color: var(--ld-accent); } .bad { color: var(--ld-danger); } .center { text-align: center; margin: 0; }
    .block-btn { width: 100%; text-align: left; cursor: pointer; font: 600 .92rem var(--ld-font); }
  `,
})
export class HomePage implements OnInit {
  protected readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly maps = inject(MapsLoaderService);
  private readonly originSearch = viewChild.required<PlaceSearchComponent>('originSearch');
  private readonly destSearch = viewChild.required<PlaceSearchComponent>('destSearch');

  protected readonly origin = signal<Place | null>(null);
  protected readonly destination = signal<Place | null>(null);
  protected readonly estimate = signal<Estimate | null>(null);
  protected readonly selected = signal<Quote | null>(null);
  protected readonly quoting = signal(false);
  protected readonly requesting = signal(false);
  protected readonly error = signal('');
  protected readonly config = signal<PublicConfig | null>(null);
  protected readonly profile = signal<PassengerProfile | null>(null);
  protected readonly pendingRating = signal<RideView | null>(null);
  protected coupon = '';
  protected payment: PaymentMethod = 'CASH';
  private idemKey = newIdempotencyKey();
  protected readonly payLabel: Record<string, string> = { CASH: 'Dinheiro (pago ao motorista)', PIX: 'Pix', CARD: 'Cartão' };

  protected readonly canQuote = computed(() => !!this.origin() && !!this.destination());
  protected readonly mapCenter = computed(() => {
    const c = this.config()?.cities?.[0];
    return this.origin() ?? (c ? { lat: c.lat, lng: c.lng } : { lat: -14.235, lng: -51.925 });
  });
  protected readonly markers = computed<MapMarker[]>(() => {
    const m: MapMarker[] = [];
    const o = this.origin();
    const d = this.destination();
    if (o) m.push({ id: 'o', kind: 'origin', lat: o.lat, lng: o.lng, title: 'Embarque' });
    if (d) m.push({ id: 'd', kind: 'destination', lat: d.lat, lng: d.lng, title: 'Destino' });
    return m;
  });
  protected readonly shortcuts = computed(() => {
    const p = this.profile();
    if (!p) return [];
    const fav = p.favorites.map((f) => ({ icon: f.kind === 'HOME' ? '⌂' : f.kind === 'WORK' ? '◼' : '★',
      label: f.label || (f.kind === 'HOME' ? 'Casa' : f.kind === 'WORK' ? 'Trabalho' : 'Favorito'), place: f.place }));
    const rec = p.recentDestinations.slice(0, 5).map((r) => ({ icon: '↺', label: r.address ?? 'Recente', place: r }));
    return [...fav, ...rec];
  });

  async ngOnInit(): Promise<void> {
    try {
      const active = await this.api.get<RideView | null>('/rides/active');
      if (active) {
        await this.router.navigate(['/corrida', active.id]);
        return;
      }
    } catch { /* segue para a tela inicial */ }
    this.api.get<PublicConfig>('/public/config').then((c) => this.config.set(c)).catch(() => undefined);
    this.api.get<PassengerProfile>('/passengers/me').then((p) => this.profile.set(p)).catch(() => undefined);
    this.api.get<RideView | null>('/rides/pending-rating').then((r) => this.pendingRating.set(r)).catch(() => undefined);
  }

  protected setOrigin(p: Place): void {
    this.origin.set(p);
    this.invalidate();
  }

  protected setDestination(p: Place): void {
    this.destination.set(p);
    this.invalidate();
  }

  protected useShortcut(place: Place, label: string): void {
    if (!this.origin()) {
      this.setOrigin(place);
      this.originSearch().setText(place.address ?? label);
    } else {
      this.setDestination(place);
      this.destSearch().setText(place.address ?? label);
    }
  }

  protected async onMapClick(pt: { lat: number; lng: number }): Promise<void> {
    const address = (await reverseGeocode(this.maps, pt)) ?? `Ponto ${pt.lat.toFixed(5)}, ${pt.lng.toFixed(5)}`;
    const place = { ...pt, address };
    if (!this.origin()) {
      this.setOrigin(place);
      this.originSearch().setText(address);
    } else {
      this.setDestination(place);
      this.destSearch().setText(address);
    }
  }

  private invalidate(): void {
    this.estimate.set(null);
    this.selected.set(null);
    this.error.set('');
  }

  protected async quote(): Promise<void> {
    this.quoting.set(true);
    this.error.set('');
    try {
      const est = await this.api.post<Estimate>('/rides/estimate', {
        origin: this.origin(), destination: this.destination(), couponCode: this.coupon.trim() || null,
      });
      this.estimate.set(est);
      this.selected.set(est.quotes[0] ?? null);
      this.idemKey = newIdempotencyKey();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.quoting.set(false);
    }
  }

  protected async request(): Promise<void> {
    const q = this.selected();
    if (!q) return;
    this.requesting.set(true);
    this.error.set('');
    try {
      const ride = await this.api.post<RideView>('/rides', { quoteId: q.quoteId, paymentMethod: this.payment },
        { idempotencyKey: this.idemKey });
      await this.router.navigate(['/corrida', ride.id]);
    } catch (e) {
      const err = ApiError.from(e);
      if (err.code === 'ACTIVE_RIDE_EXISTS' && err.body?.details?.['rideId']) {
        await this.router.navigate(['/corrida', err.body.details['rideId']]);
        return;
      }
      if (err.code === 'QUOTE_EXPIRED') this.estimate.set(null);
      this.error.set(err.message);
      this.toast.error(err.message);
    } finally {
      this.requesting.set(false);
    }
  }
}
