import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, ApiService, DateTimePipe, MapComponent, MapMarker } from '@lucena/shared';

type Kind = 'PASSENGER' | 'DRIVER';
interface Position { userId: string; name: string; kind: Kind; lat: number; lng: number; accuracyM: number | null; updatedAt: string; }

const KIND_LABEL: Record<Kind, string> = { PASSENGER: 'Passageiro', DRIVER: 'Motorista' };

/**
 * "O olho de Deus": onde estão, agora, os passageiros e motoristas que ATIVARAM o compartilhamento de localização
 * (opcional, revogável). Atualiza a cada 15 s. Restrito a ADMIN/OWNER no servidor; acessos são auditados.
 */
@Component({
  selector: 'admin-god-eye',
  imports: [MapComponent, RouterLink, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="row between head">
      <h1>O olho de Deus</h1>
      <span class="small muted">Atualizado {{ lastUpdate() ? (lastUpdate()! | dt) : '…' }} · a cada 15 s</span>
    </div>
    <p class="small muted">Somente quem ativou "Compartilhar minha localização com a equipe" no app aparece aqui, com o app
      aberto nos últimos 5 minutos. Cada acesso a este painel fica registrado na auditoria.</p>
    <div class="row filters" role="group" aria-label="Filtros">
      <label class="check"><input type="checkbox" [checked]="showDrivers()" (change)="showDrivers.set(!showDrivers())" />
        <span><span class="dot driver" aria-hidden="true"></span>Motoristas ({{ count('DRIVER') }})</span></label>
      <label class="check"><input type="checkbox" [checked]="showPassengers()" (change)="showPassengers.set(!showPassengers())" />
        <span><span class="dot passenger" aria-hidden="true"></span>Passageiros ({{ count('PASSENGER') }})</span></label>
      <input class="input search" type="search" placeholder="Buscar pelo nome" aria-label="Buscar pelo nome"
             [value]="search()" (input)="search.set($any($event.target).value)" />
    </div>
    @if (error()) { <p class="alert err">{{ error() }}</p> }
    <div class="grid layout">
      <div class="card map-card"><ld-map [markers]="markers()" [fitToContent]="fit()" [zoom]="12" /></div>
      <div class="stack list">
        @for (p of visible(); track p.userId) {
          <div class="card flat item">
            <span class="row between"><strong>{{ p.name }}</strong>
              <span class="badge" [class.info]="p.kind === 'DRIVER'" [class.err]="p.kind === 'PASSENGER'">{{ label[p.kind] }}</span></span>
            <span class="small muted mono">{{ p.lat.toFixed(5) }}, {{ p.lng.toFixed(5) }}
              @if (p.accuracyM) { · ±{{ p.accuracyM.toFixed(0) }} m }</span>
            <span class="small muted">{{ p.updatedAt | dt }}</span>
            <a class="small" [routerLink]="[p.kind === 'DRIVER' ? '/motoristas' : '/passageiros', p.userId]">Ver cadastro</a>
          </div>
        } @empty { <p class="muted small">Ninguém compartilhando a localização agora.</p> }
      </div>
    </div>
  `,
  styles: `.head { align-items: baseline; } .filters { margin: 8px 0 14px; gap: 16px; flex-wrap: wrap; }
    .search { max-width: 260px; } .layout { grid-template-columns: 1fr 340px; }
    .map-card { padding: 0; overflow: hidden; height: 72vh; } .map-card ld-map { height: 100%; }
    .list { max-height: 72vh; overflow: auto; } .item { display: flex; flex-direction: column; gap: 2px; padding: 12px; }
    .dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }
    .dot.driver { background: #0d1321; } .dot.passenger { background: #e11d48; }
    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } .map-card { height: 50vh; } }`,
})
export default class GodEyePage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly label = KIND_LABEL;
  protected readonly positions = signal<Position[]>([]);
  protected readonly showDrivers = signal(true);
  protected readonly showPassengers = signal(true);
  protected readonly search = signal('');
  protected readonly error = signal('');
  protected readonly lastUpdate = signal<string | null>(null);
  /** Enquadra o mapa só na primeira carga, para não "pular" a cada atualização. */
  protected readonly fit = signal(true);

  protected readonly visible = computed(() => {
    const q = this.search().trim().toLowerCase();
    return this.positions().filter((p) => (p.kind === 'DRIVER' ? this.showDrivers() : this.showPassengers())
      && (!q || p.name?.toLowerCase().includes(q)));
  });
  protected readonly markers = computed<MapMarker[]>(() => this.visible().map((p) => ({
    id: p.userId, lat: p.lat, lng: p.lng, kind: p.kind === 'DRIVER' ? 'car' : 'person',
    title: `${p.name} (${KIND_LABEL[p.kind]})`, info: `${p.name}\n${KIND_LABEL[p.kind]}`,
  })));

  protected count(kind: Kind): number {
    return this.positions().filter((p) => p.kind === kind).length;
  }

  ngOnInit(): void {
    void this.load();
    const t = setInterval(() => void this.load(), 15_000);
    this.destroyRef.onDestroy(() => clearInterval(t));
  }

  private async load(): Promise<void> {
    try {
      const list = await this.api.get<Position[]>('/admin/god-eye/positions');
      this.positions.set(list);
      this.error.set('');
      this.lastUpdate.set(new Date().toISOString());
      if (list.length) setTimeout(() => this.fit.set(false), 1500);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
