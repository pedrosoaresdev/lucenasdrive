import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from '@lucena/shared';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./pages/landing.page').then((m) => m.LandingPage), title: 'Lucena Drive' },
  { path: 'entrar', canActivate: [guestGuard], loadComponent: () => import('@lucena/shared').then((m) => m.LoginPage), title: 'Entrar · Lucena Drive' },
  { path: 'cadastro', canActivate: [guestGuard], loadComponent: () => import('./auth/register.page').then((m) => m.RegisterPage), title: 'Criar conta · Lucena Drive' },
  { path: 'recuperar-senha', loadComponent: () => import('@lucena/shared').then((m) => m.ForgotPage), title: 'Recuperar senha' },
  { path: 'redefinir-senha', loadComponent: () => import('@lucena/shared').then((m) => m.ResetPage), title: 'Redefinir senha' },
  { path: 'verificar-email', canActivate: [authGuard], loadComponent: () => import('@lucena/shared').then((m) => m.VerifyEmailPage), title: 'Confirme seu e-mail' },
  { path: 'completar-cadastro', canActivate: [authGuard], loadComponent: () => import('./auth/register.page').then((m) => m.RegisterPage), data: { complete: true }, title: 'Complete seu cadastro' },
  { path: 'termos', loadComponent: () => import('@lucena/shared').then((m) => m.LegalPage), data: { doc: 'terms' }, title: 'Termos de uso' },
  { path: 'acompanhar/:token', loadComponent: () => import('./pages/shared-trip.page').then((m) => m.SharedTripPage), title: 'Acompanhar viagem · Lucena Drive' },
  { path: 'privacidade', loadComponent: () => import('@lucena/shared').then((m) => m.LegalPage), data: { doc: 'privacy' }, title: 'Política de privacidade' },
  {
    path: '',
    canActivate: [authGuard, roleGuard('PASSENGER', '/completar-cadastro')],
    loadComponent: () => import('./pages/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: 'inicio', loadComponent: () => import('./pages/home.page').then((m) => m.HomePage), title: 'Para onde vamos? · Lucena Drive' },
      { path: 'corrida/:id', loadComponent: () => import('./pages/tracking.page').then((m) => m.TrackingPage), title: 'Sua corrida · Lucena Drive' },
      { path: 'corrida/:id/avaliar', loadComponent: () => import('./pages/rate.page').then((m) => m.RatePage), title: 'Avaliar corrida' },
      { path: 'historico', loadComponent: () => import('./pages/history.page').then((m) => m.HistoryPage), title: 'Minhas corridas' },
      { path: 'historico/:id', loadComponent: () => import('./pages/ride-detail.page').then((m) => m.RideDetailPage), title: 'Detalhes da corrida' },
      { path: 'perfil', loadComponent: () => import('./pages/profile.page').then((m) => m.ProfilePage), title: 'Meu perfil' },
      { path: 'suporte', loadComponent: () => import('@lucena/shared').then((m) => m.SupportPage), title: 'Ajuda e suporte' },
    ],
  },
  { path: '**', redirectTo: '' },
];
