import {
  ChangeDetectionStrategy, Component, ElementRef, OnDestroy, afterNextRender, effect, inject, input, output,
  signal, viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';
import { MapsLoaderService } from '../core/maps-loader.service';
import { decodePolyline } from '../core/osm-geocoder';

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
 * Mapa reutilizável (marcadores, rota codificada, polígonos e cliques) com dois motores:
 * Google Maps (com chave) ou OpenStreetMap via Leaflet (gratuito, sem chave).
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
    .wrap { position: relative; isolation: isolate; width: 100%; height: 100%; min-height: 220px; border-radius: inherit; overflow: hidden;
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
  /** Centraliza o mapa (ex.: cidade ou endereço buscado). Mude a referência do objeto para repetir. */
  readonly focus = input<{ lat: number; lng: number; zoom?: number } | null>(null);
  /** Vértices do rascunho podem ser arrastados (edição de regiões). */
  readonly editableDraft = input(false);
  readonly draftChange = output<{ lat: number; lng: number }[]>();

  private map?: google.maps.Map;
  private gMarkers = new Map<string, google.maps.Marker>();
  private gLine?: google.maps.Polyline;
  private gPolys: google.maps.Polygon[] = [];
  private gDraft?: google.maps.Polyline;
  private info?: google.maps.InfoWindow;
  private readonly ready = signal(false);
  private L?: typeof Leaflet;
  private lmap?: Leaflet.Map;
  private lMarkers = new Map<string, Leaflet.Marker>();
  private lLine?: Leaflet.Polyline;
  private lPolys: Leaflet.Polygon[] = [];
  private lDraft?: Leaflet.Polyline;
  private lHandles: Leaflet.Marker[] = [];
  private gHandles: google.maps.Marker[] = [];
  private fitted = false;

  constructor() {
    afterNextRender(() => {
      if (this.maps.provider === 'osm') {
        void this.initLeaflet();
        return;
      }
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
      if (!this.ready() || !this.lmap) return;
      this.lSyncMarkers(this.markers());
      this.lSyncLine(this.polyline());
      this.lSyncPolygons(this.polygons());
      this.lSyncDraft(this.draftPath());
      this.lFit();
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
      const f = this.focus();
      if (!f || !this.ready()) return;
      this.lmap?.setView(f, f.zoom ?? 14);
      if (this.map) {
        this.map.panTo(f);
        this.map.setZoom(f.zoom ?? 14);
      }
    });

    effect(() => {
      const c = this.center();
      if (this.ready() && this.map && !this.markers().length) this.map.panTo(c);
      if (this.ready() && this.lmap && !this.markers().length) this.lmap.panTo(c);
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
    this.gHandles.forEach((h) => h.setMap(null));
    this.gHandles = [];
    if (!this.editableDraft()) return;
    path.forEach((pt, i) => {
      const h = new google.maps.Marker({ map: this.map, position: pt, draggable: true, title: `Vértice ${i + 1}`,
        icon: { path: google.maps.SymbolPath.CIRCLE, scale: 6, fillColor: '#f59e0b', fillOpacity: 1, strokeColor: '#fff', strokeWeight: 2 } });
      h.addListener('dragend', (e: google.maps.MapMouseEvent) => {
        if (!e.latLng) return;
        this.draftChange.emit(this.draftPath().map((p, j) => (j === i ? { lat: e.latLng!.lat(), lng: e.latLng!.lng() } : p)));
      });
      this.gHandles.push(h);
    });
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

  // ------------------------------------------------------------------ OpenStreetMap (Leaflet)

  private async initLeaflet(): Promise<void> {
    try {
      const L = await this.maps.loadLeaflet();
      this.L = L;
      this.lmap = L.map(this.canvas().nativeElement, { zoomControl: true, attributionControl: true })
        .setView(this.center(), this.zoom());
      L.tileLayer(this.maps.osmTileUrl, {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>',
      }).addTo(this.lmap);
      this.lmap.on('click', (e: Leaflet.LeafletMouseEvent) => this.mapClick.emit({ lat: e.latlng.lat, lng: e.latlng.lng }));
      // o contêiner pode mudar de tamanho depois da renderização (layouts responsivos)
      setTimeout(() => this.lmap?.invalidateSize(), 200);
      this.ready.set(true);
    } catch {
      this.maps.status.set('error');
    }
  }

  private lIcon(m: MapMarker): Leaflet.DivIcon {
    const color = COLORS[m.kind];
    const html = m.kind === 'car'
      ? `<svg width="26" height="26" viewBox="-10 -10 20 20" style="transform:rotate(${m.heading ?? 0}deg)"><path d="M -6,6 L 0,-8 L 6,6 L 0,3 Z" fill="${color}" stroke="#fff" stroke-width="1.5"/></svg>`
      : `<span style="display:block;width:${m.kind === 'me' ? 14 : 18}px;height:${m.kind === 'me' ? 14 : 18}px;border-radius:50%;background:${color};border:3px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.4)"></span>`;
    const size = m.kind === 'car' ? 26 : m.kind === 'me' ? 20 : 24;
    return this.L!.divIcon({ html, className: 'ld-osm-marker', iconSize: [size, size], iconAnchor: [size / 2, size / 2] });
  }

  private lSyncMarkers(list: MapMarker[]): void {
    const L = this.L!;
    const ids = new Set(list.map((m) => m.id));
    for (const [id, mk] of this.lMarkers) {
      if (!ids.has(id)) { mk.remove(); this.lMarkers.delete(id); }
    }
    for (const m of list) {
      let mk = this.lMarkers.get(m.id);
      if (mk) {
        mk.setLatLng(m);
        mk.setIcon(this.lIcon(m));
      } else {
        mk = L.marker(m, { icon: this.lIcon(m), title: this.label(m), keyboard: true }).addTo(this.lmap!);
        this.lMarkers.set(m.id, mk);
      }
      mk.unbindPopup();
      if (m.info) {
        const el = document.createElement('div');
        el.style.cssText = 'white-space:pre-line;font:600 13px system-ui';
        el.textContent = m.info; // texto puro: nomes de usuários nunca viram HTML
        mk.bindPopup(el);
      }
    }
  }

  private lSyncLine(encoded: string | null | undefined): void {
    this.lLine?.remove();
    this.lLine = encoded
      ? this.L!.polyline(decodePolyline(encoded), { color: '#4338ca', weight: 5, opacity: .85 }).addTo(this.lmap!)
      : undefined;
  }

  private lSyncPolygons(list: MapPolygon[]): void {
    this.lPolys.forEach((p) => p.remove());
    this.lPolys = list.map((p) => {
      const poly = this.L!.polygon(p.path, { color: p.color ?? '#4338ca', weight: 2,
        fillColor: p.color ?? '#4338ca', fillOpacity: .12, interactive: !!p.label && !this.editableDraft() });
      if (p.label) poly.bindTooltip(p.label, { sticky: true });
      return poly.addTo(this.lmap!);
    });
  }

  private lSyncDraft(path: { lat: number; lng: number }[]): void {
    this.lDraft?.remove();
    this.lDraft = path.length
      ? this.L!.polyline([...path, path[0]], { color: '#f59e0b', weight: 3 }).addTo(this.lmap!)
      : undefined;
    this.lHandles.forEach((h) => h.remove());
    this.lHandles = [];
    if (!this.editableDraft()) return;
    path.forEach((pt, i) => {
      const h = this.L!.marker(pt, { draggable: true, title: `Vértice ${i + 1} (arraste para mover)`,
        icon: this.L!.divIcon({ className: 'ld-osm-marker', iconSize: [14, 14], iconAnchor: [7, 7],
          html: '<span style="display:block;width:14px;height:14px;border-radius:3px;background:#f59e0b;border:2px solid #fff;box-shadow:0 1px 3px rgba(0,0,0,.5)"></span>' }) })
        .addTo(this.lmap!);
      h.on('dragend', () => {
        const ll = h.getLatLng();
        const next = this.draftPath().map((p, j) => (j === i ? { lat: ll.lat, lng: ll.lng } : p));
        this.draftChange.emit(next);
      });
      this.lHandles.push(h);
    });
  }

  private lFit(): void {
    if (!this.fitToContent() || !this.lmap) return;
    const pts = this.markers();
    if (pts.length >= 2) {
      this.lmap.fitBounds(this.L!.latLngBounds(pts.map((p) => [p.lat, p.lng] as [number, number])), { padding: [60, 60] });
    } else if (pts.length === 1 && !this.fitted) {
      this.lmap.setView(pts[0], Math.max(this.lmap.getZoom(), 15));
      this.fitted = true;
    }
  }

  ngOnDestroy(): void {
    this.lmap?.remove();
    this.gMarkers.forEach((m) => m.setMap(null));
    this.gLine?.setMap(null);
    this.gPolys.forEach((p) => p.setMap(null));
  }
}
