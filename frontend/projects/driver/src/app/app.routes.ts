import { Routes } from '@angular/router';
import { authGuard, guestGuard, roleGuard } from '@lucena/shared';

const tag = { tag: 'Motorista', after: '/painel' };

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'painel' },
  { path: 'entrar', canActivate: [guestGuard], loadComponent: () => import('@lucena/shared').then((m) => m.LoginPage),
    data: { ...tag, heading: 'Área do motorista', subheading: 'Entre para dirigir com o Lucena Drive.' }, title: 'Entrar · Motorista' },
  { path: 'recuperar-senha', loadComponent: () => import('@lucena/shared').then((m) => m.ForgotPage), data: tag, title: 'Recuperar senha' },
  { path: 'redefinir-senha', loadComponent: () => import('@lucena/shared').then((m) => m.ResetPage), data: tag, title: 'Redefinir senha' },
  { path: 'verificar-email', canActivate: [authGuard], loadComponent: () => import('@lucena/shared').then((m) => m.VerifyEmailPage), data: tag, title: 'Confirme seu e-mail' },
  { path: 'cadastro', loadComponent: () => import('./pages/register.page').then((m) => m.RegisterPage), title: 'Seja motorista · Lucena Drive' },
  { path: 'termos', loadComponent: () => import('@lucena/shared').then((m) => m.LegalPage), data: { doc: 'terms' }, title: 'Termos' },
  { path: 'privacidade', loadComponent: () => import('@lucena/shared').then((m) => m.LegalPage), data: { doc: 'privacy' }, title: 'Privacidade' },
  {
    path: '',
    canActivate: [authGuard, roleGuard('DRIVER', '/cadastro')],
    loadComponent: () => import('./pages/shell.component').then((m) => m.ShellComponent),
    children: [
      { path: 'painel', loadComponent: () => import('./pages/dashboard.page').then((m) => m.DashboardPage), title: 'Painel · Motorista' },
      { path: 'documentos', loadComponent: () => import('./pages/onboarding.page').then((m) => m.OnboardingPage), title: 'Documentos e veículos' },
      { path: 'corrida/:id', loadComponent: () => import('./pages/ride.page').then((m) => m.RidePage), title: 'Corrida em andamento' },
      { path: 'ganhos', loadComponent: () => import('./pages/earnings.page').then((m) => m.EarningsPage), title: 'Ganhos' },
      { path: 'historico', loadComponent: () => import('./pages/history.page').then((m) => m.HistoryPage), title: 'Histórico' },
      { path: 'avaliacoes', loadComponent: () => import('./pages/ratings.page').then((m) => m.RatingsPage), title: 'Avaliações' },
      { path: 'perfil', loadComponent: () => import('./pages/profile.page').then((m) => m.ProfilePage), title: 'Perfil' },
      { path: 'suporte', loadComponent: () => import('@lucena/shared').then((m) => m.SupportPage), title: 'Suporte' },
    ],
  },
  { path: '**', redirectTo: 'painel' },
];
