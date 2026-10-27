import { Routes } from '@angular/router';
import { authGuard, guestGuard } from '@lucena/shared';
import { staffMfaGuard } from './core/guards';

const tag = { tag: 'Admin', registerLink: null, after: '/mfa' };

export const routes: Routes = [
  { path: 'entrar', canActivate: [guestGuard], loadComponent: () => import('@lucena/shared').then((m) => m.LoginPage),
    data: { ...tag, heading: 'Painel administrativo', subheading: 'Acesso restrito à equipe autorizada.' }, title: 'Admin · Lucena Drive' },
  { path: 'recuperar-senha', loadComponent: () => import('@lucena/shared').then((m) => m.ForgotPage), data: tag },
  { path: 'redefinir-senha', loadComponent: () => import('@lucena/shared').then((m) => m.ResetPage), data: tag },
  { path: 'termos', loadComponent: () => import('@lucena/shared').then((m) => m.LegalPage), data: { doc: 'terms' } },
  { path: 'privacidade', loadComponent: () => import('@lucena/shared').then((m) => m.LegalPage), data: { doc: 'privacy' } },
  { path: 'mfa', canActivate: [authGuard], loadComponent: () => import('./pages/mfa.page'), title: 'Segundo fator · Admin' },
  { path: 'sem-acesso', loadComponent: () => import('./pages/no-access.page'), title: 'Sem acesso' },
  {
    path: '',
    canActivate: [staffMfaGuard],
    loadComponent: () => import('./pages/layout.component').then((m) => m.LayoutComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'painel' },
      { path: 'painel', loadComponent: () => import('./pages/dashboard.page'), title: 'Dashboard · Admin' },
      { path: 'passageiros', loadComponent: () => import('./pages/users.page'), title: 'Passageiros · Admin' },
      { path: 'passageiros/:uid', loadComponent: () => import('./pages/user-detail.page'), title: 'Passageiro · Admin' },
      { path: 'motoristas', loadComponent: () => import('./pages/drivers.page'), title: 'Motoristas · Admin' },
      { path: 'motoristas/:driverId', loadComponent: () => import('./pages/driver-detail.page'), title: 'Motorista · Admin' },
      { path: 'corridas', loadComponent: () => import('./pages/rides.page'), title: 'Corridas · Admin' },
      { path: 'corridas/:rideId', loadComponent: () => import('./pages/ride-detail.page'), title: 'Corrida · Admin' },
      { path: 'mapa', loadComponent: () => import('./pages/live-map.page'), title: 'Mapa operacional · Admin' },
      { path: 'geografia', loadComponent: () => import('./pages/geo.page'), title: 'Gestão geográfica · Admin' },
      { path: 'tarifas', loadComponent: () => import('./pages/fares.page'), title: 'Tarifas · Admin' },
      { path: 'cupons', loadComponent: () => import('./pages/coupons.page'), title: 'Cupons · Admin' },
      { path: 'avaliacoes', loadComponent: () => import('./pages/ratings.page'), title: 'Avaliações · Admin' },
      { path: 'financeiro', loadComponent: () => import('./pages/finance.page'), title: 'Financeiro · Admin' },
      { path: 'suporte', loadComponent: () => import('./pages/support.page'), title: 'Suporte · Admin' },
      { path: 'equipe', loadComponent: () => import('./pages/staff.page'), title: 'Equipe · Admin' },
      { path: 'auditoria', loadComponent: () => import('./pages/audit.page'), title: 'Auditoria · Admin' },
      { path: 'privacidade-lgpd', loadComponent: () => import('./pages/privacy.page'), title: 'Solicitações LGPD · Admin' },
      { path: 'seguranca', loadComponent: () => import('./pages/security.page'), title: 'Minha segurança · Admin' },
    ],
  },
  { path: '**', redirectTo: '' },
];
