import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, AvatarComponent, LogoComponent, NotificationBellComponent } from '@lucena/shared';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LogoComponent, NotificationBellComponent, AvatarComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="topbar">
      <a routerLink="/inicio" aria-label="Início"><ld-logo [size]="30" /></a>
      <nav class="nav-tabs desktop" aria-label="Navegação principal">
        <a routerLink="/inicio" routerLinkActive="active">Viajar</a>
        <a routerLink="/historico" routerLinkActive="active">Corridas</a>
        <a routerLink="/suporte" routerLinkActive="active">Ajuda</a>
      </nav>
      <span class="spacer"></span>
      @if (auth.me(); as me) {
        <ld-notification-bell [uid]="me.uid" />
        <a routerLink="/perfil" aria-label="Meu perfil"><ld-avatar [path]="me.photoUrl" [name]="me.fullName ?? ''" [size]="36" /></a>
      }
    </header>
    <main><router-outlet /></main>
    <nav class="bottom" aria-label="Navegação">
      <a routerLink="/inicio" routerLinkActive="active">Viajar</a>
      <a routerLink="/historico" routerLinkActive="active">Corridas</a>
      <a routerLink="/suporte" routerLinkActive="active">Ajuda</a>
      <a routerLink="/perfil" routerLinkActive="active">Perfil</a>
    </nav>
  `,
  styles: `
    main { min-height: calc(100dvh - 60px); padding-bottom: 64px; }
    .bottom { position: fixed; bottom: 0; left: 0; right: 0; z-index: 25; display: grid; grid-template-columns: repeat(4, 1fr);
      background: var(--ld-surface); border-top: 1px solid var(--ld-border); padding-bottom: env(safe-area-inset-bottom); }
    .bottom a { text-align: center; padding: 12px 4px; font-weight: 600; font-size: .85rem; color: var(--ld-text-muted); }
    .bottom a.active { color: var(--ld-primary); }
    @media (min-width: 800px) { .bottom { display: none; } main { padding-bottom: 0; } }
    @media (max-width: 799px) { .desktop { display: none; } }
  `,
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly router = inject(Router);
}
