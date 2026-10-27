import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, MapComponent, MapMarker, RideStatus, RideStatusPipe } from '@lucena/shared';

interface Live { id: string; code: string; status: RideStatus; cityId: string; originLat: number; originLng: number;
  destinationLat: number; destinationLng: number; driverPosition: { lat: number; lng: number; heading?: number } | null;
  driverName: string | null; plate: string | null; }

/** Corridas ativas no mapa (atualização a cada 10 s), sem dados pessoais além do primeiro nome. */
@Component({
  selector: 'admin-live-map',
  imports: [RouterLink, MapComponent, RideStatusPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Mapa operacional</h1>
    <div class="grid layout">
      <div class="card map-card"><ld-map [markers]="markers()" /></div>
      <div class="stack list">
        <p class="small muted">{{ rides().length }} corrida(s) ativa(s)</p>
        @for (r of rides(); track r.id) {
          <a class="card flat item" [routerLink]="['/corridas', r.id]"><span class="mono">{{ r.code }}</span>
            <span class="small">{{ r.status | rideStatus }} · {{ r.driverName ?? 'sem motorista' }} {{ r.plate ?? '' }}</span></a>
        }
      </div>
    </div>
  `,
  styles: `.layout { grid-template-columns: 1fr 320px; } .map-card { padding: 0; overflow: hidden; height: 70vh; }
    .map-card ld-map { height: 100%; } .list { max-height: 70vh; overflow: auto; } .item { display: flex; flex-direction: column; color: inherit; padding: 12px; }
    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } }`,
})
export default class LiveMapPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly rides = signal<Live[]>([]);
  protected readonly markers = computed<MapMarker[]>(() => this.rides().map((r): MapMarker => (r.driverPosition
    ? { id: r.id, kind: 'car', lat: r.driverPosition.lat, lng: r.driverPosition.lng, heading: r.driverPosition.heading, title: r.code }
    : { id: r.id, kind: 'origin', lat: r.originLat, lng: r.originLng, title: r.code + ' (aguardando)' })));

  ngOnInit(): void {
    void this.load();
    const t = setInterval(() => void this.load(), 10_000);
    this.destroyRef.onDestroy(() => clearInterval(t));
  }

  private async load(): Promise<void> {
    this.rides.set(await this.api.get<Live[]>('/admin/rides/live').catch(() => this.rides()));
  }
}
