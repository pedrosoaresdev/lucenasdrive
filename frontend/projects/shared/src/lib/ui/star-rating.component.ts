import { ChangeDetectionStrategy, Component, computed, input, model, signal } from '@angular/core';

/**
 * Seleção de 1 a 5 estrelas acessível: grupo de rádio (setas ← →, Home/End, Espaço/Enter),
 * rótulos para leitores de tela e feedback visual imediato.
 */
@Component({
  selector: 'ld-star-rating',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stars" role="radiogroup" [attr.aria-label]="label()" (keydown)="onKey($event)"
         (mouseleave)="hover.set(0)">
      @for (n of [1, 2, 3, 4, 5]; track n) {
        <button type="button" role="radio" class="star" [class.on]="n <= shown()"
                [attr.aria-checked]="value() === n" [attr.aria-label]="n + (n === 1 ? ' estrela: ' : ' estrelas: ') + labels[n]"
                [tabindex]="(value() || 1) === n ? 0 : -1" [disabled]="readonly()"
                (click)="value.set(n)" (mouseenter)="hover.set(n)" (focus)="hover.set(0)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.8l2.85 5.78 6.38.93-4.62 4.5 1.09 6.35L12 17.36l-5.7 3 1.09-6.35-4.62-4.5 6.38-.93z"/></svg>
        </button>
      }
    </div>
    @if (!readonly()) {
      <p class="caption" aria-live="polite">{{ shown() ? labels[shown()] : 'Toque nas estrelas para avaliar' }}</p>
    }
  `,
  styles: `
    .stars { display: inline-flex; gap: 6px; }
    .star { width: var(--size, 48px); height: var(--size, 48px); padding: 4px; border: 0; background: none;
      cursor: pointer; border-radius: 12px; transition: transform .12s; }
    .star:disabled { cursor: default; }
    .star svg { width: 100%; height: 100%; fill: var(--ld-surface-2); stroke: var(--ld-border); stroke-width: 1; transition: fill .12s; }
    .star.on svg { fill: var(--ld-star); stroke: var(--ld-star); }
    .star:not(:disabled):hover { transform: scale(1.08); }
    .caption { margin: 6px 0 0; font-weight: 600; min-height: 1.5em; text-align: center; }
  `,
})
export class StarRatingComponent {
  readonly value = model(0);
  readonly readonly = input(false);
  readonly label = input('Avaliação de 1 a 5 estrelas');
  protected readonly hover = signal(0);
  protected readonly shown = computed(() => this.hover() || this.value());
  protected readonly labels: Record<number, string> = {
    1: 'Experiência muito ruim', 2: 'Experiência ruim', 3: 'Experiência regular', 4: 'Experiência boa',
    5: 'Experiência excelente',
  };

  protected onKey(e: KeyboardEvent): void {
    if (this.readonly()) return;
    const v = this.value() || 0;
    const set = (n: number) => {
      e.preventDefault();
      this.value.set(Math.min(5, Math.max(1, n)));
      queueMicrotask(() => ((e.currentTarget as HTMLElement)?.querySelector('[aria-checked="true"]') as HTMLElement)?.focus());
    };
    switch (e.key) {
      case 'ArrowRight': case 'ArrowUp': set(v + 1); break;
      case 'ArrowLeft': case 'ArrowDown': set(v - 1); break;
      case 'Home': set(1); break;
      case 'End': set(5); break;
    }
  }
}
