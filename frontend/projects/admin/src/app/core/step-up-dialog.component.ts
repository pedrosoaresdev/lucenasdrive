import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError } from '@lucena/shared';
import { AdminSessionService } from './admin-session.service';

/** Confirmação do segundo fator para operações críticas. */
@Component({
  selector: 'admin-step-up',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (session.stepUpRequest()) {
      <div class="backdrop" role="dialog" aria-modal="true" aria-labelledby="su-title">
        <form class="card stack dialog fade-in" (submit)="confirm($event)">
          <h2 id="su-title">Confirme sua identidade</h2>
          <p class="small muted">Esta é uma operação crítica. Digite o código de 6 dígitos do seu aplicativo autenticador.</p>
          <input class="input code" [(ngModel)]="code" name="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code"
                 pattern="[0-9]{6}" aria-label="Código do autenticador" autofocus />
          @if (error()) { <p class="alert err small" role="alert">{{ error() }}</p> }
          <div class="row">
            <button class="btn primary" type="submit" [disabled]="code.length !== 6 || busy()">Confirmar</button>
            <button class="btn ghost" type="button" (click)="close(false)">Cancelar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .backdrop { position: fixed; inset: 0; z-index: 900; background: rgba(13, 19, 33, .5); display: grid; place-items: center; padding: 16px; }
    .dialog { width: min(420px, 100%); } .code { font-size: 1.6rem; letter-spacing: .4em; text-align: center; }
  `,
})
export class StepUpDialogComponent {
  protected readonly session = inject(AdminSessionService);
  protected code = '';
  protected readonly busy = signal(false);
  protected readonly error = signal('');

  protected async confirm(e: Event): Promise<void> {
    e.preventDefault();
    this.busy.set(true);
    this.error.set('');
    try {
      await this.session.stepUp(this.code);
      this.close(true);
    } catch (err) {
      this.error.set(ApiError.from(err).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected close(ok: boolean): void {
    this.session.stepUpRequest()?.resolve(ok);
    this.session.stepUpRequest.set(null);
    this.code = '';
    this.error.set('');
  }
}
