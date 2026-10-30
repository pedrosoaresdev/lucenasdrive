import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, input, signal } from '@angular/core';
import { ApiError, ApiService, MoneyPipe, ToastService } from '@lucena/shared';

export interface ChargeView {
  rideId: string; method: 'PIX' | 'CARD' | 'CASH'; status: string; amountCents: number;
  pixCopyPaste: string | null; pixQrCodeBase64: string | null; checkoutUrl: string | null;
  chargeCreated: boolean; splitToDriver: boolean;
}

/**
 * Pagamento da corrida (Pix/cartão). O status só muda para "confirmado" quando o provedor confirma
 * (webhook ou consulta autenticada no servidor); o botão "Já paguei" apenas pede essa consulta.
 */
@Component({
  selector: 'app-ride-payment',
  imports: [MoneyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (charge(); as c) {
      <section class="card stack pay" aria-live="polite">
        @if (c.status === 'CONFIRMED') {
          <p class="alert ok">Pagamento de {{ c.amountCents | money }} confirmado pelo provedor. Obrigado!</p>
        } @else if (c.status === 'AWAITING_PROVIDER') {
          <h2>Pagamento pendente: {{ c.amountCents | money }}</h2>
          @if (!c.chargeCreated) {
            <p class="alert warn">Não foi possível gerar a cobrança agora. Tente novamente em instantes.</p>
            <button class="btn primary" [disabled]="busy()" (click)="refresh()">Gerar cobrança novamente</button>
          } @else if (c.method === 'PIX') {
            @if (c.pixQrCodeBase64) {
              <img class="qr" [src]="'data:image/png;base64,' + c.pixQrCodeBase64" alt="QR Code Pix da corrida" />
            }
            @if (c.pixCopyPaste) {
              <label class="small muted" for="pix-cc">Pix copia e cola</label>
              <textarea id="pix-cc" class="input mono" readonly rows="3">{{ c.pixCopyPaste }}</textarea>
              <button class="btn primary" (click)="copy(c.pixCopyPaste)">Copiar código Pix</button>
            } @else if (c.checkoutUrl) {
              <a class="btn primary" [href]="c.checkoutUrl" target="_blank" rel="noopener">Abrir Pix no Asaas</a>
            }
          } @else if (c.checkoutUrl) {
            <p class="small muted">Você será levado à página segura do provedor. Os dados do cartão não passam pelo Lucena Drive.</p>
            <a class="btn primary" [href]="c.checkoutUrl" target="_blank" rel="noopener">Pagar com cartão</a>
          }
          @if (c.chargeCreated) {
            <button class="btn ghost" [disabled]="busy()" (click)="refresh()">{{ busy() ? 'Verificando…' : 'Já paguei — verificar' }}</button>
            <p class="small muted">A confirmação chega automaticamente em alguns segundos após o pagamento.</p>
          }
        } @else if (c.status === 'FAILED') {
          <p class="alert err">O provedor não confirmou o pagamento. Fale com o suporte se o valor foi debitado.</p>
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; margin-bottom: 16px; }
    .pay h2 { margin: 0; font-size: 1.1rem; }
    .qr { width: 220px; height: 220px; align-self: center; image-rendering: pixelated; background: #fff; padding: 8px; border-radius: 12px; }
    textarea { resize: none; font-size: .8rem; word-break: break-all; }
  `,
})
export class RidePaymentComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly rideId = input.required<string>();
  protected readonly charge = signal<ChargeView | null>(null);
  protected readonly busy = signal(false);
  private timer: ReturnType<typeof setInterval> | undefined;
  private polls = 0;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearInterval(this.timer));
  }

  ngOnInit(): void {
    void this.load();
    // Acompanha a confirmação por até ~10 min (o servidor também concilia sozinho).
    this.timer = setInterval(() => {
      if (++this.polls > 120 || this.charge()?.status !== 'AWAITING_PROVIDER') {
        clearInterval(this.timer);
        return;
      }
      void this.load();
    }, 5000);
  }

  private async load(): Promise<void> {
    try {
      this.charge.set(await this.api.get<ChargeView>(`/rides/${this.rideId()}/payment`));
    } catch (e) {
      if (!(e instanceof ApiError) || e.status !== 404) {
        throw e;
      }
      clearInterval(this.timer);
    }
  }

  protected async refresh(): Promise<void> {
    this.busy.set(true);
    try {
      const c = await this.api.post<ChargeView>(`/rides/${this.rideId()}/payment/refresh`);
      this.charge.set(c);
      if (c.status === 'AWAITING_PROVIDER' && c.chargeCreated) {
        this.toast.show('Ainda não recebemos a confirmação do provedor. Aguarde alguns segundos.');
      }
    } catch (e) {
      this.toast.error(e instanceof ApiError ? e.message : 'Não foi possível verificar agora.');
    } finally {
      this.busy.set(false);
    }
  }

  protected copy(code: string): void {
    void navigator.clipboard?.writeText(code).then(() => this.toast.ok('Código Pix copiado.')).catch(() => undefined);
  }
}
