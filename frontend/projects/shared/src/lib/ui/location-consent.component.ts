import { ChangeDetectionStrategy, Component, OnInit, inject, input } from '@angular/core';
import { PresenceKind, PresenceService } from '../core/presence.service';

/** Convite (opcional) para compartilhar a localização com a equipe Lucena Drive. */
@Component({
  selector: 'ld-location-consent',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (presence.askNeeded()) {
      <aside class="consent card" role="dialog" aria-labelledby="lc-title">
        <strong id="lc-title">Compartilhar sua localização com a equipe?</strong>
        <p class="small">Com o app aberto, a equipe Lucena Drive poderá ver no mapa onde você está, junto com seu nome e
          se você é {{ kind() === 'DRIVER' ? 'motorista' : 'passageiro' }}. Isso ajuda no suporte e na segurança.
          <strong>É opcional</strong> e você pode desligar quando quiser em Perfil.</p>
        <div class="row">
          <button class="btn primary" type="button" (click)="presence.enable()">Permitir</button>
          <button class="btn ghost" type="button" (click)="presence.dismiss()">Agora não</button>
        </div>
      </aside>
    }
    @if (presence.enabled() && presence.browserDenied()) {
      <aside class="consent card small" role="status">
        A localização está bloqueada no navegador. Para compartilhar, libere a localização deste site nas configurações
        do navegador, ou desligue o compartilhamento em Perfil.
      </aside>
    }
  `,
  styles: `.consent { position: fixed; left: 12px; right: 12px; bottom: 76px; z-index: 50; max-width: 520px; margin: 0 auto;
    display: flex; flex-direction: column; gap: 8px; box-shadow: var(--ld-shadow-lg, 0 10px 30px rgba(0,0,0,.2)); }
    @media (min-width: 800px) { .consent { bottom: 20px; } } p { margin: 0; }`,
})
export class LocationConsentComponent implements OnInit {
  protected readonly presence = inject(PresenceService);
  readonly kind = input.required<PresenceKind>();

  ngOnInit(): void {
    void this.presence.start(this.kind());
  }
}
