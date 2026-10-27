import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Role } from './models';

/** Exige login (a autorização real é feita pelo backend em cada chamada). */
export const authGuard: CanActivateFn = async (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.whenReady();
  return auth.isLoggedIn() ? true : router.createUrlTree(['/entrar'], { queryParams: { voltar: state.url } });
};

/** Exige papel; sem ele, redireciona para a rota informada (ex.: completar cadastro). */
export function roleGuard(role: Role, redirect: string): CanActivateFn {
  return async () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    await auth.whenReady();
    if (!auth.isLoggedIn()) return router.createUrlTree(['/entrar']);
    if (!auth.me()) await auth.refreshMe().catch(() => undefined);
    return auth.hasRole(role) ? true : router.createUrlTree([redirect]);
  };
}

export const guestGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.whenReady();
  return auth.isLoggedIn() ? router.createUrlTree(['/']) : true;
};
