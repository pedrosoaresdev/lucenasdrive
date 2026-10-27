import { ChangeDetectionStrategy, Component, Injectable, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

interface Ask { title: string; message?: string; minLength: number; confirmLabel: string; danger: boolean; resolve: (r: string | null) => void; }

/** Confirmação com justificativa obrigatória (registrada na trilha de auditoria pelo backend). */
@Injectable({ providedIn: 'root' })
export class ReasonService {
  readonly current = signal<Ask | null>(null);

  ask(title: string, opts: { message?: string; minLength?: number; confirmLabel?: string; danger?: boolean } = {}): Promise<string | null> {
    return new Promise((resolve) => this.current.set({ title, message: opts.message, minLength: opts.minLength ?? 10,
      confirmLabel: opts.confirmLabel ?? 'Confirmar', danger: opts.danger ?? false, resolve }));
  }
}

@Component({
  selector: 'admin-reason-dialog',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (svc.current(); as a) {
      <div class="backdrop" role="dialog" aria-modal="true" aria-labelledby="rd-title">
        <form class="card stack dialog fade-in" (submit)="ok($event)">
          <h2 id="rd-title">{{ a.title }}</h2>
          @if (a.message) { <p class="small muted">{{ a.message }}</p> }
          @if (a.minLength > 0) {
            <label for="rd-reason" class="small">Motivo (registrado na auditoria)</label>
            <textarea id="rd-reason" class="input" [(ngModel)]="reason" name="reason" maxlength="500"></textarea>
            <span class="small muted">Mínimo de {{ a.minLength }} caracteres.</span>
          }
          <div class="row">
            <button class="btn" [class.danger]="a.danger" [class.primary]="!a.danger" type="submit"
                    [disabled]="reason.trim().length < a.minLength">{{ a.confirmLabel }}</button>
            <button class="btn ghost" type="button" (click)="cancel()">Cancelar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .backdrop { position: fixed; inset: 0; z-index: 800; background: rgba(13, 19, 33, .5); display: grid; place-items: center; padding: 16px; }
    .dialog { width: min(480px, 100%); }
  `,
})
export class ReasonDialogComponent {
  protected readonly svc = inject(ReasonService);
  protected reason = '';

  protected ok(e: Event): void {
    e.preventDefault();
    this.svc.current()?.resolve(this.reason.trim() || '');
    this.close();
  }

  protected cancel(): void {
    this.svc.current()?.resolve(null);
    this.close();
  }

  private close(): void {
    this.svc.current.set(null);
    this.reason = '';
  }
}
