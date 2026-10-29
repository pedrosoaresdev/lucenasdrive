import {
  ChangeDetectionStrategy, Component, ElementRef, OnDestroy, afterNextRender, effect, inject, input, output,
  signal, viewChild,
} from '@angular/core';
import { MapsLoaderService } from '../core/maps-loader.service';

export interface MapMarker {
  id: string;
  lat: number;
  lng: number;
  kind: 'origin' | 'destination' | 'car' | 'me' | 'point' | 'person';
  title?: string;
  heading?: number;
  /** Texto exibido em um balão ao clicar no marcador. */
  info?: string;
}

export interface MapPolygon {
  id: string;
  path: { lat: number; lng: number }[];
  color?: string;
  label?: string;
}

const COLORS = { origin: '#10b981', destination: '#4338ca', me: '#0369a1', point: '#f59e0b', car: '#0d1321', person: '#e11d48' };

/**
 * Mapa Google Maps reutilizável (marcadores, rota codificada, polígonos e cliques).
 * Sem chave configurada, exibe um estado claro de integração pendente em vez de um mapa falso.
 */
@Component({
  selector: 'ld-map',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="wrap">
      <div #canvas class="canvas" role="application" aria-label="Mapa"></div>
      @if (maps.status() === 'unconfigured') {
        <div class="overlay">
          <strong>Mapa indisponível</strong>
          <span class="small">Integração pendente: configure a chave de navegador da Google Maps JavaScript API
            (mapsBrowserKey). As coordenadas continuam funcionando.</span>
          @if (markers().length) {
            <ul class="small mono">
              @for (m of markers(); track m.id) { <li>{{ label(m) }}: {{ m.lat.toFixed(5) }}, {{ m.lng.toFixed(5) }}</li> }
            </ul>
          }
        </div>
      } @else if (maps.status() === 'error') {
        <div class="overlay"><strong>Não foi possível carregar o mapa.</strong>
          <span class="small">Verifique a conexão e as restrições da chave.</span></div>
      }
    </div>
  `,
  styles: `
    :host { display: block; }
    .wrap { position: relative; width: 100%; height: 100%; min-height: 220px; border-radius: inherit; overflow: hidden;
      background: linear-gradient(135deg, var(--ld-surface-2), var(--ld-bg)); }
    .canvas { position: absolute; inset: 0; }
    .overlay { position: absolute; inset: 0; display: flex; flex-direction: column; gap: 6px; align-items: center;
      justify-content: center; text-align: center; padding: 24px; color: var(--ld-text-muted); }
    .overlay strong { color: var(--ld-text); }
    ul { list-style: none; padding: 0; margin: 8px 0 0; }
  `,
})
export class MapComponent implements OnDestroy {
  protected readonly maps = inject(MapsLoaderService);
  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('canvas');

  readonly center = input<{ lat: number; lng: number }>({ lat: -14.235, lng: -51.925 });
  readonly zoom = input(14);
  readonly markers = input<MapMarker[]>([]);
  readonly polyline = input<string | null | undefined>(null);
  readonly polygons = input<MapPolygon[]>([]);
  readonly draftPath = input<{ lat: number; lng: number }[]>([]);
  readonly fitToContent = input(true);
  readonly mapClick = output<{ lat: number; lng: number }>();

  private map?: google.maps.Map;
  private gMarkers = new Map<string, google.maps.Marker>();
  private gLine?: google.maps.Polyline;
  private gPolys: google.maps.Polygon[] = [];
  private gDraft?: google.maps.Polyline;
  private info?: google.maps.InfoWindow;
  private readonly ready = signal(false);
  private fitted = false;

  constructor() {
    afterNextRender(() => {
      if (!this.maps.configured) return;
      this.maps.load().then((g) => {
        this.map = new g.Map(this.canvas().nativeElement, {
          center: this.center(), zoom: this.zoom(), disableDefaultUI: true, zoomControl: true,
          clickableIcons: false, gestureHandling: 'greedy', mapId: this.maps.mapId,
        });
        this.map.addListener('click', (e: google.maps.MapMouseEvent) => {
          if (e.latLng) this.mapClick.emit({ lat: e.latLng.lat(), lng: e.latLng.lng() });
        });
        this.ready.set(true);
      }).catch(() => undefined);
    });

    effect(() => {
      if (!this.ready() || !this.map) return;
      this.syncMarkers(this.markers());
      this.syncLine(this.polyline());
      this.syncPolygons(this.polygons());
      this.syncDraft(this.draftPath());
      this.fit();
    });

    effect(() => {
      const c = this.center();
      if (this.ready() && this.map && !this.markers().length) this.map.panTo(c);
    });
  }

  protected label(m: MapMarker): string {
    return m.title ?? { origin: 'Embarque', destination: 'Destino', car: 'Motorista', me: 'Você', point: 'Ponto',
      person: 'Passageiro' }[m.kind];
  }

  private icon(m: MapMarker): google.maps.Symbol {
    if (m.kind === 'car') {
      return { path: 'M -6,6 L 0,-8 L 6,6 L 0,3 Z', fillColor: COLORS.car, fillOpacity: 1, strokeColor: '#fff',
        strokeWeight: 2, scale: 1.6, rotation: m.heading ?? 0 };
    }
    return { path: google.maps.SymbolPath.CIRCLE, scale: m.kind === 'me' ? 7 : 9, fillColor: COLORS[m.kind],
      fillOpacity: 1, strokeColor: '#fff', strokeWeight: 3 };
  }

  private syncMarkers(list: MapMarker[]): void {
    const ids = new Set(list.map((m) => m.id));
    for (const [id, mk] of this.gMarkers) {
      if (!ids.has(id)) { mk.setMap(null); this.gMarkers.delete(id); }
    }
    for (const m of list) {
      const existing = this.gMarkers.get(m.id);
      if (existing) {
        existing.setPosition(m);
        existing.setIcon(this.icon(m));
      } else {
        const mk = new google.maps.Marker({ map: this.map, position: m, icon: this.icon(m), title: this.label(m) });
        mk.addListener('click', () => {
          const text = this.markers().find((x) => x.id === m.id)?.info;
          if (!text) return;
          this.info ??= new google.maps.InfoWindow();
          const el = document.createElement('div');
          el.style.cssText = 'white-space:pre-line;font:600 13px system-ui;color:#0d1321';
          el.textContent = text; // texto puro: nomes de usuários nunca viram HTML
          this.info.setContent(el);
          this.info.open({ map: this.map, anchor: mk });
        });
        this.gMarkers.set(m.id, mk);
      }
    }
  }

  private syncLine(encoded: string | null | undefined): void {
    this.gLine?.setMap(null);
    this.gLine = undefined;
    if (encoded && google.maps.geometry?.encoding) {
      this.gLine = new google.maps.Polyline({ map: this.map, path: google.maps.geometry.encoding.decodePath(encoded),
        strokeColor: '#4338ca', strokeWeight: 5, strokeOpacity: .85 });
    }
  }

  private syncPolygons(list: MapPolygon[]): void {
    this.gPolys.forEach((p) => p.setMap(null));
    this.gPolys = list.map((p) => new google.maps.Polygon({ map: this.map, paths: p.path, strokeColor: p.color ?? '#4338ca',
      strokeWeight: 2, fillColor: p.color ?? '#4338ca', fillOpacity: .12, clickable: false }));
  }

  private syncDraft(path: { lat: number; lng: number }[]): void {
    this.gDraft?.setMap(null);
    this.gDraft = path.length
      ? new google.maps.Polyline({ map: this.map, path: [...path, path[0]], strokeColor: '#f59e0b', strokeWeight: 3 })
      : undefined;
  }

  private fit(): void {
    if (!this.fitToContent() || !this.map) return;
    const pts = this.markers();
    if (pts.length >= 2) {
      const b = new google.maps.LatLngBounds();
      pts.forEach((p) => b.extend(p));
      this.map.fitBounds(b, 60);
    } else if (pts.length === 1 && !this.fitted) {
      this.map.setCenter(pts[0]);
      this.fitted = true;
    }
  }

  ngOnDestroy(): void {
    this.gMarkers.forEach((m) => m.setMap(null));
    this.gLine?.setMap(null);
    this.gPolys.forEach((p) => p.setMap(null));
  }
}
