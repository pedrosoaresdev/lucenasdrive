import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MapsLoaderService } from '../core/maps-loader.service';
import { Place } from '../core/models';
import { photonReverse, photonSearch } from '../core/osm-geocoder';

interface Suggestion { text: string; prediction?: google.maps.places.PlacePrediction; place?: Place; }

/**
 * Busca de endereços com a Places API (New). Sem chave do Google, oferece entrada de coordenadas
 * e a localização do dispositivo — sem fingir que a busca de endereços funciona.
 */
@Component({
  selector: 'ld-place-search',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="ps">
      <span class="dot" [style.background]="color()" aria-hidden="true"></span>
      <input class="input" [id]="inputId()" [placeholder]="placeholder()" [ngModel]="text()" (ngModelChange)="onInput($event)"
             [attr.aria-label]="label()" autocomplete="off" role="combobox" [attr.aria-expanded]="open()"
             [attr.aria-controls]="inputId() + '-list'" (keydown.escape)="open.set(false)" (blur)="onBlur()" />
      @if (allowGeolocation()) {
        <button type="button" class="btn sm ghost" (click)="useMyLocation()" [disabled]="locating()"
                title="Usar minha localização" aria-label="Usar minha localização">◎</button>
      }
    </div>
    @if (open() && suggestions().length) {
      <ul class="list card" [id]="inputId() + '-list'" role="listbox">
        @for (s of suggestions(); track s.text) {
          <li role="option" tabindex="0" (mousedown)="pick(s)" (keydown.enter)="pick(s)">{{ s.text }}</li>
        }
      </ul>
    }
    @if (!maps.configured && maps.provider !== 'osm') {
      <p class="hint small muted">Busca de endereços indisponível (Google Places não configurado). Informe
        coordenadas como <span class="mono">-7.1195, -34.8450</span>, toque no mapa ou use sua localização.</p>
    }
    @if (maps.provider === 'osm' && osmFailed()) {
      <p class="hint small muted">A busca de endereços está instável agora. Toque no mapa, use sua localização ou
        informe coordenadas como <span class="mono">-7.1195, -34.8450</span>.</p>
    }
    @if (error()) { <p class="small" style="color: var(--ld-danger)">{{ error() }}</p> }
  `,
  styles: `
    :host { display: block; position: relative; }
    .ps { display: flex; gap: 8px; align-items: center; }
    .dot { width: 12px; height: 12px; border-radius: 50%; flex: none; }
    .list { position: absolute; z-index: 30; left: 0; right: 0; margin: 4px 0 0; padding: 6px; list-style: none; max-height: 260px; overflow: auto; }
    .list li { padding: 10px 12px; border-radius: 10px; cursor: pointer; font-size: .92rem; }
    .list li:hover, .list li:focus { background: var(--ld-surface-2); outline: none; }
    .hint { margin: 6px 0 0 20px; }
  `,
})
export class PlaceSearchComponent {
  protected readonly maps = inject(MapsLoaderService);
  readonly placeholder = input('Buscar endereço');
  readonly label = input('Endereço');
  readonly inputId = input('place-' + Math.random().toString(36).slice(2, 8));
  readonly color = input('#4338ca');
  readonly allowGeolocation = input(true);
  readonly bias = input<{ lat: number; lng: number } | null>(null);
  readonly selected = output<Place>();

  protected readonly text = signal('');
  protected readonly suggestions = signal<Suggestion[]>([]);
  protected readonly open = signal(false);
  protected readonly locating = signal(false);
  protected readonly error = signal('');
  protected readonly osmFailed = signal(false);
  private abort?: AbortController;
  private session?: google.maps.places.AutocompleteSessionToken;
  private timer?: ReturnType<typeof setTimeout>;

  /** Define o texto exibido (ex.: endereço escolhido no mapa). */
  setText(v: string): void {
    this.text.set(v);
  }

  protected onInput(v: string): void {
    this.text.set(v);
    this.error.set('');
    const coords = v.match(/^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/);
    if (coords) {
      const lat = Number(coords[1]);
      const lng = Number(coords[2]);
      if (Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
        this.selected.emit({ lat, lng, address: `Coordenadas ${lat.toFixed(5)}, ${lng.toFixed(5)}` });
        this.open.set(false);
      }
      return;
    }
    if (!this.maps.hasMap || v.trim().length < 3) {
      this.suggestions.set([]);
      return;
    }
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.fetch(v), this.maps.provider === 'osm' ? 450 : 250);
  }

  /** OpenStreetMap: Photon (gratuito). Falhas não travam a tela: o usuário segue com mapa/localização/coordenadas. */
  private async fetchOsm(input: string): Promise<void> {
    this.abort?.abort();
    this.abort = new AbortController();
    try {
      const list = await photonSearch(this.maps.osmGeocoderUrl, input, this.bias(), this.abort.signal);
      this.osmFailed.set(false);
      this.suggestions.set(list.map((s) => ({ text: s.text, place: s.place })));
      this.open.set(true);
    } catch (e) {
      if ((e as Error)?.name !== 'AbortError') this.osmFailed.set(true);
    }
  }

  private async fetch(input: string): Promise<void> {
    if (this.maps.provider === 'osm') return this.fetchOsm(input);
    try {
      await this.maps.load();
      const lib = (await google.maps.importLibrary('places')) as google.maps.PlacesLibrary;
      this.session ??= new lib.AutocompleteSessionToken();
      const b = this.bias();
      const { suggestions } = await lib.AutocompleteSuggestion.fetchAutocompleteSuggestions({
        input, sessionToken: this.session, language: 'pt-BR', region: 'br', includedRegionCodes: ['br'],
        ...(b ? { locationBias: { center: b, radius: 30000 } } : {}),
      });
      this.suggestions.set(suggestions.filter((s) => s.placePrediction)
        .map((s) => ({ text: s.placePrediction!.text.text, prediction: s.placePrediction! })));
      this.open.set(true);
    } catch {
      this.error.set('Falha ao buscar endereços.');
    }
  }

  protected async pick(s: Suggestion): Promise<void> {
    this.open.set(false);
    this.text.set(s.text);
    if (s.place) {
      this.selected.emit(s.place);
      return;
    }
    if (!s.prediction) return;
    try {
      const place = s.prediction.toPlace();
      await place.fetchFields({ fields: ['location', 'formattedAddress'] });
      this.session = undefined;
      if (place.location) {
        this.selected.emit({ lat: place.location.lat(), lng: place.location.lng(),
          address: place.formattedAddress ?? s.text, placeId: place.id });
      }
    } catch {
      this.error.set('Não foi possível obter as coordenadas deste endereço.');
    }
  }

  protected onBlur(): void {
    setTimeout(() => this.open.set(false), 150);
  }

  protected useMyLocation(): void {
    if (!('geolocation' in navigator)) {
      this.error.set('Seu dispositivo não oferece geolocalização.');
      return;
    }
    this.locating.set(true);
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        this.locating.set(false);
        const place: Place = { lat: pos.coords.latitude, lng: pos.coords.longitude, address: 'Minha localização' };
        place.address = (await reverseGeocode(this.maps, place)) ?? place.address;
        this.text.set(place.address!);
        this.selected.emit(place);
      },
      (err) => {
        this.locating.set(false);
        this.error.set(err.code === err.PERMISSION_DENIED
          ? 'Permissão de localização negada. Autorize nas configurações do navegador.'
          : 'Não foi possível obter sua localização.');
      },
      { enableHighAccuracy: true, timeout: 10000, maximumAge: 15000 },
    );
  }
}

/** Geocodificação reversa: Geocoding API (Google) ou Photon (OpenStreetMap). Null se indisponível. */
export async function reverseGeocode(maps: MapsLoaderService, p: { lat: number; lng: number }): Promise<string | null> {
  if (maps.provider === 'osm') {
    try {
      return await photonReverse(maps.osmGeocoderUrl, p);
    } catch {
      return null;
    }
  }
  if (!maps.configured) return null;
  try {
    await maps.load();
    const { results } = await new google.maps.Geocoder().geocode({ location: p, language: 'pt-BR' });
    return results[0]?.formatted_address ?? null;
  } catch {
    return null;
  }
}
