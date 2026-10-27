import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiError, ApiService, AuthService, MFA_STORAGE_KEY, Role, readMfaToken } from '@lucena/shared';

/**
 * Sessão administrativa pós-MFA. O token fica em sessionStorage (encerra ao fechar a aba) e só vale
 * junto com o login Firebase do mesmo usuário. Operações críticas pedem reconfirmação (step-up).
 */
@Injectable({ providedIn: 'root' })
export class AdminSessionService {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly token = signal<string | null>(readMfaToken());
  readonly stepUpRequest = signal<{ resolve: (ok: boolean) => void } | null>(null);
  readonly isStaff = computed(() => this.auth.roles().some((r) => ['SUPPORT', 'FINANCE', 'ADMIN', 'OWNER'].includes(r)));

  setToken(t: string | null): void {
    try {
      if (t) sessionStorage.setItem(MFA_STORAGE_KEY, t);
      else sessionStorage.removeItem(MFA_STORAGE_KEY);
    } catch { /* armazenamento indisponível: mantém em memória */ }
    this.token.set(t);
  }

  can(...roles: Role[]): boolean {
    return roles.some((r) => this.auth.hasRole(r));
  }

  /**
   * Executa uma operação; se o servidor exigir MFA recente, abre a confirmação de código e repete.
   * Se a sessão MFA expirou/foi revogada, volta para a tela de segundo fator.
   */
  async run<T>(fn: () => Promise<T>): Promise<T> {
    try {
      return await fn();
    } catch (e) {
      const err = ApiError.from(e);
      if (err.code !== 'MFA_REQUIRED') throw err;
      if (!this.token()) {
        await this.router.navigateByUrl('/mfa');
        throw err;
      }
      const ok = await new Promise<boolean>((resolve) => this.stepUpRequest.set({ resolve }));
      if (!ok) throw err;
      return fn();
    }
  }

  async stepUp(code: string): Promise<void> {
    await this.api.post('/admin/auth/step-up', { code });
  }

  async logout(): Promise<void> {
    await this.api.post('/admin/auth/logout').catch(() => undefined);
    this.setToken(null);
    await this.auth.logout();
    await this.router.navigateByUrl('/entrar');
  }
}
