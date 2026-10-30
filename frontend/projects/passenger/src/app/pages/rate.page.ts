import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { RidePaymentComponent } from '../components/ride-payment.component';
import {
  ApiError, ApiService, AvatarComponent, KmPipe, MoneyPipe, PublicConfig, RideView, StarRatingComponent,
  StateViewComponent, ToastService,
} from '@lucena/shared';

const TAG_LABEL: Record<string, string> = {
  DIRECAO_SEGURA: 'Direção segura', CARRO_LIMPO: 'Carro limpo', PONTUALIDADE: 'Pontualidade', CORDIALIDADE: 'Cordialidade',
  BOA_ROTA: 'Boa rota', BOA_CONVERSA: 'Boa conversa', DIRECAO_PERIGOSA: 'Direção perigosa', CARRO_SUJO: 'Carro sujo',
  ATRASO: 'Atraso', ROTA_RUIM: 'Rota ruim', CONDUTA_INADEQUADA: 'Conduta inadequada', MUSICA_ALTA: 'Música alta',
};
const POSITIVE = ['DIRECAO_SEGURA', 'CARRO_LIMPO', 'PONTUALIDADE', 'CORDIALIDADE', 'BOA_ROTA', 'BOA_CONVERSA'];

/** Avaliação do motorista (1 a 5 estrelas), disponível também ao reabrir o app. */
@Component({
  selector: 'app-rate',
  imports: [FormsModule, RidePaymentComponent, AvatarComponent, MoneyPipe, KmPipe, StarRatingComponent, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page">
      @if (!ride()) {
        <ld-state [state]="error() ? 'error' : 'loading'" [message]="error()" [retryable]="false" />
      } @else if (done()) {
        <div class="card stack center fade-in">
          <h2>Obrigado pela avaliação!</h2>
          <p class="muted">Sua opinião ajuda a manter a qualidade do Lucena Drive.</p>
          <button class="btn primary" (click)="router.navigateByUrl('/inicio')">Voltar ao início</button>
        </div>
      } @else {
        @let r = ride()!;
        @if (r.paymentMethod !== 'CASH') { <app-ride-payment [rideId]="r.id" /> }
        <form class="card stack fade-in" (submit)="submit($event)">
          <div class="driver">
            <ld-avatar [path]="r.driver?.photoPath" [name]="r.driver?.name ?? ''" [size]="64" />
            <div>
              <strong>{{ r.driver?.name }}</strong>
              <p class="small muted">{{ r.driver?.vehicleModel }} · {{ r.driver?.plate }}</p>
            </div>
          </div>
          <div class="summary small muted">
            <span>{{ r.route.distanceMeters | km }}</span><span>·</span><span>Total: <strong>{{ r.fare.finalCents | money }}</strong></span>
          </div>
          <h1 id="q">Como foi sua experiência com o Lucena Drive?</h1>
          <ld-star-rating class="stars" [(value)]="stars" label="Como foi sua experiência com o Lucena Drive?" />
          @if (stars()) {
            <fieldset class="tags">
              <legend class="small muted">{{ stars() >= 4 ? 'O que você mais gostou?' : 'O que pode melhorar?' }} (opcional)</legend>
              @for (t of visibleTags(); track t) {
                <button type="button" class="chip" [class.on]="tags().includes(t)" [attr.aria-pressed]="tags().includes(t)"
                        (click)="toggle(t)">{{ label[t] }}</button>
              }
            </fieldset>
            <div class="field">
              <label for="c">Comentário (opcional)</label>
              <textarea id="c" class="input" [(ngModel)]="comment" name="comment" maxlength="500"></textarea>
              <span class="hint">{{ comment.length }}/500 · Comentários podem ser moderados conforme as regras da plataforma.</span>
            </div>
          }
          @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
          <button class="btn primary lg block" type="submit" [disabled]="!stars() || busy()">
            {{ busy() ? 'Enviando…' : 'Enviar avaliação' }}</button>
          <button class="btn link" type="button" (click)="skip()" [disabled]="busy()">Pular por agora</button>
        </form>
      }
    </div>
  `,
  styles: `
    .page { max-width: 560px; padding-top: 24px; padding-bottom: 24px; }
    .driver { display: flex; gap: 14px; align-items: center; }
    .summary { display: flex; gap: 8px; }
    h1 { font-size: 1.35rem; text-align: center; margin-top: 8px; }
    .stars { align-self: center; display: flex; flex-direction: column; align-items: center; }
    .tags { border: 0; padding: 0; margin: 0; display: flex; flex-wrap: wrap; gap: 8px; }
    .tags legend { margin-bottom: 8px; }
    .chip { border: 1px solid var(--ld-border); background: var(--ld-surface); border-radius: 999px; padding: 7px 12px;
      cursor: pointer; font: 600 .85rem var(--ld-font); color: var(--ld-text); }
    .chip.on { background: var(--ld-primary); color: var(--ld-on-primary); border-color: var(--ld-primary); }
    .center { text-align: center; align-items: center; }
  `,
})
export class RatePage implements OnInit {
  protected readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly id = input.required<string>();
  protected readonly ride = signal<RideView | null>(null);
  protected readonly stars = signal(0);
  protected readonly tags = signal<string[]>([]);
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal('');
  protected readonly allTags = signal<string[]>(Object.keys(TAG_LABEL));
  protected readonly label = TAG_LABEL;
  protected comment = '';

  async ngOnInit(): Promise<void> {
    try {
      const r = await this.api.get<RideView>(`/rides/${this.id()}`);
      if (r.rating?.status !== 'PENDING') {
        this.error.set(r.rating?.status === 'SUBMITTED' ? 'Esta corrida já foi avaliada.' : 'Avaliação indisponível para esta corrida.');
        return;
      }
      this.ride.set(r);
      this.api.get<PublicConfig>('/public/config').then((c) => this.allTags.set(c.ratingTags)).catch(() => undefined);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }

  protected visibleTags(): string[] {
    const good = this.stars() >= 4;
    return this.allTags().filter((t) => POSITIVE.includes(t) === good);
  }

  protected toggle(t: string): void {
    this.tags.update((l) => (l.includes(t) ? l.filter((x) => x !== t) : [...l, t]));
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    this.busy.set(true);
    this.error.set('');
    try {
      const visible = this.visibleTags();
      await this.api.post(`/rides/${this.id()}/rating`, {
        stars: this.stars(), comment: this.comment.trim() || null, tags: this.tags().filter((t) => visible.includes(t)),
      });
      this.done.set(true);
    } catch (err) {
      this.error.set(ApiError.from(err).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async skip(): Promise<void> {
    try {
      await this.api.post(`/rides/${this.id()}/rating/skip`);
    } catch (err) {
      this.toast.error(ApiError.from(err).message);
    }
    await this.router.navigateByUrl('/inicio');
  }
}
