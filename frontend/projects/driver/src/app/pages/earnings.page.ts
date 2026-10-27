import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiError, ApiService, AuthService, DateTimePipe, IntegrationPendingComponent, LedgerEntry, MoneyPipe, PayoutRequest,
  StateViewComponent, ToastService, newIdempotencyKey,
} from '@lucena/shared';

interface DriverFinance {
  periods: { period: 'DAY' | 'WEEK' | 'MONTH'; rides: number; grossCents: number; commissionCents: number; earningsCents: number }[];
  balanceByStatus: Record<string, number>; withdrawableCents: number; recentEntries: LedgerEntry[];
  payouts: PayoutRequest[]; payoutsAvailable: boolean;
}

const ENTRY_LABEL: Record<string, string> = {
  RIDE_FARE_CASH: 'Corrida recebida em dinheiro', DRIVER_EARNING: 'Ganho de corrida', DRIVER_COMMISSION_DUE: 'Comissão devida',
  COUPON_SUBSIDY: 'Repasse de cupom', CANCELLATION_FEE: 'Taxa de cancelamento', PAYOUT: 'Saque', ADJUSTMENT: 'Ajuste',
  REVERSAL: 'Estorno',
};
const STATUS_LABEL: Record<string, string> = {
  RECORDED: 'Registrado', AWAITING_SETTLEMENT: 'Aguardando liquidação', AVAILABLE: 'Disponível', PAID: 'Pago', REVERSED: 'Estornado',
};

