import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, LocationConsentComponent, AvatarComponent, LogoComponent, NotificationBellComponent } from '@lucena/shared';

@Component({
  selector: 'app-shell',
  imports: [LocationConsentComponent, RouterOutlet, RouterLink, RouterLinkActive, LogoComponent, NotificationBellComponent, AvatarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="topbar">
      <a routerLink="/painel" aria-label="Painel"><ld-logo [size]="30" tag="Motorista" /></a>
      <nav class="nav-tabs desktop" aria-label="Navegação principal">
        @for (l of links; track l.to) { <a [routerLink]="l.to" routerLinkActive="active">{{ l.label }}</a> }
      </nav>
      <span class="spacer"></span>
      @if (auth.me(); as me) {
        <ld-notification-bell [uid]="me.uid" />
        <a routerLink="/perfil" aria-label="Perfil"><ld-avatar [path]="me.photoUrl" [name]="me.fullName ?? ''" [size]="36" /></a>
      }
    </header>
    <main><router-outlet /></main>
    <ld-location-consent kind="DRIVER" />
    <nav class="bottom" aria-label="Navegação">
      @for (l of links.slice(0, 4); track l.to) { <a [routerLink]="l.to" routerLinkActive="active">{{ l.label }}</a> }
    </nav>
  `,
  styles: `
    main { min-height: calc(100dvh - 60px); padding-bottom: 64px; }
    .bottom { position: fixed; bottom: 0; left: 0; right: 0; z-index: 25; display: grid; grid-template-columns: repeat(4, 1fr);
      background: var(--ld-surface); border-top: 1px solid var(--ld-border); padding-bottom: env(safe-area-inset-bottom); }
    .bottom a { text-align: center; padding: 12px 4px; font-weight: 600; font-size: .85rem; color: var(--ld-text-muted); }
    .bottom a.active { color: var(--ld-primary); }
    @media (min-width: 900px) { .bottom { display: none; } main { padding-bottom: 0; } }
    @media (max-width: 899px) { .desktop { display: none; } }
  `,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly links = [
    { to: '/painel', label: 'Dirigir' }, { to: '/ganhos', label: 'Ganhos' }, { to: '/historico', label: 'Corridas' },
    { to: '/suporte', label: 'Ajuda' }, { to: '/avaliacoes', label: 'Avaliações' }, { to: '/documentos', label: 'Documentos' },
  ];
}
