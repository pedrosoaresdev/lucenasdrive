import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Aviso explícito de funcionalidade que depende de configuração externa ainda ausente. */
@Component({
  selector: 'ld-integration-pending',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="alert warn" role="note">
      <strong>Integração pendente: {{ name() }}.</strong>
      <span> {{ detail() }}</span>
    </div>
  `,
})
export class IntegrationPendingComponent {
  readonly name = input.required<string>();
  readonly detail = input('');
}
