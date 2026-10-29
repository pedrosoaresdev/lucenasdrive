import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ApiError, ApiService, DateTimePipe, MapComponent, MapMarker, MapPolygon, MapsLoaderService, reverseGeocode,
} from '@lucena/shared';
import { MapToolsComponent } from '../core/map-tools.component';

type Kind = 'PASSENGER' | 'DRIVER';
interface Position {
  userId: string; name: string; kind: Kind; lat: number; lng: number; accuracyM: number | null; updatedAt: string;
  ip: string | null; device: string | null; cityId: string | null; cityName: string | null;
}

const KIND_LABEL: Record<Kind, string> = { PASSENGER: 'Passageiro', DRIVER: 'Motorista' };

/**
 * "O olho de Deus": onde estão, agora, os passageiros e motoristas que ATIVARAM o compartilhamento de localização
 * (opcional, revogável). Mostra nome, tipo, cidade, IP e aparelho. Restrito a ADMIN/OWNER; acessos auditados.
 */
@Component({
  selector: 'admin-god-eye',
  imports: [MapComponent, MapToolsComponent, RouterLink, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="row between head">
      <h1>O olho de Deus</h1>
      <span class="row small muted">
        <label class="check"><input type="checkbox" [checked]="auto()" (change)="auto.set(!auto())" /> <span>Atualizar a cada 15 s</span></label>
        · {{ lastUpdate() ? (lastUpdate()! | dt) : 'carregando…' }}
        <button class="btn ghost sm" (click)="load()">Atualizar agora</button>
      </span>
    </div>
    <p class="small muted">Somente quem ativou "Compartilhar minha localização com a equipe" no app aparece aqui, com o app
      aberto nos últimos 5 minutos. Cada acesso a este painel fica registrado na auditoria.</p>

    <admin-map-tools [allowOutside]="true" (cityChange)="cityId.set($event); load()" (focus)="focus.set($event)"
                     (polygons)="areas.set($event)" />

    <div class="row filters" role="group" aria-label="Filtros">
      <label class="check"><input type="checkbox" [checked]="showDrivers()" (change)="showDrivers.set(!showDrivers())" />
        <span><span class="dot driver" aria-hidden="true"></span>Motoristas ({{ count('DRIVER') }})</span></label>
      <label class="check"><input type="checkbox" [checked]="showPassengers()" (change)="showPassengers.set(!showPassengers())" />
        <span><span class="dot passenger" aria-hidden="true"></span>Passageiros ({{ count('PASSENGER') }})</span></label>
      <input class="input search" type="search" placeholder="Buscar por nome, cidade ou IP" aria-label="Buscar por nome, cidade ou IP"
             [value]="search()" (input)="search.set($any($event.target).value)" />
    </div>

    @if (byCity().length) {
      <div class="row chips" aria-label="Pessoas por cidade">
        @for (c of byCity(); track c.name) { <span class="badge">{{ c.name }}: {{ c.total }}</span> }
      </div>
    }
    @if (error()) { <p class="alert err">{{ error() }}</p> }

    <div class="grid layout">
      <div class="card map-card">
        <ld-map [markers]="markers()" [polygons]="areas()" [focus]="focus()" [fitToContent]="fit()" [zoom]="12" />
      </div>
      <div class="stack list">
        @for (p of visible(); track p.userId) {
          <div class="card flat item" [class.sel]="selected() === p.userId">
            <span class="row between"><strong>{{ p.name }}</strong>
              <span class="badge" [class.info]="p.kind === 'DRIVER'" [class.err]="p.kind === 'PASSENGER'">{{ label[p.kind] }}</span></span>
            <dl class="small">
              <div><dt>Cidade</dt><dd>{{ p.cityName ?? (outside()[key(p)] ? 'Fora das áreas: ' + outside()[key(p)] : 'Fora das áreas atendidas') }}</dd></div>
              <div><dt>Posição</dt><dd class="mono">{{ p.lat.toFixed(5) }}, {{ p.lng.toFixed(5) }}@if (p.accuracyM) { · ±{{ p.accuracyM.toFixed(0) }} m }</dd></div>
              <div><dt>IP</dt><dd class="mono">{{ p.ip ?? '—' }}</dd></div>
              <div><dt>Aparelho</dt><dd>{{ p.device ?? '—' }}</dd></div>
              <div><dt>Visto em</dt><dd>{{ p.updatedAt | dt }}</dd></div>
            </dl>
            <span class="row">
              <button class="btn ghost sm" (click)="show(p)">Ver no mapa</button>
              <a class="btn link sm" [routerLink]="[p.kind === 'DRIVER' ? '/motoristas' : '/passageiros', p.userId]">Cadastro</a>
              <a class="btn link sm" [href]="'https://www.openstreetmap.org/?mlat=' + p.lat + '&mlon=' + p.lng + '#map=17/' + p.lat + '/' + p.lng"
                 target="_blank" rel="noopener">Abrir em nova aba</a>
            </span>
          </div>
        } @empty { <p class="muted small">Ninguém compartilhando a localização agora{{ cityId() ? ' nesta cidade' : '' }}.</p> }
      </div>
    </div>
  `,
  styles: `.head { align-items: baseline; flex-wrap: wrap; gap: 8px; } .filters { margin: 0 0 10px; gap: 16px; flex-wrap: wrap; }
    .search { max-width: 300px; } .chips { gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
    .layout { grid-template-columns: 1fr 380px; }
    .map-card { padding: 0; overflow: hidden; height: 72vh; } .map-card ld-map { height: 100%; }
    .list { max-height: 72vh; overflow: auto; } .item { display: flex; flex-direction: column; gap: 6px; padding: 12px; }
    .item.sel { border-color: var(--ld-primary); }
    dl { margin: 0; display: grid; gap: 2px; } dl div { display: grid; grid-template-columns: 70px 1fr; gap: 6px; }
    dt { color: var(--ld-text-muted); } dd { margin: 0; word-break: break-word; }
    .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }
    .dot.driver { background: #0d1321; } .dot.passenger { background: #e11d48; }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } .map-card { height: 55vh; } }`,
})
export default class GodEyePage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly maps = inject(MapsLoaderService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly label = KIND_LABEL;
  protected readonly positions = signal<Position[]>([]);
  protected readonly showDrivers = signal(true);
  protected readonly showPassengers = signal(true);
  protected readonly search = signal('');
  protected readonly cityId = signal('');
  protected readonly areas = signal<MapPolygon[]>([]);
  protected readonly focus = signal<{ lat: number; lng: number; zoom?: number } | null>(null);
  protected readonly selected = signal<string | null>(null);
  protected readonly auto = signal(true);
  protected readonly error = signal('');
  protected readonly lastUpdate = signal<string | null>(null);
  /** Nome do lugar (via geocodificação reversa) para quem está fora das áreas atendidas. */
  protected readonly outside = signal<Record<string, string>>({});
  /** Enquadra o mapa só na primeira carga, para não "pular" a cada atualização. */
  protected readonly fit = signal(true);

  protected readonly visible = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.positions().filter((p) => (p.kind === 'DRIVER' ? this.showDrivers() : this.showPassengers())
      && (!q || [p.name, p.cityName, p.ip, this.outside()[this.key(p)]].some((v) => v?.toLowerCase().includes(q))));
  });
  protected readonly markers = computed<MapMarker[]>(() => this.visible().map((p) => ({
    id: p.userId, lat: p.lat, lng: p.lng, kind: p.kind === 'DRIVER' ? 'car' : 'person',
    title: `${p.name} (${KIND_LABEL[p.kind]})`,
    info: `${p.name}\n${KIND_LABEL[p.kind]}\n${p.cityName ?? this.outside()[this.key(p)] ?? 'Fora das áreas atendidas'}\nIP: ${p.ip ?? '—'}`,
  })));
  protected readonly byCity = computed(() => {
    const m = new Map<string, number>();
    for (const p of this.positions()) {
      const n = p.cityName ?? 'Fora das áreas';
      m.set(n, (m.get(n) ?? 0) + 1);
    }
    return [...m.entries()].map(([name, total]) => ({ name, total })).sort((a, b) => b.total - a.total);
  });

  protected count(kind: Kind): number {
    return this.positions().filter((p) => p.kind === kind).length;
  }

  protected key(p: { lat: number; lng: number }): string {
    return `${p.lat.toFixed(2)},${p.lng.toFixed(2)}`;
  }

  protected show(p: Position): void {
    this.selected.set(p.userId);
    this.focus.set({ lat: p.lat, lng: p.lng, zoom: 17 });
  }

  ngOnInit(): void {
    void this.load();
    const t = setInterval(() => { if (this.auto()) void this.load(); }, 15_000);
    this.destroyRef.onDestroy(() => clearInterval(t));
  }

  protected async load(): Promise<void> {
    try {
      const id = this.cityId();
      const list = await this.api.get<Position[]>('/admin/god-eye/positions', id ? { cityId: id } : undefined);
      this.positions.set(list);
      this.error.set('');
      this.lastUpdate.set(new Date().toISOString());
      if (list.length) setTimeout(() => this.fit.set(false), 1500);
      void this.resolveOutside(list);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }

  /** Para quem está fora das cidades cadastradas, descobre o nome do lugar (com cache e poucas consultas). */
  private async resolveOutside(list: Position[]): Promise<void> {
    for (const p of list.filter((x) => !x.cityName).slice(0, 10)) {
      const k = this.key(p);
      if (this.outside()[k]) continue;
      const name = await reverseGeocode(this.maps, p);
      if (name) this.outside.update((o) => ({ ...o, [k]: name }));
    }
  }
}
