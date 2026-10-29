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
 * Gestão geográfica: municípios atendidos e polígonos operacionais (criar, editar com vértices arrastáveis,
 * ativar/desativar). Ruas e endereços vêm do provedor de mapas; só as áreas das regras ficam no banco.
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
          @if (canEdit()) {
            <details class="card"><summary>Editar dados de {{ c.name }}</summary>
              <div class="stack form">
                <input class="input" [(ngModel)]="cityForm.name" placeholder="Nome do município" aria-label="Nome do município" />
                <input class="input" [(ngModel)]="cityForm.state" maxlength="2" placeholder="UF" aria-label="UF" />
                <ld-place-search placeholder="Novo centro da cidade (opcional)" (selected)="cityForm.center = $event" />
                <span class="small muted">Centro atual: {{ (cityForm.center ?? c.center).lat.toFixed(5) }}, {{ (cityForm.center ?? c.center).lng.toFixed(5) }}</span>
                <button class="btn primary" (click)="saveCity(c)" [disabled]="!cityForm.name || cityForm.state.length !== 2">Salvar cidade</button>
              </div>
            </details>
          }
          <section class="card stack">
            <div class="row between"><h2>Áreas · {{ c.name }}</h2>
              @if (canEdit()) { <button class="btn ghost sm" (click)="toggleCity(c)">{{ c.active ? 'Desativar cidade' : 'Ativar cidade' }}</button> }</div>
            @for (a of areas(); track a.id) {
              <div class="item static" [class.sel]="editing()?.id === a.id">
                <span><span class="dot" [style.background]="type[a.type].color"></span><strong>{{ a.name }}</strong>
                  <span class="small muted"> · {{ type[a.type].label }} @if (a.fareMultiplierBp) { · {{ a.fareMultiplierBp / 10000 }}× }
                    · {{ a.polygon.length }} vértices</span>
                  @if (!a.active) { <span class="badge">Inativa</span> }</span>
                <span class="row">
                  <button class="btn ghost sm" (click)="view(a)">Ver</button>
                  @if (canEdit()) {
                    <button class="btn ghost sm" (click)="edit(a)" [disabled]="editing()?.id === a.id">Editar</button>
                    <button class="btn ghost sm" (click)="toggleArea(a)">{{ a.active ? 'Desativar' : 'Ativar' }}</button>
                  }
                </span>
              </div>
            } @empty { <p class="small muted">Nenhuma área. Sem área de cobertura ativa, corridas não podem ser solicitadas.</p> }
            @if (canEdit()) {
              <details [open]="drawing()" (toggle)="onToggleForm($any($event.target).open)">
                <summary>{{ editing() ? 'Editando: ' + editing()!.name : 'Nova área (clique no mapa para desenhar)' }}</summary>
                <div class="stack form">
                  <input class="input" [(ngModel)]="newArea.name" placeholder="Nome da área" aria-label="Nome da área" />
                  <select class="input" [(ngModel)]="newArea.type" aria-label="Tipo">
                    @for (t of typeKeys; track t) { <option [value]="t">{{ type[t].label }}</option> }</select>
                  @if (newArea.type === 'FARE_REGION') {
                    <input class="input" type="number" step="0.05" min="1" max="3" [(ngModel)]="newArea.multiplier" placeholder="Multiplicador (ex.: 1.2)" aria-label="Multiplicador" />
                  }
                  <textarea class="input" [(ngModel)]="newArea.notes" maxlength="500" placeholder="Observações (opcional)" aria-label="Observações"></textarea>
                  <p class="small muted">Clique no mapa para adicionar vértices. Arraste os quadradinhos laranja para ajustar o desenho.</p>
                  <div class="row">
                    <button class="btn ghost sm" type="button" (click)="undoVertex()" [disabled]="!draft().length">Desfazer último</button>
                    <button class="btn ghost sm" type="button" (click)="draft.set([])" [disabled]="!draft().length">Limpar e redesenhar</button>
                  </div>
                  <label class="small" for="vtx">Ou informe os vértices (um "lat, lng" por linha)</label>
                  <textarea id="vtx" class="input mono" [(ngModel)]="vertexText" placeholder="-7.10, -34.88&#10;-7.10, -34.80&#10;-7.18, -34.80"></textarea>
                  <button class="btn ghost sm" type="button" (click)="applyVertices()" [disabled]="!vertexText.trim()">Usar vértices digitados</button>
                  <p class="small muted">{{ draft().length }} vértice(s). Mínimo 3.</p>
                  @if (editing(); as e) {
                    <div class="row">
                      <button class="btn primary" (click)="saveArea(e)" [disabled]="draft().length < 3 || !newArea.name">Salvar alterações</button>
                      <button class="btn ghost" (click)="cancelEdit()">Cancelar</button>
                    </div>
                  } @else {
                    <button class="btn primary" (click)="createArea(c)" [disabled]="draft().length < 3 || !newArea.name">Salvar área</button>
                  }
                </div>
              </details>
            }
          </section>
        }
      </div>
      <div class="stack">
        <ld-place-search placeholder="Ir para endereço, bairro ou cidade no mapa" label="Buscar local no mapa"
                         inputId="geo-search" (selected)="focus.set({ lat: $event.lat, lng: $event.lng, zoom: 15 })" />
        <div class="card map-card">
          <ld-map [center]="city()?.center ?? { lat: -14.235, lng: -51.925 }" [zoom]="12" [polygons]="polygons()" [draftPath]="draft()"
                  [focus]="focus()" [editableDraft]="drawing()" (draftChange)="draft.set($event)"
                  [fitToContent]="false" (mapClick)="addVertex($event)" />
        </div>
      </div>
    </div>
  `,
  styles: `
    .layout { grid-template-columns: 380px 1fr; align-items: start; }
    .map-card { padding: 0; overflow: hidden; height: 75vh; position: sticky; top: 76px; } .map-card ld-map { height: 100%; }
    .item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 10px; border-radius: 10px;
      border: 1px solid var(--ld-border); background: var(--ld-surface); cursor: pointer; font: inherit; color: inherit; width: 100%; }
    .item.sel { border-color: var(--ld-primary); background: var(--ld-primary-soft); } .item.static { cursor: default; flex-wrap: wrap; }
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
  protected newArea = { name: '', type: 'COVERAGE', multiplier: 1.2, notes: '' };
  protected cityForm = { name: '', state: '', center: null as Place | null };
  protected readonly editing = signal<Area | null>(null);
  protected readonly focus = signal<{ lat: number; lng: number; zoom?: number } | null>(null);
  protected vertexText = '';
  protected readonly canEdit = () => this.a.session.can('ADMIN', 'OWNER');
  protected readonly polygons = computed<MapPolygon[]>(() => this.areas()
    .filter((a) => a.active && a.id !== this.editing()?.id)
    .map((a) => ({ id: a.id, path: a.polygon, color: TYPE[a.type].color, label: `${a.name} (${TYPE[a.type].label})` })));

  async ngOnInit(): Promise<void> {
    this.cities.set(await this.api.get<City[]>('/admin/cities'));
    if (!this.city() && this.cities().length) await this.select(this.cities()[0]);
  }

  protected async select(c: City): Promise<void> {
    this.city.set(c);
    this.cancelEdit();
    this.cityForm = { name: c.name, state: c.state, center: null };
    this.focus.set({ ...c.center, zoom: 12 });
    this.areas.set(await this.api.get<Area[]>(`/admin/cities/${c.id}/areas`));
  }

  protected addVertex(p: { lat: number; lng: number }): void {
    if (this.drawing()) this.draft.update((d) => [...d, p]);
  }

  protected onToggleForm(open: boolean): void {
    this.drawing.set(open);
    if (!open && this.editing()) this.cancelEdit();
  }

  protected undoVertex(): void {
    this.draft.update((d) => d.slice(0, -1));
  }

  /** Centraliza o mapa na área (média dos vértices). */
  protected view(a: Area): void {
    const n = a.polygon.length || 1;
    this.focus.set({ lat: a.polygon.reduce((s, p) => s + p.lat, 0) / n, lng: a.polygon.reduce((s, p) => s + p.lng, 0) / n, zoom: 14 });
  }

  protected edit(a: Area): void {
    this.editing.set(a);
    this.newArea = { name: a.name, type: a.type, multiplier: a.fareMultiplierBp ? a.fareMultiplierBp / 10000 : 1.2, notes: a.notes ?? '' };
    this.draft.set(a.polygon.map((p) => ({ lat: p.lat, lng: p.lng })));
    this.vertexText = a.polygon.map((p) => `${p.lat.toFixed(6)}, ${p.lng.toFixed(6)}`).join('\n');
    this.drawing.set(true);
    this.view(a);
  }

  protected cancelEdit(): void {
    this.editing.set(null);
    this.draft.set([]);
    this.vertexText = '';
    this.newArea = { name: '', type: 'COVERAGE', multiplier: 1.2, notes: '' };
  }

  /** Atualiza nome, tipo, multiplicador, observações e polígono da área (justificativa auditada). */
  protected async saveArea(area: Area): Promise<void> {
    const body = { cityId: area.cityId, name: this.newArea.name, type: this.newArea.type, polygon: this.draft(), active: area.active,
      fareMultiplierBp: this.newArea.type === 'FARE_REGION' ? Math.round(this.newArea.multiplier * 10000) : null,
      notes: this.newArea.notes || null };
    const ok = await this.a.withReason('Salvar alterações da área', (reason) => this.api.put(`/admin/areas/${area.id}`, { ...body, reason }),
      { minLength: 5, ok: 'Área atualizada.' });
    if (ok) {
      this.cancelEdit();
      this.drawing.set(false);
      await this.select(this.city()!);
    }
  }

  protected async saveCity(c: City): Promise<void> {
    const f = this.cityForm;
    const center = f.center ? { lat: f.center.lat, lng: f.center.lng } : c.center;
    const ok = await this.a.withReason('Salvar dados da cidade', (reason) => this.api.put(`/admin/cities/${c.id}`,
      { name: f.name, state: f.state.toUpperCase(), center, active: c.active, timezone: c.timezone, reason }),
      { minLength: 5, ok: 'Cidade atualizada.' });
    if (ok) {
      await this.ngOnInit();
      const updated = this.cities().find((x) => x.id === c.id);
      if (updated) await this.select(updated);
    }
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
      fareMultiplierBp: this.newArea.type === 'FARE_REGION' ? Math.round(this.newArea.multiplier * 10000) : null,
      notes: this.newArea.notes || null };
    const ok = await this.a.exec(() => this.api.post('/admin/areas', body), 'Área criada.');
    if (ok) {
      this.draft.set([]);
      this.vertexText = '';
      this.newArea = { name: '', type: 'COVERAGE', multiplier: 1.2, notes: '' };
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
