import { inject } from '@angular/core';
import { ApiError, ToastService } from '@lucena/shared';
import { AdminSessionService } from './admin-session.service';
import { ReasonService } from './reason-dialog.component';

/** Ação administrativa padrão: justificativa → chamada (com step-up se exigido) → aviso. */
export function adminActions() {
  const session = inject(AdminSessionService);
  const reasons = inject(ReasonService);
  const toast = inject(ToastService);
  return {
    session,
    reasons,
    toast,
    async withReason<T>(title: string, fn: (reason: string) => Promise<T>, opts: { minLength?: number; danger?: boolean; ok?: string; message?: string } = {}): Promise<T | null> {
      const reason = await reasons.ask(title, { minLength: opts.minLength ?? 10, danger: opts.danger, message: opts.message });
      if (reason === null) return null;
      try {
        const r = await session.run(() => fn(reason));
        if (opts.ok) toast.ok(opts.ok);
        return r;
      } catch (e) {
        toast.error(ApiError.from(e).message);
        return null;
      }
    },
    async exec<T>(fn: () => Promise<T>, ok?: string): Promise<T | null> {
      try {
        const r = await session.run(fn);
        if (ok) toast.ok(ok);
        return r;
      } catch (e) {
        toast.error(ApiError.from(e).message);
        return null;
      }
    },
  };
}
