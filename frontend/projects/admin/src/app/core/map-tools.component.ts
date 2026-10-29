import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { ApiService, MapPolygon, Place, PlaceSearchComponent } from '@lucena/shared';

export interface AdminCity { id: string; name: string; state: string; center: { lat: number; lng: number }; active: boolean; }
interface Area { id: string; name: string; type: string; polygon: { lat: number; lng: number }[]; active: boolean; }

const AREA_COLOR: Record<string, string> = { COVERAGE: '#4338ca', PICKUP_ZONE: '#10b981', RESTRICTED: '#c62828', FARE_REGION: '#b45309' };

/** Ferramentas dos mapas do admin: filtrar/ir para cidade, buscar endereço ou local, minha localização e áreas. */
@Component({
  selector: 'admin-map-tools',
  imports: [PlaceSearchComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="tools">
      <label class="field-inline">Cidade
        <select class="input" [value]="cityId()" (change)="pickCity($any($event.target).value)" aria-label="Cidade">
          <option value="">Todas as cidades</option>
          @for (c of cities(); track c.id) { <option [value]="c.id">{{ c.name }} - {{ c.state }}{{ c.active ? '' : ' (inativa)' }}</option> }
          @if (allowOutside()) { <option value="none">Fora das áreas atendidas</option> }
        </select>
      </label>
      <ld-place-search class="search" placeholder="Ir para endereço, bairro, cidade ou lat,lng" label="Buscar local no mapa"
                       inputId="map-tools-search" (selected)="goTo($event)" />
      <label class="check"><input type="checkbox" [checked]="showAreas()" (change)="toggleAreas()" /> <span>Mostrar áreas</span></label>
    </div>
  `,
  styles: `.tools { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin: 8px 0 14px; }
    .field-inline { display: flex; align-items: center; gap: 8px; font-weight: 600; font-size: .9rem; }
    .field-inline select { width: auto; min-width: 200px; } .search { flex: 1; min-width: 260px; }`,
})
export class MapToolsComponent implements OnInit {
  private readonly api = inject(ApiService);
  readonly allowOutside = input(false);
  readonly cityChange = output<string>();
  readonly focus = output<{ lat: number; lng: number; zoom?: number }>();
  readonly polygons = output<MapPolygon[]>();
  protected readonly cities = signal<AdminCity[]>([]);
  protected readonly cityId = signal('');
  protected readonly showAreas = signal(true);

  async ngOnInit(): Promise<void> {
    this.cities.set(await this.api.get<AdminCity[]>('/admin/cities').catch(() => []));
  }

  protected async pickCity(id: string): Promise<void> {
    this.cityId.set(id);
    this.cityChange.emit(id);
    const c = this.cities().find((x) => x.id === id);
    if (c) this.focus.emit({ ...c.center, zoom: 12 });
    await this.loadAreas();
  }

  protected goTo(p: Place): void {
    this.focus.emit({ lat: p.lat, lng: p.lng, zoom: 15 });
  }

  protected async toggleAreas(): Promise<void> {
    this.showAreas.set(!this.showAreas());
    await this.loadAreas();
  }

  private async loadAreas(): Promise<void> {
    const id = this.cityId();
    if (!this.showAreas() || !id || id === 'none') {
      this.polygons.emit([]);
      return;
    }
    const areas = await this.api.get<Area[]>(`/admin/cities/${id}/areas`).catch(() => [] as Area[]);
    this.polygons.emit(areas.filter((a) => a.active)
      .map((a) => ({ id: a.id, path: a.polygon, color: AREA_COLOR[a.type] ?? '#4338ca', label: a.name })));
  }
}
