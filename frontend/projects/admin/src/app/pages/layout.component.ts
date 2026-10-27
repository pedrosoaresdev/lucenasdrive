import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService, LogoComponent, NotificationBellComponent, Role } from '@lucena/shared';
import { AdminSessionService } from '../core/admin-session.service';

interface NavItem { to: string; label: string; roles: Role[]; group: string; }

const NAV: NavItem[] = [
  { to: '/painel', label: 'Dashboard', roles: ['SUPPORT', 'FINANCE', 'ADMIN', 'OWNER'], group: 'Visão geral' },
  { to: '/mapa', label: 'Mapa operacional', roles: ['SUPPORT', 'ADMIN', 'OWNER'], group: 'Visão geral' },
  { to: '/corridas', label: 'Corridas', roles: ['SUPPORT', 'FINANCE', 'ADMIN', 'OWNER'], group: 'Operação' },
  { to: '/passageiros', label: 'Passageiros', roles: ['SUPPORT', 'ADMIN', 'OWNER'], group: 'Operação' },
  { to: '/motoristas', label: 'Motoristas', roles: ['SUPPORT', 'ADMIN', 'OWNER'], group: 'Operação' },
  { to: '/avaliacoes', label: 'Avaliações', roles: ['SUPPORT', 'ADMIN', 'OWNER'], group: 'Operação' },
  { to: '/suporte', label: 'Suporte', roles: ['SUPPORT', 'ADMIN', 'OWNER'], group: 'Operação' },
  { to: '/geografia', label: 'Gestão geográfica', roles: ['SUPPORT', 'FINANCE', 'ADMIN', 'OWNER'], group: 'Comercial' },
  { to: '/tarifas', label: 'Tarifas e comissões', roles: ['SUPPORT', 'FINANCE', 'ADMIN', 'OWNER'], group: 'Comercial' },
  { to: '/cupons', label: 'Cupons e promoções', roles: ['FINANCE', 'ADMIN', 'OWNER'], group: 'Comercial' },
  { to: '/financeiro', label: 'Financeiro', roles: ['FINANCE', 'ADMIN', 'OWNER'], group: 'Financeiro' },
  { to: '/equipe', label: 'Equipe e permissões', roles: ['ADMIN', 'OWNER'], group: 'Governança' },
  { to: '/auditoria', label: 'Auditoria', roles: ['ADMIN', 'OWNER'], group: 'Governança' },
  { to: '/privacidade-lgpd', label: 'Solicitações LGPD', roles: ['ADMIN', 'OWNER'], group: 'Governança' },
  { to: '/seguranca', label: 'Minha segurança', roles: ['SUPPORT', 'FINANCE', 'ADMIN', 'OWNER'], group: 'Governança' },
];

/** Layout com navegação lateral. Itens ocultos por papel são só conveniência: o backend nega o acesso. */
@Component({
  selector: 'admin-layout',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, LogoComponent, NotificationBellComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="shell" [class.open]="menuOpen()">
      <aside class="side" aria-label="Menu administrativo">
        <div class="brand"><ld-logo [size]="30" tag="Admin" /></div>
        <nav>
          @for (g of groups(); track g.name) {
            <p class="group">{{ g.name }}</p>
            @for (i of g.items; track i.to) {
              <a [routerLink]="i.to" routerLinkActive="active" (click)="menuOpen.set(false)">{{ i.label }}</a>
            }
          }
        </nav>
      </aside>
      <div class="main">
        <header class="topbar">
          <button class="btn ghost icon-btn menu" (click)="menuOpen.set(!menuOpen())" aria-label="Abrir menu">☰</button>
          <span class="spacer"></span>
          @if (auth.me(); as me) {
            <span class="small muted who">{{ me.email }} · {{ me.roles.join(', ') }}</span>
            <ld-notification-bell [uid]="me.uid" />
          }
          <button class="btn ghost sm" (click)="session.logout()">Sair</button>
        </header>
        <main class="content"><router-outlet /></main>
      </div>
    </div>
  `,
  styles: `
    .shell { display: grid; grid-template-columns: 250px 1fr; min-height: 100dvh; }
    .side { background: var(--ld-ink); color: #cfd3e1; padding: 16px 12px; position: sticky; top: 0; height: 100dvh; overflow-y: auto; }
    .brand { padding: 4px 8px 16px; } .brand ::ng-deep .word { color: #fff; }
    nav { display: flex; flex-direction: column; gap: 2px; }
    .group { font-size: .7rem; text-transform: uppercase; letter-spacing: .08em; color: #8890a6; margin: 14px 8px 4px; }
    nav a { color: #cfd3e1; padding: 9px 12px; border-radius: 10px; font-weight: 550; font-size: .92rem; }
    nav a:hover { background: rgba(255,255,255,.06); text-decoration: none; }
    nav a.active { background: var(--ld-primary); color: #fff; }
    .main { min-width: 0; } .content { padding: 24px; max-width: 1400px; }
    .menu { display: none; }
    @media (max-width: 960px) {
      .shell { grid-template-columns: 1fr; }
      .side { position: fixed; z-index: 60; width: 260px; transform: translateX(-100%); transition: transform .2s; }
      .shell.open .side { transform: none; }
      .menu { display: inline-flex; } .who { display: none; } .content { padding: 16px; }
    }
  `,
})
export class LayoutComponent {
  protected readonly auth = inject(AuthService);
  protected readonly session = inject(AdminSessionService);
  protected readonly menuOpen = signal(false);
  protected readonly groups = computed(() => {
    const roles = this.auth.roles();
    const visible = NAV.filter((i) => i.roles.some((r) => roles.includes(r)));
    const names = [...new Set(visible.map((i) => i.group))];
    return names.map((name) => ({ name, items: visible.filter((i) => i.group === name) }));
  });
}