/** Ganhos (dia/semana/mês), saldos separados por estado de liquidação, extrato, chave Pix e saques. */
@Component({
  selector: 'app-earnings',
  imports: [FormsModule, MoneyPipe, DateTimePipe, StateViewComponent, IntegrationPendingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Ganhos</h1>
      @if (!fin()) { <ld-state [state]="error() ? 'error' : 'loading'" [message]="error()" (retry)="load()" /> }
      @else {
        @let f = fin()!;
        <div class="grid">
          @for (p of f.periods; track p.period) {
            <div class="card kpi"><span class="label">{{ periodLabel[p.period] }}</span>
              <span class="value">{{ p.earningsCents | money }}</span>
              <span class="small muted">{{ p.rides }} corrida(s) · bruto {{ p.grossCents | money }} · comissão {{ p.commissionCents | money }}</span></div>
          }
        </div>
        <section class="card stack">
          <h2>Saldos</h2>
          <p class="small muted">Valores calculados não são dinheiro disponível: só fica disponível o que o provedor de pagamentos liquidar.</p>
          <div class="grid">
            @for (s of statuses; track s) {
              <div class="kpi"><span class="label">{{ statusLabel[s] }}</span><span class="value small-v">{{ (f.balanceByStatus[s] ?? 0) | money }}</span></div>
            }
            <div class="kpi"><span class="label">Disponível para saque</span><span class="value small-v ok">{{ f.withdrawableCents | money }}</span></div>
          </div>
        </section>

        <section class="card stack">
          <h2>Saques via Pix</h2>
          @if (!f.payoutsAvailable) {
            <ld-integration-pending name="Saques" detail="Os repasses serão habilitados após a contratação e configuração do provedor de pagamentos." />
          }
          <div class="stack">
            <strong class="small">Chave Pix de recebimento</strong>
            @if (pixKey(); as k) { <p class="small">Atual: {{ k.keyType }} · <span class="mono">{{ k.keyMasked }}</span></p> }
            <div class="row">
              <select class="input key-type" [(ngModel)]="pix.keyType" aria-label="Tipo de chave">
                @for (t of ['CPF', 'EMAIL', 'PHONE', 'EVP', 'CNPJ']; track t) { <option [value]="t">{{ t }}</option> }
              </select>
              <input class="input grow" [(ngModel)]="pix.key" placeholder="Chave Pix" aria-label="Chave Pix" />
            </div>
            <input class="input" type="password" [(ngModel)]="password" placeholder="Confirme sua senha" aria-label="Senha para confirmar" autocomplete="current-password" />
            <button class="btn ghost" (click)="savePix()" [disabled]="busy() || !pix.key || !password">Salvar chave Pix</button>
          </div>
          @if (f.payoutsAvailable) {
            <div class="row">
              <input class="input grow" type="number" min="10" step="0.01" [(ngModel)]="amount" placeholder="Valor (R$)" aria-label="Valor do saque" />
              <button class="btn primary" (click)="requestPayout()" [disabled]="busy() || !amount || !password">Solicitar saque</button>
            </div>
          }
          @for (p of f.payouts; track p.id) {
            <div class="row between small"><span>{{ p.createdAt | dt }} · {{ p.amountCents | money }} · {{ p.pixKeyMasked }}</span>
              <span class="row"><span class="badge">{{ p.status }}</span>
                @if (p.receiptUrl) { <a [href]="p.receiptUrl" target="_blank" rel="noopener">Comprovante</a> }</span></div>
          }
        </section>

        <section class="stack">
          <h2>Extrato</h2>
          <div class="table-wrap"><table class="table">
            <thead><tr><th>Data</th><th>Lançamento</th><th>Status</th><th>Valor</th></tr></thead>
            <tbody>
              @for (e of f.recentEntries; track e.id) {
                <tr><td>{{ e.createdAt | dt }}</td><td>{{ entryLabel[e.type] ?? e.type }}</td><td>{{ statusLabel[e.status] }}</td>
                  <td class="mono" [class.neg]="e.direction === 'DEBIT'">{{ e.direction === 'DEBIT' ? '−' : '' }}{{ e.amountCents | money }}</td></tr>
              } @empty { <tr><td colspan="4" class="muted">Sem lançamentos.</td></tr> }
            </tbody>
          </table></div>
        </section>
      }
    </div>
  `,
  styles: `
    .page { max-width: 960px; padding-top: 24px; padding-bottom: 24px; }
    .small-v { font-size: 1.15rem; } .ok { color: var(--ld-accent); } .neg { color: var(--ld-danger); }
    .grow { flex: 1; } .key-type { width: 120px; }
  `,
})
export class EarningsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  protected readonly fin = signal<DriverFinance | null>(null);
  protected readonly pixKey = signal<{ keyType: string; keyMasked: string } | null>(null);
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly statuses = ['RECORDED', 'AWAITING_SETTLEMENT', 'AVAILABLE', 'PAID'];
  protected readonly statusLabel = STATUS_LABEL;
  protected readonly entryLabel = ENTRY_LABEL;
  protected readonly periodLabel = { DAY: 'Hoje', WEEK: 'Esta semana', MONTH: 'Este mês' };
  protected pix = { keyType: 'CPF', key: '' };
  protected password = '';
  protected amount: number | null = null;

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected async load(): Promise<void> {
    this.error.set('');
    try {
      this.fin.set(await this.api.get<DriverFinance>('/drivers/me/finance'));
      this.pixKey.set(await this.api.get('/drivers/me/finance/pix-key'));
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }

  /** Operações financeiras exigem login recente: reautentica com a senha antes de chamar a API. */
  private async reauth(): Promise<void> {
    await this.auth.reauthenticate(this.password);
  }

  protected async savePix(): Promise<void> {
    this.busy.set(true);
    try {
      await this.reauth();
      this.pixKey.set(await this.api.put('/drivers/me/finance/pix-key', this.pix));
      this.pix.key = '';
      this.toast.ok('Chave Pix atualizada.');
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async requestPayout(): Promise<void> {
    this.busy.set(true);
    try {
      await this.reauth();
      await this.api.post('/drivers/me/finance/payouts', { amountCents: Math.round(Number(this.amount) * 100) },
        { idempotencyKey: newIdempotencyKey() });
      this.toast.ok('Saque solicitado. Acompanhe o status abaixo.');
      this.amount = null;
      await this.load();
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
