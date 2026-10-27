import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '@lucena/shared';
import { AdminSessionService } from './admin-session.service';

/** Equipe autenticada com sessão MFA. A autorização definitiva é sempre do backend. */
export const staffMfaGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const session = inject(AdminSessionService);
  const router = inject(Router);
  await auth.whenReady();
  if (!auth.isLoggedIn()) return router.createUrlTree(['/entrar']);
  if (!auth.me()) await auth.refreshMe().catch(() => undefined);
  if (!session.isStaff()) return router.createUrlTree(['/sem-acesso']);
  return session.token() ? true : router.createUrlTree(['/mfa']);
};
