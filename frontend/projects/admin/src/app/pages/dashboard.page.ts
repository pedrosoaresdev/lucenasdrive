import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiError, ApiService, DateTimePipe, MoneyPipe, StateViewComponent, money } from '@lucena/shared';
import { BarChartComponent, BarPoint } from '../core/bar-chart.component';

interface Finance {
  grossRideValueCents: number; cashRideValueCents: number; digitalPaymentsConfirmedCents: number;
  commissionCalculatedCents: number; commissionReceivedCents: number; awaitingSettlementCents: number;
  couponSubsidyCents: number; providerFeesCents: number; refundsCents: number; chargebacksCents: number;
  driverPayoutsPendingCents: number; driverPayoutsCompletedCents: number; providerAvailableBalanceCents: number | null;
  providerConfigured: boolean;
}
interface Summary {
  totalPassengers: number; totalDrivers: number; approvedDrivers: number; pendingDrivers: number; onlineDrivers: number;
  ridesSearching: number; ridesInProgress: number; ridesCompleted: number; ridesCancelled: number; activeCoupons: number;
  platformRatingAverage: number; platformRatingCount: number; pendingPayouts: number; openSafetyTickets: number;
  reconciliationIssues: number; finance: Finance; ridesByCity: { cityId: string; cityName: string; completedRides: number; activeRides: number }[];
  generatedAt: string;
}
interface DayPoint { date: string; completedRides: number; cancelledRides: number; grossCents: number; commissionCents: number; }

