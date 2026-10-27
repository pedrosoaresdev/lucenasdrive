import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * Marca Lucena Drive: símbolo (quadrado arredondado índigo com um "L" que vira estrada e um ponto
 * de destino em verde-menta) + logotipo tipográfico.
 */
@Component({
  selector: 'ld-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="logo" [style.--s.px]="size()">
      <svg class="mark" viewBox="0 0 48 48" aria-hidden="true">
        <rect width="48" height="48" rx="13" fill="#4338ca" />
        <path d="M16 11v18.5c0 4.7 3.8 8.5 8.5 8.5H34" fill="none" stroke="#fff" stroke-width="5.5"
              stroke-linecap="round" stroke-linejoin="round" />
        <path d="M16 20c6 0 9 2.5 11 7" fill="none" stroke="#c7c9ff" stroke-width="2" stroke-dasharray="2.5 3"
              stroke-linecap="round" />
        <circle cx="34" cy="16" r="5" fill="#10b981" />
        <circle cx="34" cy="16" r="2" fill="#fff" />
      </svg>
      @if (!symbolOnly()) {
        <span class="word"><b>Lucena</b><span>Drive</span>
          @if (tag()) { <em>{{ tag() }}</em> }
        </span>
      }
    </span>
  `,
  styles: `
    .logo { display: inline-flex; align-items: center; gap: calc(var(--s) * .3); }
    .mark { width: var(--s); height: var(--s); flex: none; }
    .word { display: inline-flex; align-items: baseline; gap: 4px; font-size: calc(var(--s) * .55);
      letter-spacing: -.02em; color: var(--ld-text); }
    .word b { font-weight: 800; }
    .word span { font-weight: 500; color: var(--ld-primary); }
    .word em { font-style: normal; font-size: .55em; font-weight: 700; text-transform: uppercase; letter-spacing: .08em;
      margin-left: 6px; padding: 2px 7px; border-radius: 6px; background: var(--ld-primary-soft); color: var(--ld-primary); }
  `,
})
export class LogoComponent {
  readonly size = input(32);
  readonly symbolOnly = input(false);
  readonly tag = input<string>('');
}
