import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  ApiService, DateTimePipe, IntegrationPendingComponent, LedgerEntry, MoneyPipe, PayoutRequest, newIdempotencyKey,
} from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Fin { grossRideValueCents: number; cashRideValueCents: number; digitalPaymentsConfirmedCents: number;
  commissionCalculatedCents: number; commissionReceivedCents: number; awaitingSettlementCents: number; couponSubsidyCents: number;
  providerFeesCents: number; refundsCents: number; chargebacksCents: number; driverPayoutsPendingCents: number;
  driverPayoutsCompletedCents: number; providerAvailableBalanceCents: number | null; providerConfigured: boolean; }
interface Issue { kind: string; referenceId: string; description: string; since: string; }

/**
 * Financeiro da plataforma, separando: (1) valores registrados, (2) aguardando liquidação,
 * (3) disponíveis segundo o provedor e (4) pagos/confirmados. Saldo do Firestore ≠ dinheiro em conta.
 */
@Component({
  selector: 'admin-finance',
  imports: [FormsModule, MoneyPipe, DateTimePipe, IntegrationPendingComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Financeiro</h1>
    <div class="row filters">
      <label class="small">De <input class="input" type="date" [(ngModel)]="from" /></label>
      <label class="small">Até <input class="input" type="date" [(ngModel)]="to" /></label>
      <button class="btn primary" (click)="load()">Aplicar</button>
    </div>
    @if (!f()?.providerConfigured) {
      <ld-integration-pending name="Provedor de pagamentos" detail="Pix, cartão, liquidação, saldo disponível e saques dependem do contrato com um provedor (ver docs/INTEGRATIONS.md). Os valores abaixo são registros internos." />
    }
    @if (f(); as x) {
      <div class="grid cols">
        <section class="card stack"><h2>1 · Registrado</h2>
          <p>Valor bruto das corridas <strong>{{ x.grossRideValueCents | money }}</strong></p>
          <p>Recebido em dinheiro pelos motoristas <strong>{{ x.cashRideValueCents | money }}</strong></p>
          <p>Comissões calculadas <strong>{{ x.commissionCalculatedCents | money }}</strong></p>
          <p>Subsídios de cupons <strong>{{ x.couponSubsidyCents | money }}</strong></p></section>
        <section class="card stack"><h2>2 · Aguardando liquidação</h2>
          <p>Saldo líquido pendente <strong>{{ x.awaitingSettlementCents | money }}</strong></p>
          <p>Repasses a motoristas em curso <strong>{{ x.driverPayoutsPendingCents | money }}</strong></p></section>
        <section class="card stack"><h2>3 · Disponível (provedor)</h2>
          @if (x.providerAvailableBalanceCents !== null) { <p class="big">{{ x.providerAvailableBalanceCents | money }}</p> }
          @else { <p class="muted">Indisponível — sem provedor configurado.</p> }
          <p>Comissão efetivamente recebida <strong>{{ x.commissionReceivedCents | money }}</strong></p></section>
        <section class="card stack"><h2>4 · Pago e confirmado</h2>
          <p>Pagamentos digitais confirmados <strong>{{ x.digitalPaymentsConfirmedCents | money }}</strong></p>
          <p>Repasses concluídos <strong>{{ x.driverPayoutsCompletedCents | money }}</strong></p>
          <p>Taxas do provedor <strong>{{ x.providerFeesCents | money }}</strong></p>
          <p>Reembolsos <strong>{{ x.refundsCents | money }}</strong> · Estornos/chargebacks <strong>{{ x.chargebacksCents | money }}</strong></p></section>
      </div>
    }

    <div class="grid cols2">
      <section class="card stack">
        <h2>Saque da plataforma (Pix)</h2>
        @if (pix(); as k) { <p class="small">Chave autorizada: {{ k.keyType }} · <span class="mono">{{ k.keyMasked }}</span></p> }
        @else { <p class="small muted">Nenhuma chave Pix cadastrada.</p> }
        @if (a.session.can('OWNER')) {
          <div class="row"><select class="input sel" [(ngModel)]="pixForm.keyType" aria-label="Tipo de chave">
            @for (t of ['CNPJ', 'CPF', 'EMAIL', 'PHONE', 'EVP']; track t) { <option [value]="t">{{ t }}</option> }</select>
            <input class="input grow" [(ngModel)]="pixForm.key" placeholder="Chave Pix" aria-label="Chave Pix" />
            <button class="btn ghost" (click)="savePix()" [disabled]="!pixForm.key">Salvar (exige MFA)</button></div>
        }
        @if (a.session.can('OWNER', 'FINANCE')) {
          <div class="row"><input class="input grow" type="number" step="0.01" [(ngModel)]="amount" placeholder="Valor (R$)" aria-label="Valor do saque" />
            <button class="btn primary" (click)="withdraw()" [disabled]="!amount || !f()?.providerConfigured">Solicitar saque</button></div>
          <p class="small muted">Exige confirmação do segundo fator. Limitado ao saldo informado pelo provedor.</p>
        }
      </section>
      <section class="card stack">
        <h2>Conciliação</h2>
        @for (i of issues(); track i.referenceId) { <p class="small"><span class="badge warn">{{ i.kind }}</span> {{ i.description }} · <span class="mono">{{ i.referenceId }}</span> · desde {{ i.since | dt }}</p> }
        @empty { <p class="small muted">Nenhuma pendência interna. A conferência com o extrato do provedor depende da integração.</p> }
      </section>
    </div>

    <section class="stack block">
      <h2>Saques e repasses</h2>
      <div class="table-wrap"><table class="table">
        <thead><tr><th>Data</th><th>Titular</th><th>Valor</th><th>Chave</th><th>Status</th><th>ID transferência</th><th>Comprovante</th></tr></thead>
        <tbody>@for (p of payouts(); track p.id) {
          <tr><td>{{ p.createdAt | dt }}</td><td>{{ p.ownerType }}</td><td class="mono">{{ p.amountCents | money }}</td><td class="mono">{{ p.pixKeyMasked }}</td>
            <td><span class="badge">{{ p.status }}</span> @if (p.failureReason) { <span class="small muted">{{ p.failureReason }}</span> }</td>
            <td class="mono small">{{ p.providerTransferId ?? '—' }}</td>
            <td>@if (p.receiptUrl) { <a [href]="p.receiptUrl" target="_blank" rel="noopener">abrir</a> } @else { — }</td></tr>
        } @empty { <tr><td colspan="7" class="muted">Nenhum saque.</td></tr> }</tbody>
      </table></div>
    </section>

    <section class="stack block">
      <div class="row between"><h2>Extrato da plataforma</h2>
        @if (a.session.can('FINANCE', 'OWNER')) { <button class="btn ghost sm" (click)="exportCsv()">Exportar CSV</button> }</div>
      <div class="table-wrap"><table class="table">
        <thead><tr><th>Data</th><th>Tipo</th><th>Direção</th><th>Valor</th><th>Status</th><th>Corrida</th><th>Descrição</th></tr></thead>
        <tbody>@for (e of statement(); track e.id) {
          <tr><td>{{ e.createdAt | dt }}</td><td>{{ e.type }}</td><td>{{ e.direction }}</td><td class="mono">{{ e.amountCents | money }}</td>
            <td>{{ e.status }}</td><td class="mono small">{{ e.rideId ?? '—' }}</td><td>{{ e.description }}</td></tr>
        } @empty { <tr><td colspan="7" class="muted">Sem lançamentos no período.</td></tr> }</tbody>
      </table></div>
    </section>
  `,
  styles: `.filters { margin: 12px 0 16px; } .filters .input { width: auto; }
    .cols { grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); margin-top: 12px; } .cols p { margin: 0; font-size: .92rem; }
    .cols2 { grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); margin-top: 16px; } .big { font-size: 1.6rem; font-weight: 800; }
    .grow { flex: 1; } .sel { width: 110px; } .block { margin-top: 20px; }`,
})
export default class FinancePage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly a = adminActions();
  protected readonly f = signal<Fin | null>(null);
  protected readonly issues = signal<Issue[]>([]);
  protected readonly payouts = signal<PayoutRequest[]>([]);
  protected readonly statement = signal<LedgerEntry[]>([]);
  protected readonly pix = signal<{ keyType: string; keyMasked: string } | null>(null);
  protected from = '';
  protected to = '';
  protected amount: number | null = null;
  protected pixForm = { keyType: 'CNPJ', key: '' };

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private range() {
    return { from: this.from ? new Date(this.from + 'T00:00:00').toISOString() : null,
      to: this.to ? new Date(this.to + 'T23:59:59').toISOString() : null };
  }

  protected async load(): Promise<void> {
    const r = this.range();
    const [f, issues, payouts, st] = await Promise.all([
      this.api.get<Fin>('/admin/finance/summary', r), this.api.get<Issue[]>('/admin/finance/reconciliation'),
      this.api.get<PayoutRequest[]>('/admin/finance/payouts'), this.api.get<LedgerEntry[]>('/admin/finance/statement', { ...r, account: 'PLATFORM' }),
    ]);
    this.f.set(f);
    this.issues.set(issues);
    this.payouts.set(payouts);
    this.statement.set(st);
    if (this.a.session.can('FINANCE', 'OWNER')) this.pix.set(await this.api.get<{ keyType: string; keyMasked: string } | null>('/admin/finance/pix-key').catch(() => null));
  }

  protected async savePix(): Promise<void> {
    const r = await this.a.exec(() => this.api.put<{ keyType: string; keyMasked: string }>('/admin/finance/pix-key', this.pixForm), 'Chave Pix atualizada.');
    if (r) {
      this.pix.set(r);
      this.pixForm.key = '';
    }
  }

  protected async withdraw(): Promise<void> {
    const key = newIdempotencyKey();
    if (await this.a.exec(() => this.api.post('/admin/finance/withdrawals', { amountCents: Math.round(Number(this.amount) * 100) },
      { idempotencyKey: key }), 'Saque solicitado ao provedor.')) {
      this.amount = null;
      await this.load();
    }
  }

  protected async exportCsv(): Promise<void> {
    const blob = await this.a.exec(() => this.api.download('/admin/finance/statement.csv', { ...this.range(), account: 'PLATFORM' }));
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'extrato-lucena.csv';
    link.click();
    URL.revokeObjectURL(url);
  }
}
