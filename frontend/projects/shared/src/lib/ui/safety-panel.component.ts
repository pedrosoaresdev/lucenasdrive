import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ApiError } from '../core/errors';
import { ToastService } from '../core/toast.service';

/**
 * Segurança em viagem: atalhos para 190/192, compartilhamento do trajeto (passageiro) e alerta para a equipe.
 * Deixa claro que não existe central de monitoramento 24 h.
 */
@Component({
  selector: 'ld-safety-panel',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card stack safety" role="dialog" aria-labelledby="sf-title">
      <div class="row between"><h2 id="sf-title">Segurança</h2>
        <button class="btn link" type="button" (click)="closed.emit()" aria-label="Fechar">Fechar</button></div>
      <p class="alert err small"><strong>Emergência?</strong> Ligue para a Polícia (190) ou o SAMU (192).
        O Lucena Drive não tem central de monitoramento 24 horas.</p>
      <div class="row">
        <a class="btn danger grow" href="tel:190">Ligar 190</a>
        <a class="btn ghost grow" href="tel:192">Ligar 192</a>
      </div>
      @if (canShare()) {
        <div class="stack">
          <strong class="small">Compartilhar viagem</strong>
          <p class="small muted">Envie um link para alguém de confiança acompanhar o motorista, o veículo e a posição.
            O link para de funcionar 1 hora após o fim da corrida.</p>
          <div class="row">
            <button class="btn primary grow" type="button" (click)="share()" [disabled]="busy()">Gerar link</button>
            @if (link()) { <button class="btn ghost" type="button" (click)="revoke()" [disabled]="busy()">Desativar links</button> }
          </div>
          @if (link(); as l) { <input class="input mono small" [value]="l" readonly aria-label="Link de acompanhamento" (focus)="$any($event.target).select()" /> }
        </div>
      }
      <div class="stack">
        <strong class="small">Avisar a equipe Lucena Drive</strong>
        <textarea class="input" [(ngModel)]="description" maxlength="1000" aria-label="O que está acontecendo"
                  placeholder="O que está acontecendo? (opcional)"></textarea>
        <button class="btn danger" type="button" (click)="alert()" [disabled]="busy() || sent()">
          {{ sent() ? 'Alerta enviado' : 'Enviar alerta de segurança' }}</button>
        @if (sent()) { <p class="small muted">Registramos o alerta com prioridade máxima e a última posição conhecida. A equipe
          responderá pelo app (Ajuda). Se houver risco imediato, ligue 190.</p> }
      </div>
    </section>
  `,
  styles: `.safety { max-width: 520px; } h2 { margin: 0; }`,
})
export class SafetyPanelComponent {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly rideId = input.required<string>();
  readonly canShare = input(false);
  readonly closed = output<void>();
  protected readonly link = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected description = '';

  protected async share(): Promise<void> {
    this.busy.set(true);
    try {
      const r = await this.api.post<{ token: string }>(`/rides/${this.rideId()}/share`);
      const url = `${location.origin}/acompanhar/${r.token}`;
      this.link.set(url);
      const nav = navigator as Navigator & { share?: (d: ShareData) => Promise<void> };
      if (nav.share) {
        await nav.share({ title: 'Acompanhe minha viagem', text: 'Acompanhe minha viagem no Lucena Drive:', url }).catch(() => undefined);
      } else {
        await navigator.clipboard?.writeText(url).then(() => this.toast.ok('Link copiado.')).catch(() => undefined);
      }
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async revoke(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.delete(`/rides/${this.rideId()}/share`);
      this.link.set(null);
      this.toast.ok('Links desativados.');
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async alert(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post(`/rides/${this.rideId()}/safety-alert`, { description: this.description.trim() || null });
      this.sent.set(true);
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
