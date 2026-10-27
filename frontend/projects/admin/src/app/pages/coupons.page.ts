import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, DateTimePipe, MoneyPipe, StateViewComponent } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Coupon { code: string; description: string; type: 'PERCENT' | 'FIXED'; percentBp: number; amountCents: number;
  maxDiscountCents: number; minFareCents: number; startsAt: string | null; endsAt: string | null; maxRedemptions: number;
  maxPerUser: number; redemptionCount: number; budgetCents: number; budgetUsedCents: number; cityIds: string[];
  categories: string[]; newPassengersOnly: boolean; eligibleUserIds: string[]; active: boolean; createdAt: string; }
interface Results { coupon: Coupon; confirmedRedemptions: number; reservedRedemptions: number; confirmedDiscountCents: number; }

/** Campanhas promocionais. Validação, limites e orçamento são aplicados no servidor (com transação). */
@Component({
  selector: 'admin-coupons',
  imports: [FormsModule, MoneyPipe, DateTimePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Cupons e promoções</h1>
    @if (canEdit()) {
      <details class="card create"><summary><strong>Criar campanha</strong></summary>
        <div class="form">
          <label>Código (4–20 letras/números)<input class="input" [(ngModel)]="f.code" maxlength="20" /></label>
          <label>Descrição<input class="input" [(ngModel)]="f.description" /></label>
          <label>Tipo<select class="input" [(ngModel)]="f.type"><option value="PERCENT">Percentual</option><option value="FIXED">Valor fixo</option></select></label>
          @if (f.type === 'PERCENT') { <label>Desconto (%)<input class="input" type="number" [(ngModel)]="f.percent" /></label> }
          @else { <label>Desconto (R$)<input class="input" type="number" step="0.01" [(ngModel)]="f.amount" /></label> }
          <label>Desconto máximo (R$, 0 = sem teto)<input class="input" type="number" step="0.01" [(ngModel)]="f.maxDiscount" /></label>
          <label>Valor mínimo da corrida (R$)<input class="input" type="number" step="0.01" [(ngModel)]="f.minFare" /></label>
          <label>Início<input class="input" type="datetime-local" [(ngModel)]="f.startsAt" /></label>
          <label>Fim<input class="input" type="datetime-local" [(ngModel)]="f.endsAt" /></label>
          <label>Limite total de usos (0 = ilimitado)<input class="input" type="number" [(ngModel)]="f.maxRedemptions" /></label>
          <label>Limite por usuário (0 = ilimitado)<input class="input" type="number" [(ngModel)]="f.maxPerUser" /></label>
          <label>Orçamento (R$, 0 = sem orçamento)<input class="input" type="number" step="0.01" [(ngModel)]="f.budget" /></label>
          <label>Cidades (IDs separados por vírgula)<input class="input" [(ngModel)]="f.cities" /></label>
          <label>Usuários elegíveis (UIDs, opcional)<input class="input" [(ngModel)]="f.users" /></label>
          <fieldset class="cats"><legend>Categorias</legend>
            @for (c of ['ESSENCIAL', 'CONFORTO', 'EXECUTIVO']; track c) {
              <label class="check"><input type="checkbox" [checked]="f.categories.includes(c)" (change)="toggleCat(c)" />{{ c }}</label> }</fieldset>
          <label class="check"><input type="checkbox" [(ngModel)]="f.newOnly" /> Somente novos passageiros (1ª viagem)</label>
        </div>
        <button class="btn primary" (click)="create()" [disabled]="!f.code">Criar cupom</button>
      </details>
    }
    @if (!coupons()) { <ld-state state="loading" /> }
    @else {
      <div class="table-wrap"><table class="table">
        <thead><tr><th>Código</th><th>Desconto</th><th>Validade</th><th>Usos</th><th>Orçamento</th><th>Status</th><th></th></tr></thead>
        <tbody>@for (c of coupons(); track c.code) {
          <tr><td class="mono"><strong>{{ c.code }}</strong><br /><span class="small muted">{{ c.description }}</span></td>
            <td>{{ c.type === 'PERCENT' ? c.percentBp / 100 + '%' : (c.amountCents | money) }} @if (c.maxDiscountCents) { <span class="small muted">(máx {{ c.maxDiscountCents | money }})</span> }</td>
            <td class="small">{{ c.startsAt ? (c.startsAt | dt) : '—' }} → {{ c.endsAt ? (c.endsAt | dt) : 'sem fim' }}</td>
            <td>{{ c.redemptionCount }}{{ c.maxRedemptions ? ' / ' + c.maxRedemptions : '' }}</td>
            <td>{{ c.budgetCents ? (c.budgetUsedCents | money) + ' / ' + (c.budgetCents | money) : '—' }}</td>
            <td><span class="badge" [class.ok]="c.active">{{ c.active ? 'Ativo' : 'Inativo' }}</span></td>
            <td class="row"><button class="btn ghost sm" (click)="results(c)">Resultados</button>
              @if (canEdit()) { <button class="btn ghost sm" (click)="toggle(c)">{{ c.active ? 'Encerrar' : 'Ativar' }}</button> }</td></tr>
        } @empty { <tr><td colspan="7" class="muted">Nenhuma campanha.</td></tr> }</tbody>
      </table></div>
    }
    @if (res(); as r) {
      <section class="card stack res">
        <h2>Resultados · {{ r.coupon.code }}</h2>
        <p>{{ r.confirmedRedemptions }} resgates confirmados · {{ r.reservedRedemptions }} reservados (corridas em curso) ·
          desconto concedido {{ r.confirmedDiscountCents | money }}</p>
        @for (x of redemptions(); track x.id) { <p class="small mono">{{ x.createdAt | dt }} · {{ x.userId }} · corrida {{ x.rideId }} · {{ x.discountCents | money }} · {{ x.status }}</p> }
      </section>
    }
  `,
  styles: `.create { margin: 12px 0 16px; } summary { cursor: pointer; }
    .form { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin: 12px 0; }
    .form label { font-size: .82rem; font-weight: 600; display: flex; flex-direction: column; gap: 4px; } .cats { border: 0; padding: 0; }
    .res { margin-top: 16px; }`,
})
export default class CouponsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  protected readonly coupons = signal<Coupon[] | null>(null);
  protected readonly res = signal<Results | null>(null);
  protected readonly redemptions = signal<{ id: string; userId: string; rideId: string; discountCents: number; status: string; createdAt: string }[]>([]);
  protected readonly canEdit = () => this.a.session.can('ADMIN', 'OWNER');
  protected f = this.blank();

  private blank() {
    return { code: '', description: '', type: 'PERCENT', percent: 10, amount: 5, maxDiscount: 0, minFare: 0, startsAt: '', endsAt: '',
      maxRedemptions: 0, maxPerUser: 1, budget: 0, cities: '', users: '', categories: [] as string[], newOnly: false };
  }

  async ngOnInit(): Promise<void> {
    this.coupons.set(await this.api.get<Coupon[]>('/admin/coupons'));
  }

  protected toggleCat(c: string): void {
    this.f.categories = this.f.categories.includes(c) ? this.f.categories.filter((x) => x !== c) : [...this.f.categories, c];
  }

  protected async create(): Promise<void> {
    const f = this.f;
    const list = (s: string) => s.split(',').map((x) => x.trim()).filter(Boolean);
    const body = { code: f.code, description: f.description, type: f.type, percentBp: Math.round(f.percent * 100),
      amountCents: Math.round(f.amount * 100), maxDiscountCents: Math.round(f.maxDiscount * 100), minFareCents: Math.round(f.minFare * 100),
      startsAt: f.startsAt ? new Date(f.startsAt).toISOString() : null, endsAt: f.endsAt ? new Date(f.endsAt).toISOString() : null,
      maxRedemptions: f.maxRedemptions, maxPerUser: f.maxPerUser, budgetCents: Math.round(f.budget * 100), cityIds: list(f.cities),
      categories: f.categories, newPassengersOnly: f.newOnly, eligibleUserIds: list(f.users), active: true };
    if (await this.a.exec(() => this.api.post('/admin/coupons', body), 'Cupom criado.')) {
      this.f = this.blank();
      await this.ngOnInit();
    }
  }

  protected async toggle(c: Coupon): Promise<void> {
    const ok = await this.a.withReason(c.active ? 'Encerrar campanha' : 'Ativar campanha',
      (reason) => this.api.post(`/admin/coupons/${c.code}/active`, { active: !c.active, reason }), { minLength: 5, ok: 'Campanha atualizada.' });
    if (ok) await this.ngOnInit();
  }

  protected async results(c: Coupon): Promise<void> {
    this.res.set(await this.api.get<Results>(`/admin/coupons/${c.code}/results`));
    this.redemptions.set(await this.api.get(`/admin/coupons/${c.code}/redemptions`));
  }
}
