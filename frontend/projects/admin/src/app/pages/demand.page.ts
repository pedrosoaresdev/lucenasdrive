import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, DateTimePipe, HeatCell, MapComponent, StateViewComponent } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';
import { AdminCity } from '../core/map-tools.component';

interface Step { minRatio: number; multiplierBp: number; }
interface Policy { cityId: string; enabled: boolean; steps: Step[]; maxBp: number; windowMinutes: number; updatedBy: string | null; updatedAt: string | null; }
interface State { demand: number; supply: number; ratio: number; multiplierBp: number; updatedAt: string; }

/**
 * Demanda e preço dinâmico por cidade. O preço dinâmico é OPCIONAL (desligado por padrão), limitado por teto e
 * pelo teto de cada tarifa, e o passageiro sempre vê o aviso de alta demanda antes de confirmar.
 */
@Component({
  selector: 'admin-demand',
  imports: [FormsModule, MapComponent, DateTimePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Demanda e preço dinâmico</h1>
    <label class="field-inline">Cidade
      <select class="input" [ngModel]="cityId()" (ngModelChange)="pick($event)" aria-label="Cidade">
        @for (c of cities(); track c.id) { <option [value]="c.id">{{ c.name }} - {{ c.state }}</option> }
      </select>
    </label>
    @if (!cities().length) { <ld-state state="empty" title="Nenhuma cidade" message="Cadastre cidades em Gestão geográfica." /> }
    @if (policy(); as p) {
      <div class="grid layout">
        <div class="stack">
          <section class="card stack">
            <div class="row between"><h2>Agora</h2>
              <button class="btn ghost sm" (click)="recompute()" [disabled]="!canEdit()">Recalcular agora</button></div>
            @if (state(); as s) {
              <div class="grid kpis">
                <div class="kpi"><span class="label">Passageiros pedindo</span><span class="value">{{ s.demand }}</span></div>
                <div class="kpi"><span class="label">Motoristas livres</span><span class="value">{{ s.supply }}</span></div>
                <div class="kpi"><span class="label">Razão</span><span class="value">{{ s.ratio.toFixed(2).replace('.', ',') }}</span></div>
                <div class="kpi"><span class="label">Multiplicador</span><span class="value">{{ x(s.multiplierBp) }}×</span></div>
              </div>
              <p class="small muted">Calculado em {{ s.updatedAt | dt }} · janela de {{ p.windowMinutes }} min.
                {{ p.enabled ? 'Aplicado às novas cotações.' : 'Preço dinâmico desligado: só informativo.' }}</p>
            } @else { <p class="small muted">Sem cálculo ainda. Clique em "Recalcular agora".</p> }
          </section>
          <section class="card stack">
            <h2>Política da cidade</h2>
            <label class="check"><input type="checkbox" [(ngModel)]="form.enabled" [disabled]="!canEdit()" />
              <span><strong>Preço dinâmico ligado</strong> (o passageiro vê o aviso de alta demanda antes de confirmar)</span></label>
            <p class="small muted">Razão = passageiros diferentes pedindo preço ou corrida na janela ÷ motoristas livres. Vale o maior
              degrau atingido; o fator manual de demanda da tarifa não se soma (vale o maior) e o teto da tarifa continua valendo.</p>
            <div class="steps">
              <span class="small muted">Razão a partir de</span><span class="small muted">Multiplicador</span><span></span>
              @for (s of form.steps; track $index) {
                <input class="input" type="number" min="1" max="50" step="0.5" [(ngModel)]="s.minRatio" [disabled]="!canEdit()" aria-label="Razão mínima" />
                <input class="input" type="number" min="1" max="3" step="0.1" [(ngModel)]="s.mult" [disabled]="!canEdit()" aria-label="Multiplicador" />
                <button class="btn link sm" (click)="form.steps.splice($index, 1)" [disabled]="!canEdit() || form.steps.length === 1">remover</button>
              }
            </div>
            @if (canEdit() && form.steps.length < 6) {
              <button class="btn ghost sm" (click)="form.steps.push({ minRatio: 4, mult: 1.5 })">Adicionar degrau</button>
            }
            <div class="row">
              <label class="small">Teto <input class="input sm-in" type="number" min="1" max="3" step="0.1" [(ngModel)]="form.max" [disabled]="!canEdit()" /></label>
              <label class="small">Janela (min) <input class="input sm-in" type="number" min="5" max="60" [(ngModel)]="form.window" [disabled]="!canEdit()" /></label>
            </div>
            @if (canEdit()) { <button class="btn primary" (click)="save()">Salvar política</button> }
            @if (p.updatedAt) { <p class="small muted">Última alteração em {{ p.updatedAt | dt }} por {{ p.updatedBy }}</p> }
          </section>
        </div>
        <div class="stack">
          <div class="card map-card"><ld-map [heat]="heat()" [center]="center()" [zoom]="12" [fitToContent]="false" /></div>
          <p class="small muted">Mapa de calor: pedidos dos últimos 30 minutos agrupados em quadras de ~550 m (quadras com
            menos de 2 passageiros não aparecem). Os motoristas veem o mesmo mapa no app. {{ heat().length }} quadra(s).</p>
        </div>
      </div>
    }
  `,
  styles: `.field-inline { display: flex; align-items: center; gap: 8px; font-weight: 600; margin: 8px 0 16px; }
    .field-inline select { width: auto; min-width: 240px; } .layout { grid-template-columns: 420px 1fr; align-items: start; }
    .kpis { grid-template-columns: repeat(2, 1fr); gap: 8px; } .kpi { display: flex; flex-direction: column; }
    .kpi .label { font-size: .8rem; color: var(--ld-text-muted); } .kpi .value { font-size: 1.4rem; font-weight: 800; }
    .steps { display: grid; grid-template-columns: 1fr 1fr auto; gap: 6px; align-items: center; } .sm-in { width: 90px; }
    .map-card { padding: 0; overflow: hidden; height: 65vh; } .map-card ld-map { height: 100%; }
    @media (max-width: 1100px) { .layout { grid-template-columns: 1fr; } .map-card { height: 50vh; } }`,
})
export default class DemandPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  private readonly destroyRef = inject(DestroyRef);
  protected readonly cities = signal<AdminCity[]>([]);
  protected readonly cityId = signal('');
  protected readonly policy = signal<Policy | null>(null);
  protected readonly state = signal<State | null>(null);
  protected readonly heat = signal<HeatCell[]>([]);
  protected readonly center = signal({ lat: -14.235, lng: -51.925 });
  protected readonly canEdit = () => this.a.session.can('ADMIN', 'OWNER');
  protected form = { enabled: false, steps: [] as { minRatio: number; mult: number }[], max: 1.6, window: 10 };

  protected x(bp: number): string {
    return (bp / 10000).toFixed(1).replace('.', ',');
  }

  async ngOnInit(): Promise<void> {
    this.cities.set(await this.api.get<AdminCity[]>('/admin/cities').catch(() => []));
    if (this.cities().length) await this.pick(this.cities()[0].id);
    const t = setInterval(() => { if (this.cityId()) void this.refresh(); }, 60_000);
    this.destroyRef.onDestroy(() => clearInterval(t));
  }

  protected async pick(id: string): Promise<void> {
    this.cityId.set(id);
    const c = this.cities().find((x) => x.id === id);
    if (c) this.center.set(c.center);
    await this.refresh(true);
  }

  private async refresh(resetForm = false): Promise<void> {
    const id = this.cityId();
    const [d, h] = await Promise.all([
      this.api.get<{ policy: Policy; state: State | null }>(`/admin/cities/${id}/demand`),
      this.api.get<HeatCell[]>(`/admin/cities/${id}/demand-heatmap`).catch(() => []),
    ]);
    this.policy.set(d.policy);
    this.state.set(d.state);
    this.heat.set(h);
    if (resetForm) {
      this.form = { enabled: d.policy.enabled, steps: d.policy.steps.map((s) => ({ minRatio: s.minRatio, mult: s.multiplierBp / 10000 })),
        max: d.policy.maxBp / 10000, window: d.policy.windowMinutes };
    }
  }

  protected async recompute(): Promise<void> {
    const s = await this.a.exec(() => this.api.post<State>(`/admin/cities/${this.cityId()}/demand/recompute`));
    if (s) this.state.set(s);
  }

  protected async save(): Promise<void> {
    const f = this.form;
    const body = { enabled: f.enabled, maxBp: Math.round(Number(f.max) * 10000), windowMinutes: Math.round(Number(f.window)),
      steps: f.steps.map((s) => ({ minRatio: Number(s.minRatio), multiplierBp: Math.round(Number(s.mult) * 10000) })) };
    const ok = await this.a.withReason(f.enabled ? 'Salvar política (preço dinâmico LIGADO)' : 'Salvar política (desligado)',
      (reason) => this.api.put(`/admin/cities/${this.cityId()}/demand-policy`, { ...body, reason }),
      { ok: 'Política de demanda salva.', message: 'Mudanças de preço afetam passageiros: a justificativa fica na auditoria.' });
    if (ok) await this.refresh(true);
  }
}
