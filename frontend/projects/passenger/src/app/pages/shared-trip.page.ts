import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { ApiError, ApiService, LogoComponent, MapComponent, MapMarker, RideStatusPipe, StateViewComponent } from '@lucena/shared';

interface SharedRide {
  status: string; passengerFirstName: string; driverName: string | null; vehicle: string | null;
  vehicleColor: string | null; plate: string | null; originAddress: string; destinationAddress: string;
  driverPosition: { lat: number; lng: number; heading?: number } | null; etaSeconds: number | null; updatedAt: string;
}

/** Página pública do link de acompanhamento (sem login). Atualiza a cada 10 s enquanto o link vale. */
@Component({
  selector: 'app-shared-trip',
  imports: [LogoComponent, MapComponent, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <ld-logo />
      @if (error()) {
        <ld-state state="error" [message]="error()" [retryable]="false" />
      } @else if (!data()) {
        <ld-state state="loading" />
      } @else {
        @let d = data()!;
        <h1>Viagem de {{ d.passengerFirstName }}</h1>
        <span class="badge brand">{{ $any(d.status) | rideStatus }}</span>
        @if (markers().length) { <ld-map class="map" [markers]="markers()" /> }
        <dl class="card trip">
          @if (d.driverName) { <div><dt>Motorista</dt><dd>{{ d.driverName }}</dd></div>
            <div><dt>Veículo</dt><dd>{{ d.vehicle }} · {{ d.vehicleColor }} · <strong class="mono">{{ d.plate }}</strong></dd></div> }
          <div><dt>Embarque</dt><dd>{{ d.originAddress }}</dd></div>
          <div><dt>Destino</dt><dd>{{ d.destinationAddress }}</dd></div>
          @if (d.etaSeconds) { <div><dt>Previsão</dt><dd>~{{ Math.ceil(d.etaSeconds / 60) }} min</dd></div> }
        </dl>
        <p class="alert info small">Em emergência, ligue 190 (Polícia) ou 192 (SAMU). Este link é temporário e
          deixa de funcionar após o fim da viagem.</p>
      }
    </div>
  `,
  styles: `.page { max-width: 640px; padding-top: 20px; padding-bottom: 24px; } .map { height: 320px; border-radius: 16px; overflow: hidden; }
    .trip { margin: 0; display: flex; flex-direction: column; gap: 6px; } .trip div { display: grid; grid-template-columns: 90px 1fr; gap: 8px; }
    dt { color: var(--ld-text-muted); font-size: .85rem; } dd { margin: 0; }`,
})
export class SharedTripPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly token = input<string | undefined>(undefined);
  protected readonly data = signal<SharedRide | null>(null);
  protected readonly error = signal('');
  protected readonly Math = Math;
  protected readonly markers = computed<MapMarker[]>(() => {
    const p = this.data()?.driverPosition;
    return p ? [{ id: 'car', kind: 'car', lat: p.lat, lng: p.lng, heading: p.heading ?? 0 }] : [];
  });

  ngOnInit(): void {
    void this.load();
    const timer = setInterval(() => void this.load(), 10_000);
    this.destroyRef.onDestroy(() => clearInterval(timer));
  }

  private async load(): Promise<void> {
    if (this.error()) return;
    try {
      this.data.set(await this.api.get<SharedRide>(`/public/ride-shares/${encodeURIComponent(this.token() ?? '')}`));
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
