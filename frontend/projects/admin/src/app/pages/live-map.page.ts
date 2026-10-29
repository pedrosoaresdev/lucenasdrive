import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, ApiService, DateTimePipe, MapComponent, MapMarker, MapPolygon, RideStatus, RideStatusPipe } from '@lucena/shared';
import { MapToolsComponent } from '../core/map-tools.component';

interface Live { id: string; code: string; status: RideStatus; cityId: string; originLat: number; originLng: number;
  destinationLat: number; destinationLng: number; driverPosition: { lat: number; lng: number; heading?: number } | null;
  driverName: string | null; plate: string | null; }

const STATUSES: RideStatus[] = ['SEARCHING_DRIVER', 'ACCEPTED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'IN_PROGRESS'];

/** Corridas ativas no mapa, com filtros por cidade, etapa e busca; atualização a cada 10 s. */
@Component({
  selector: 'admin-live-map',
  imports: [RouterLink, MapComponent, MapToolsComponent, RideStatusPipe, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="row between head">
      <h1>Mapa operacional</h1>
      <span class="row small muted">
        <label class="check"><input type="checkbox" [checked]="auto()" (change)="auto.set(!auto())" /> <span>Atualizar a cada 10 s</span></label>
        · {{ lastUpdate() ? (lastUpdate()! | dt) : 'carregando…' }}
        <button class="btn ghost sm" (click)="load()">Atualizar agora</button>
      </span>
    </div>
    <admin-map-tools (cityChange)="cityId.set($event); load()" (focus)="focus.set($event)" (polygons)="areas.set($event)" />
    <div class="row filters" role="group" aria-label="Etapas">
      @for (s of statuses; track s) {
        <label class="check"><input type="checkbox" [checked]="enabled().includes(s)" (change)="toggle(s)" />
          <span>{{ s | rideStatus }} ({{ count(s) }})</span></label>
      }
      <input class="input search" type="search" placeholder="Código, motorista ou placa" aria-label="Buscar corrida"
             [value]="search()" (input)="search.set($any($event.target).value)" />
      <label class="check"><input type="checkbox" [checked]="showRoutes()" (change)="showRoutes.set(!showRoutes())" /> <span>Embarque e destino</span></label>
    </div>
    @if (error()) { <p class="alert err">{{ error() }}</p> }
    <div class="grid layout">
      <div class="card map-card"><ld-map [markers]="markers()" [polygons]="areas()" [focus]="focus()" [fitToContent]="fit()" /></div>
      <div class="stack list">
        <p class="small muted">{{ visible().length }} de {{ rides().length }} corrida(s) ativa(s)</p>
        @for (r of visible(); track r.id) {
          <div class="card flat item" [class.sel]="selected() === r.id">
            <span class="row between"><span class="mono">{{ r.code }}</span><span class="badge info">{{ r.status | rideStatus }}</span></span>
            <span class="small">{{ r.driverName ?? 'Sem motorista ainda' }} {{ r.plate ? '· ' + r.plate : '' }}</span>
            <span class="row">
              <button class="btn ghost sm" (click)="show(r)">Ver no mapa</button>
              <a class="btn link sm" [routerLink]="['/corridas', r.id]">Detalhes</a>
            </span>
          </div>
        } @empty { <p class="muted small">Nenhuma corrida ativa com esses filtros.</p> }
      </div>
    </div>
  `,
  styles: `.head { align-items: baseline; flex-wrap: wrap; gap: 8px; } .filters { gap: 14px; flex-wrap: wrap; margin-bottom: 12px; }
    .search { max-width: 240px; } .layout { grid-template-columns: 1fr 340px; }
    .map-card { padding: 0; overflow: hidden; height: 70vh; } .map-card ld-map { height: 100%; }
    .list { max-height: 70vh; overflow: auto; } .item { display: flex; flex-direction: column; gap: 4px; padding: 12px; }
    .item.sel { border-color: var(--ld-primary); }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } .map-card { height: 55vh; } }`,
})
export default class LiveMapPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly statuses = STATUSES;
  protected readonly rides = signal<Live[]>([]);
  protected readonly enabled = signal<RideStatus[]>([...STATUSES]);
  protected readonly search = signal('');
  protected readonly cityId = signal('');
  protected readonly areas = signal<MapPolygon[]>([]);
  protected readonly focus = signal<{ lat: number; lng: number; zoom?: number } | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly showRoutes = signal(false);
  protected readonly auto = signal(true);
  protected readonly error = signal('');
  protected readonly lastUpdate = signal<string | null>(null);
  protected readonly fit = signal(true);

  protected readonly visible = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.rides().filter((r) => this.enabled().includes(r.status)
      && (!q || [r.code, r.driverName, r.plate].some((v) => v?.toLowerCase().includes(q))));
  });
  protected readonly markers = computed<MapMarker[]>(() => this.visible().flatMap((r): MapMarker[] => {
    const info = `${r.code}\n${r.driverName ?? 'Sem motorista ainda'}${r.plate ? ' · ' + r.plate : ''}`;
    const main: MapMarker = r.driverPosition
      ? { id: r.id, kind: 'car', lat: r.driverPosition.lat, lng: r.driverPosition.lng, heading: r.driverPosition.heading, title: r.code, info }
      : { id: r.id, kind: 'origin', lat: r.originLat, lng: r.originLng, title: r.code + ' (aguardando)', info };
    if (!this.showRoutes()) return [main];
    return [main,
      ...(r.driverPosition ? [{ id: r.id + '-o', kind: 'origin' as const, lat: r.originLat, lng: r.originLng, title: r.code + ' · embarque' }] : []),
      { id: r.id + '-d', kind: 'destination', lat: r.destinationLat, lng: r.destinationLng, title: r.code + ' · destino' }];
  }));

  protected count(s: RideStatus): number {
    return this.rides().filter((r) => r.status === s).length;
  }

  protected toggle(s: RideStatus): void {
    this.enabled.update((l) => (l.includes(s) ? l.filter((x) => x !== s) : [...l, s]));
  }

  protected show(r: Live): void {
    this.selected.set(r.id);
    const p = r.driverPosition ?? { lat: r.originLat, lng: r.originLng };
    this.focus.set({ lat: p.lat, lng: p.lng, zoom: 16 });
  }

  ngOnInit(): void {
    void this.load();
    const t = setInterval(() => { if (this.auto()) void this.load(); }, 10_000);
    this.destroyRef.onDestroy(() => clearInterval(t));
  }

  protected async load(): Promise<void> {
    try {
      const id = this.cityId();
      const list = await this.api.get<Live[]>('/admin/rides/live', id && id !== 'none' ? { cityId: id } : undefined);
      this.rides.set(list);
      this.error.set('');
      this.lastUpdate.set(new Date().toISOString());
      if (list.length) setTimeout(() => this.fit.set(false), 1500);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
