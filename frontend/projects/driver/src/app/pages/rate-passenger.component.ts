import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiError, ApiService, StarRatingComponent } from '@lucena/shared';

const TAG_LABEL: Record<string, string> = {
  EDUCADO: 'Educado', PONTUAL: 'Pontual', RESPEITOSO: 'Respeitoso', BOA_CONVERSA: 'Boa conversa',
  ATRASO_EMBARQUE: 'Atraso no embarque', CONDUTA_INADEQUADA: 'Conduta inadequada', SUJOU_VEICULO: 'Sujou o veículo',
  ENDERECO_ERRADO: 'Endereço errado',
};
const POSITIVE = ['EDUCADO', 'PONTUAL', 'RESPEITOSO', 'BOA_CONVERSA'];
const NEGATIVE = ['ATRASO_EMBARQUE', 'CONDUTA_INADEQUADA', 'SUJOU_VEICULO', 'ENDERECO_ERRADO'];

/** Avaliação recíproca: o motorista avalia o passageiro após a viagem (uma vez, validado no servidor). */
@Component({
  selector: 'app-rate-passenger',
  imports: [FormsModule, StarRatingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="card flat stack" (submit)="submit($event)">
      <strong>Como foi a viagem com {{ name() }}?</strong>
      <ld-star-rating [(value)]="stars" [label]="'Avaliar ' + name()" />
      @if (stars()) {
        <div class="tags" role="group" aria-label="Categorias (opcional)">
          @for (t of stars() >= 4 ? positive : negative; track t) {
            <button type="button" class="chip" [class.on]="tags().includes(t)" [attr.aria-pressed]="tags().includes(t)"
                    (click)="toggle(t)">{{ label[t] }}</button>
          }
        </div>
        <label class="sr-only" for="pc">Comentário</label>
        <textarea id="pc" class="input" name="comment" [(ngModel)]="comment" maxlength="500"
                  placeholder="Comentário (opcional, visível apenas para a equipe)"></textarea>
      }
      @if (error()) { <p class="alert err small" role="alert">{{ error() }}</p> }
      <button class="btn primary block" type="submit" [disabled]="!stars() || busy()">
        {{ busy() ? 'Enviando…' : 'Enviar avaliação do passageiro' }}</button>
    </form>
  `,
  styles: `
    .tags { display: flex; flex-wrap: wrap; gap: 8px; }
    .chip { border: 1px solid var(--ld-border); background: var(--ld-surface); border-radius: 999px; padding: 6px 11px;
      cursor: pointer; font: 600 .82rem var(--ld-font); color: var(--ld-text); }
    .chip.on { background: var(--ld-primary); color: var(--ld-on-primary); border-color: var(--ld-primary); }
  `,
})
export class RatePassengerComponent {
  private readonly api = inject(ApiService);
  readonly rideId = input.required<string>();
  readonly name = input<string>('o passageiro');
  readonly done = output<void>();
  protected readonly label = TAG_LABEL;
  protected readonly positive = POSITIVE;
  protected readonly negative = NEGATIVE;
  protected readonly stars = signal(0);
  protected readonly tags = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected comment = '';

  protected toggle(t: string): void {
    this.tags.update((list) => (list.includes(t) ? list.filter((x) => x !== t) : [...list, t]));
  }

  protected async submit(ev: Event): Promise<void> {
    ev.preventDefault();
    if (!this.stars()) return;
    this.busy.set(true);
    this.error.set('');
    const allowed = this.stars() >= 4 ? POSITIVE : NEGATIVE;
    try {
      await this.api.post(`/driver/rides/${this.rideId()}/passenger-rating`, {
        stars: this.stars(), comment: this.comment.trim() || null, tags: this.tags().filter((t) => allowed.includes(t)),
      });
      this.done.emit();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
