import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

/** Estados padronizados: carregando, vazio, erro, indisponível (integração pendente). */
@Component({
  selector: 'ld-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="state" [attr.role]="state() === 'error' ? 'alert' : 'status'" aria-live="polite">
      @switch (state()) {
        @case ('loading') { <span class="spinner" aria-hidden="true"></span> }
        @case ('error') { <span class="ico err" aria-hidden="true">!</span> }
        @case ('unavailable') { <span class="ico warn" aria-hidden="true">⏸</span> }
        @default { <span class="ico" aria-hidden="true">○</span> }
      }
      <strong>{{ title() || defaults[state()] }}</strong>
      @if (message()) { <p class="muted small">{{ message() }}</p> }
      @if (state() === 'error' && retryable()) {
        <button class="btn sm ghost" type="button" (click)="retry.emit()">Tentar novamente</button>
      }
    </div>
  `,
  styles: `
    .state { display: flex; flex-direction: column; align-items: center; text-align: center; gap: 8px; padding: 32px 16px; }
    .ico { width: 40px; height: 40px; border-radius: 50%; display: grid; place-items: center; font-weight: 800;
      background: var(--ld-surface-2); color: var(--ld-text-muted); }
    .ico.err { background: var(--ld-danger-soft); color: var(--ld-danger); }
    .ico.warn { background: var(--ld-warning-soft); color: var(--ld-warning); }
    .spinner { width: 32px; height: 32px; border-radius: 50%; border: 3px solid var(--ld-border);
      border-top-color: var(--ld-primary); animation: spin .8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    p { margin: 0; max-width: 420px; }
  `,
})
export class StateViewComponent {
  readonly state = input<'loading' | 'empty' | 'error' | 'unavailable'>('loading');
  readonly title = input('');
  readonly message = input('');
  readonly retryable = input(true);
  readonly retry = output<void>();
  protected readonly defaults = {
    loading: 'Carregando…', empty: 'Nada por aqui ainda', error: 'Algo deu errado', unavailable: 'Indisponível no momento',
  };
}
