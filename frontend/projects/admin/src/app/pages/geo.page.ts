import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, MapComponent, MapPolygon, Place, PlaceSearchComponent } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface City { id: string; name: string; state: string; center: { lat: number; lng: number }; active: boolean; timezone: string; }
interface Area { id: string; cityId: string; name: string; type: string; polygon: { lat: number; lng: number }[]; active: boolean; fareMultiplierBp: number | null; notes: string | null; }

const TYPE: Record<string, { label: string; color: string }> = {
  COVERAGE: { label: 'Área de cobertura', color: '#4338ca' }, PICKUP_ZONE: { label: 'Zona de embarque', color: '#10b981' },
  RESTRICTED: { label: 'Zona restrita', color: '#c62828' }, FARE_REGION: { label: 'Região tarifária', color: '#b45309' },
};

/**
 * Gestão geográfica: municípios atendidos e polígonos operacionais. Ruas e endereços vêm da Google
 * Maps Platform (busca); apenas as áreas necessárias às regras ficam no banco.
 */
@Component({
  selector: 'admin-geo',
  imports: [FormsModule, MapComponent, PlaceSearchComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Gestão geográfica</h1>
    <div class="grid layout">
      <div class="stack">
        <section class="card stack">
          <h2>Cidades</h2>
          @for (c of cities(); track c.id) {
            <button class="item" [class.sel]="city()?.id === c.id" (click)="select(c)">
              <span><strong>{{ c.name }}</strong> - {{ c.state }}</span><span class="badge" [class.ok]="c.active">{{ c.active ? 'Ativa' : 'Inativa' }}</span>
            </button>
          } @empty { <p class="small muted">Nenhuma cidade cadastrada.</p> }
          @if (canEdit()) {
            <details><summary>Cadastrar município</summary>
              <div class="stack form">
                <input class="input" [(ngModel)]="newCity.name" placeholder="Nome do município" aria-label="Nome" />
                <input class="input" [(ngModel)]="newCity.state" placeholder="UF" maxlength="2" aria-label="UF" />
                <input class="input" [(ngModel)]="newCity.ibgeCode" placeholder="Código IBGE (opcional)" aria-label="IBGE" />
                <ld-place-search placeholder="Centro da cidade (busca ou lat,lng)" (selected)="newCity.center = $event" />
                <button class="btn primary" (click)="createCity()" [disabled]="!newCity.name || newCity.state.length !== 2 || !newCity.center">Cadastrar</button>
              </div>
            </details>
          }
        </section>
        @if (city(); as c) {
          <section class="card stack">
            <div class="row between"><h2>Áreas · {{ c.name }}</h2>
              @if (canEdit()) { <button class="btn ghost sm" (click)="toggleCity(c)">{{ c.active ? 'Desativar cidade' : 'Ativar cidade' }}</button> }</div>
            @for (a of areas(); track a.id) {
              <div class="row between item static">
                <span><span class="dot" [style.background]="type[a.type].color"></span><strong>{{ a.name }}</strong>
                  <span class="small muted"> · {{ type[a.type].label }} @if (a.fareMultiplierBp) { · {{ a.fareMultiplierBp / 10000 }}× }</span></span>
                @if (canEdit()) { <button class="btn ghost sm" (click)="toggleArea(a)">{{ a.active ? 'Desativar' : 'Ativar' }}</button> }
                @else { <span class="badge">{{ a.active ? 'Ativa' : 'Inativa' }}</span> }
              </div>
            } @empty { <p class="small muted">Nenhuma área. Sem área de cobertura ativa, corridas não podem ser solicitadas.</p> }
            @if (canEdit()) {
              <details [open]="drawing()" (toggle)="drawing.set($any($event.target).open)"><summary>Nova área (clique no mapa para desenhar)</summary>
                <div class="stack form">
                  <input class="input" [(ngModel)]="newArea.name" placeholder="Nome da área" aria-label="Nome da área" />
                  <select class="input" [(ngModel)]="newArea.type" aria-label="Tipo">
                    @for (t of typeKeys; track t) { <option [value]="t">{{ type[t].label }}</option> }</select>
                  @if (newArea.type === 'FARE_REGION') {
                    <input class="input" type="number" step="0.05" min="1" max="3" [(ngModel)]="newArea.multiplier" placeholder="Multiplicador (ex.: 1.2)" aria-label="Multiplicador" />
                  }
                  <label class="small" for="vtx">Ou informe os vértices (um "lat, lng" por linha)</label>
                  <textarea id="vtx" class="input mono" [(ngModel)]="vertexText" placeholder="-7.10, -34.88&#10;-7.10, -34.80&#10;-7.18, -34.80"></textarea>
                  <button class="btn ghost sm" type="button" (click)="applyVertices()" [disabled]="!vertexText.trim()">Usar vértices digitados</button>
                  <p class="small muted">{{ draft().length }} vértice(s). Mínimo 3.
                    @if (draft().length) { <button class="btn link" (click)="draft.set([])">Limpar</button> }</p>
                  <button class="btn primary" (click)="createArea(c)" [disabled]="draft().length < 3 || !newArea.name">Salvar área</button>
                </div>
              </details>
            }
          </section>
        }
      </div>
      <div class="card map-card">
        <ld-map [center]="city()?.center ?? { lat: -14.235, lng: -51.925 }" [zoom]="12" [polygons]="polygons()" [draftPath]="draft()"
                [fitToContent]="false" (mapClick)="addVertex($event)" />
      </div>
    </div>
  `,
  styles: `
    .layout { grid-template-columns: 380px 1fr; align-items: start; }
    .map-card { padding: 0; overflow: hidden; height: 75vh; position: sticky; top: 76px; } .map-card ld-map { height: 100%; }
    .item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 10px; border-radius: 10px;
      border: 1px solid var(--ld-border); background: var(--ld-surface); cursor: pointer; font: inherit; color: inherit; width: 100%; }
    .item.sel { border-color: var(--ld-primary); background: var(--ld-primary-soft); } .item.static { cursor: default; }
    .dot { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 6px; }
    .form { margin-top: 10px; } summary { cursor: pointer; font-weight: 600; }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } .map-card { position: static; height: 50vh; } }
  `,
})
export default class GeoPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  protected readonly type = TYPE;
  protected readonly typeKeys = Object.keys(TYPE);
  protected readonly cities = signal<City[]>([]);
  protected readonly city = signal<City | null>(null);
  protected readonly areas = signal<Area[]>([]);
  protected readonly draft = signal<{ lat: number; lng: number }[]>([]);
  protected readonly drawing = signal(false);
  protected newCity = { name: '', state: '', ibgeCode: '', center: null as Place | null };
  protected newArea = { name: '', type: 'COVERAGE', multiplier: 1.2 };
  protected vertexText = '';
  protected readonly canEdit = () => this.a.session.can('ADMIN', 'OWNER');
  protected readonly polygons = computed<MapPolygon[]>(() => this.areas().filter((a) => a.active)
    .map((a) => ({ id: a.id, path: a.polygon, color: TYPE[a.type].color, label: a.name })));

  async ngOnInit(): Promise<void> {
    this.cities.set(await this.api.get<City[]>('/admin/cities'));
    if (!this.city() && this.cities().length) await this.select(this.cities()[0]);
  }

  protected async select(c: City): Promise<void> {
    this.city.set(c);
    this.draft.set([]);
    this.areas.set(await this.api.get<Area[]>(`/admin/cities/${c.id}/areas`));
  }

  protected addVertex(p: { lat: number; lng: number }): void {
    if (this.drawing()) this.draft.update((d) => [...d, p]);
  }

  protected applyVertices(): void {
    const pts = this.vertexText.split(/\n+/).map((l) => l.split(/[,;\s]+/).filter(Boolean).map(Number))
      .filter((p) => p.length === 2 && p.every((n) => Number.isFinite(n)) && Math.abs(p[0]) <= 90 && Math.abs(p[1]) <= 180)
      .map(([lat, lng]) => ({ lat, lng }));
    if (pts.length < 3) {
      this.a.toast.error('Informe ao menos 3 vértices válidos no formato "lat, lng".');
      return;
    }
    this.draft.set(pts);
  }

  protected async createCity(): Promise<void> {
    const c = this.newCity;
    const created = await this.a.exec(() => this.api.post<City>('/admin/cities', { name: c.name, state: c.state.toUpperCase(),
      ibgeCode: c.ibgeCode || null, center: { lat: c.center!.lat, lng: c.center!.lng }, active: true }), 'Cidade cadastrada.');
    if (created) {
      this.newCity = { name: '', state: '', ibgeCode: '', center: null };
      await this.ngOnInit();
      await this.select(created);
    }
  }

  protected async toggleCity(c: City): Promise<void> {
    const ok = await this.a.withReason(c.active ? 'Desativar cidade' : 'Ativar cidade', (reason) => this.api.put(`/admin/cities/${c.id}`,
      { name: c.name, state: c.state, center: c.center, active: !c.active, timezone: c.timezone, reason }), { minLength: 5, ok: 'Cidade atualizada.' });
    if (ok) {
      await this.ngOnInit();
      this.city.set(this.cities().find((x) => x.id === c.id) ?? null);
    }
  }

  protected async createArea(c: City): Promise<void> {
    const body = { cityId: c.id, name: this.newArea.name, type: this.newArea.type, polygon: this.draft(), active: true,
      fareMultiplierBp: this.newArea.type === 'FARE_REGION' ? Math.round(this.newArea.multiplier * 10000) : null };
    const ok = await this.a.exec(() => this.api.post('/admin/areas', body), 'Área criada.');
    if (ok) {
      this.draft.set([]);
      this.vertexText = '';
      this.newArea.name = '';
      await this.select(c);
    }
  }

  protected async toggleArea(area: Area): Promise<void> {
    const ok = await this.a.withReason(area.active ? 'Desativar área' : 'Ativar área', (reason) => this.api.put(`/admin/areas/${area.id}`,
      { cityId: area.cityId, name: area.name, type: area.type, polygon: area.polygon, active: !area.active,
        fareMultiplierBp: area.fareMultiplierBp, notes: area.notes, reason }), { minLength: 5, ok: 'Área atualizada.' });
    if (ok) await this.select(this.city()!);
  }
}