/** Indicadores calculados no servidor a partir do banco (contagens e somas agregadas). */
@Component({
  selector: 'admin-dashboard',
  imports: [RouterLink, MoneyPipe, DateTimePipe, StateViewComponent, BarChartComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="row between head">
      <h1>Dashboard</h1>
      @if (s()) { <span class="small muted">Atualizado {{ s()!.generatedAt | dt }} · atualização automática a cada 30 s</span> }
    </div>
    @if (!s()) { <ld-state [state]="error() ? 'error' : 'loading'" [message]="error()" (retry)="load()" /> }
    @else {
      @let d = s()!;
      @let f = d.finance;
      @if (alerts().length) {
        <div class="stack alerts">@for (a of alerts(); track a.text) { <a class="alert" [class]="a.tone" [routerLink]="a.to">{{ a.text }}</a> }</div>
      }
      <h2>Operação</h2>
      <div class="grid kpis">
        <a class="card kpi" routerLink="/passageiros"><span class="label">Passageiros</span><span class="value">{{ d.totalPassengers }}</span></a>
        <a class="card kpi" routerLink="/motoristas"><span class="label">Motoristas</span><span class="value">{{ d.totalDrivers }}</span>
          <span class="small muted">{{ d.approvedDrivers }} aprovados · {{ d.pendingDrivers }} pendentes</span></a>
        <div class="card kpi"><span class="label">Motoristas online</span><span class="value">{{ d.onlineDrivers }}</span></div>
        <a class="card kpi" routerLink="/mapa"><span class="label">Corridas em andamento</span><span class="value">{{ d.ridesInProgress }}</span>
          <span class="small muted">{{ d.ridesSearching }} buscando motorista</span></a>
        <div class="card kpi"><span class="label">Concluídas</span><span class="value">{{ d.ridesCompleted }}</span></div>
        <div class="card kpi"><span class="label">Canceladas</span><span class="value">{{ d.ridesCancelled }}</span></div>
        <div class="card kpi"><span class="label">Avaliação média</span><span class="value">★ {{ d.platformRatingCount ? d.platformRatingAverage.toFixed(2) : '—' }}</span>
          <span class="small muted">{{ d.platformRatingCount }} avaliações</span></div>
        <a class="card kpi" routerLink="/cupons"><span class="label">Cupons ativos</span><span class="value">{{ d.activeCoupons }}</span></a>
      </div>

      <h2>Financeiro</h2>
      <div class="grid kpis">
        <div class="card kpi"><span class="label">Valor bruto das corridas</span><span class="value">{{ f.grossRideValueCents | money }}</span>
          <span class="small muted">registrado · {{ f.cashRideValueCents | money }} em dinheiro</span></div>
        <div class="card kpi"><span class="label">Comissão gerada</span><span class="value">{{ f.commissionCalculatedCents | money }}</span>
          <span class="small muted">valor calculado</span></div>
        <div class="card kpi"><span class="label">Comissão recebida</span><span class="value">{{ f.commissionReceivedCents | money }}</span>
          <span class="small muted">liquidada pelo provedor</span></div>
        <div class="card kpi"><span class="label">Pendente de liquidação</span><span class="value">{{ f.awaitingSettlementCents | money }}</span></div>
        <div class="card kpi"><span class="label">Saldo disponível para saque</span>
          @if (f.providerConfigured && f.providerAvailableBalanceCents !== null) { <span class="value">{{ f.providerAvailableBalanceCents | money }}</span>
            <span class="small muted">informado pelo provedor</span> }
          @else { <span class="value muted">—</span><span class="small warn-t">Provedor de pagamentos não configurado</span> }</div>
        <div class="card kpi"><span class="label">Repasses pendentes</span><span class="value">{{ d.pendingPayouts }}</span>
          <span class="small muted">{{ f.driverPayoutsPendingCents | money }}</span></div>
      </div>

      <div class="grid charts">
        <div class="card"><admin-bar-chart title="Corridas concluídas por dia" [points]="ridesSeries()" /></div>
        <div class="card"><admin-bar-chart title="Valor bruto por dia" [points]="grossSeries()" /></div>
      </div>

      <h2>Distribuição geográfica</h2>
      <div class="table-wrap"><table class="table">
        <thead><tr><th>Cidade</th><th>Concluídas</th><th>Ativas agora</th></tr></thead>
        <tbody>@for (c of d.ridesByCity; track c.cityId) {
          <tr><td>{{ c.cityName }}</td><td class="mono">{{ c.completedRides }}</td><td class="mono">{{ c.activeRides }}</td></tr>
        } @empty { <tr><td colspan="3" class="muted">Nenhuma cidade cadastrada. <a routerLink="/geografia">Cadastrar</a></td></tr> }</tbody>
      </table></div>
    }
  `,
  styles: `
    .head { margin-bottom: 8px; } h2 { margin: 24px 0 12px; }
    .kpis { grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); }
    a.kpi { color: inherit; } a.kpi:hover { text-decoration: none; border-color: var(--ld-primary); }
    .charts { grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); margin-top: 16px; }
    .alerts a { display: block; } .warn-t { color: var(--ld-warning); }
  `,
})
export default class DashboardPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly s = signal<Summary | null>(null);
  protected readonly series = signal<DayPoint[]>([]);
  protected readonly alertData = signal<Record<string, number>>({});
  protected readonly error = signal('');

  protected readonly ridesSeries = computed<BarPoint[]>(() => this.series().map((p) =>
    ({ label: p.date.slice(5).split('-').reverse().join('/'), value: p.completedRides, display: String(p.completedRides) })));
  protected readonly grossSeries = computed<BarPoint[]>(() => this.series().map((p) =>
    ({ label: p.date.slice(5).split('-').reverse().join('/'), value: p.grossCents, display: money(p.grossCents) })));
  protected readonly alerts = computed(() => {
    const a = this.alertData();
    const d = this.s();
    const list: { text: string; tone: string; to: string }[] = [];
    if (a['driversPendingReview']) list.push({ text: `${a['driversPendingReview']} cadastro(s) de motorista aguardando análise`, tone: 'info', to: '/motoristas' });
    if (a['ridesSearchingLong']) list.push({ text: `${a['ridesSearchingLong']} corrida(s) buscando motorista há mais de 2 min`, tone: 'warn', to: '/corridas' });
    if (d?.openSafetyTickets) list.push({ text: `${d.openSafetyTickets} ocorrência(s) de segurança em aberto`, tone: 'err', to: '/suporte' });
    if (d?.reconciliationIssues) list.push({ text: `${d.reconciliationIssues} pendência(s) de conciliação financeira`, tone: 'warn', to: '/financeiro' });
    if (a['reportedRatings']) list.push({ text: `${a['reportedRatings']} avaliação(ões) contestada(s)`, tone: 'info', to: '/avaliacoes' });
    if (a['driversDocumentsExpired']) list.push({ text: `${a['driversDocumentsExpired']} motorista(s) com documentação vencida`, tone: 'warn', to: '/motoristas' });
    return list;
  });

  ngOnInit(): void {
    void this.load();
    const t = setInterval(() => void this.load(), 30_000);
    this.destroyRef.onDestroy(() => clearInterval(t));
  }

  protected async load(): Promise<void> {
    try {
      const [s, series, alerts] = await Promise.all([
        this.api.get<Summary>('/admin/dashboard/summary'),
        this.api.get<DayPoint[]>('/admin/dashboard/timeseries', { days: 14 }),
        this.api.get<Record<string, number>>('/admin/dashboard/alerts'),
      ]);
      this.s.set(s);
      this.series.set(series);
      this.alertData.set(alerts);
      this.error.set('');
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    }
  }
}
