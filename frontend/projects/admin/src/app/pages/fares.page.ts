import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, DateTimePipe, MoneyPipe, StateViewComponent } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface TimeWindow { daysOfWeek: number[]; start: string; end: string; multiplierBp: number; label: string; }
interface FareConfig { id: string; cityId: string; category: string; baseFareCents: number; perKmCents: number; perMinuteCents: number;
  minimumFareCents: number; cancellationFeeCents: number; commissionBp: number; demandMultiplierBp: number; maxMultiplierBp: number;
  timeWindows: TimeWindow[]; active: boolean; version: number; updatedBy: string; updatedAt: number; }
interface Version { id: string; version: number; changedBy: string; changedAt: number; reason: string; snapshot: FareConfig; previous: FareConfig | null; }
interface Form { base: number; perKm: number; perMin: number; minimum: number; cancel: number; commission: number; demand: number; max: number; active: boolean; windows: TimeWindow[]; }

const CATEGORIES = [{ id: 'ESSENCIAL', name: 'Lucena Essencial' }, { id: 'CONFORTO', name: 'Lucena Conforto' }, { id: 'EXECUTIVO', name: 'Lucena Executivo' }];

/** Tarifas por cidade e categoria. Toda alteração cria nova versão com autor, data, valores e motivo. */
@Component({
  selector: 'admin-fares',
  imports: [FormsModule, MoneyPipe, DateTimePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Tarifas e comissões</h1>
    <div class="row pick">
      <select class="input" [(ngModel)]="cityId" (change)="load()" aria-label="Cidade">
        <option value="" disabled>Selecione a cidade</option>
        @for (c of cities(); track c.id) { <option [value]="c.id">{{ c.name }} - {{ c.state }}</option> }
      </select>
    </div>
    @if (cityId) {
      <div class="grid cats">
        @for (cat of categories; track cat.id) {
          @let cfg = configOf(cat.id);
          <section class="card stack">
            <div class="row between"><h2>{{ cat.name }}</h2>
              @if (cfg) { <span class="badge" [class.ok]="cfg.active">v{{ cfg.version }} · {{ cfg.active ? 'ativa' : 'inativa' }}</span> }
              @else { <span class="badge warn">não configurada</span> }</div>
            @if (editing() === cat.id) {
              <div class="form">
                <label>Tarifa base (R$)<input class="input" type="number" step="0.01" [(ngModel)]="form.base" /></label>
                <label>Valor por km (R$)<input class="input" type="number" step="0.01" [(ngModel)]="form.perKm" /></label>
                <label>Valor por minuto (R$)<input class="input" type="number" step="0.01" [(ngModel)]="form.perMin" /></label>
                <label>Tarifa mínima (R$)<input class="input" type="number" step="0.01" [(ngModel)]="form.minimum" /></label>
                <label>Taxa de cancelamento (R$)<input class="input" type="number" step="0.01" [(ngModel)]="form.cancel" /></label>
                <label>Comissão (%)<input class="input" type="number" step="0.1" [(ngModel)]="form.commission" /></label>
                <label>Multiplicador de demanda<input class="input" type="number" step="0.05" min="1" [(ngModel)]="form.demand" /></label>
                <label>Multiplicador máximo<input class="input" type="number" step="0.05" min="1" max="3" [(ngModel)]="form.max" /></label>
              </div>
              <fieldset class="windows"><legend class="small">Janelas horárias (opcional)</legend>
                @for (w of form.windows; track $index) {
                  <div class="row small">
                    <input class="input xs" [(ngModel)]="w.label" placeholder="Rótulo" aria-label="Rótulo" />
                    <input class="input xs" [(ngModel)]="w.start" placeholder="22:00" aria-label="Início" />
                    <input class="input xs" [(ngModel)]="w.end" placeholder="05:00" aria-label="Fim" />
                    <input class="input xs" type="number" step="0.05" [ngModel]="w.multiplierBp / 10000" (ngModelChange)="w.multiplierBp = round($event * 10000)" aria-label="Multiplicador" />
                    <span class="days">@for (d of [1,2,3,4,5,6,7]; track d) {
                      <label><input type="checkbox" [checked]="w.daysOfWeek.includes(d)" (change)="toggleDay(w, d)" />{{ dayLabel[d] }}</label> }</span>
                    <button class="btn link" (click)="form.windows.splice($index, 1)">remover</button>
                  </div>
                }
                <button class="btn ghost sm" (click)="form.windows.push({ daysOfWeek: [1,2,3,4,5,6,7], start: '22:00', end: '05:00', multiplierBp: 12000, label: 'Noturno' })">Adicionar janela</button>
              </fieldset>
              <label class="check"><input type="checkbox" [(ngModel)]="form.active" /> <span>Categoria ativa nesta cidade</span></label>
              <div class="row"><button class="btn primary" (click)="save(cat.id)">Salvar nova versão</button>
                <button class="btn ghost" (click)="editing.set(null)">Cancelar</button></div>
              <p class="small muted">Corridas já solicitadas mantêm a versão de tarifa em que foram cotadas.</p>
            } @else if (cfg) {
              <dl class="vals">
                <div><dt>Base</dt><dd>{{ cfg.baseFareCents | money }}</dd></div><div><dt>Por km</dt><dd>{{ cfg.perKmCents | money }}</dd></div>
                <div><dt>Por minuto</dt><dd>{{ cfg.perMinuteCents | money }}</dd></div><div><dt>Mínima</dt><dd>{{ cfg.minimumFareCents | money }}</dd></div>
                <div><dt>Cancelamento</dt><dd>{{ cfg.cancellationFeeCents | money }}</dd></div><div><dt>Comissão</dt><dd>{{ cfg.commissionBp / 100 }}%</dd></div>
                <div><dt>Demanda / teto</dt><dd>{{ cfg.demandMultiplierBp / 10000 }}× / {{ cfg.maxMultiplierBp / 10000 }}×</dd></div>
                <div><dt>Janelas</dt><dd>{{ cfg.timeWindows.length }}</dd></div>
              </dl>
              <p class="small muted">Atualizada em {{ cfg.updatedAt | dt }} por {{ cfg.updatedBy }}</p>
            }
            <div class="row">
              @if (canEdit() && editing() !== cat.id) { <button class="btn ghost sm" (click)="edit(cat.id)">{{ cfg ? 'Editar' : 'Configurar' }}</button> }
              @if (cfg) { <button class="btn ghost sm" (click)="history(cfg.id)">Histórico</button> }
            </div>
            @if (historyOf() === cfg?.id) {
              @for (v of versions(); track v.id) {
                <div class="small ver"><strong>v{{ v.version }}</strong> · {{ v.changedAt | dt }} · {{ v.changedBy }} · “{{ v.reason }}”
                  @if (v.previous) { <br /><span class="muted">base {{ v.previous.baseFareCents | money }} → {{ v.snapshot.baseFareCents | money }},
                    km {{ v.previous.perKmCents | money }} → {{ v.snapshot.perKmCents | money }}, comissão {{ v.previous.commissionBp / 100 }}% → {{ v.snapshot.commissionBp / 100 }}%</span> }</div>
              }
            }
          </section>
        }
      </div>
    } @else { <ld-state state="empty" title="Selecione uma cidade" message="Cadastre cidades em Gestão geográfica." /> }
  `,
  styles: `
    .pick { margin: 12px 0 16px; } .pick .input { max-width: 320px; }
    .cats { grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); align-items: start; }
    .form { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; } .form label { font-size: .82rem; font-weight: 600; display: flex; flex-direction: column; gap: 4px; }
    .vals { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 16px; margin: 0; } .vals div { display: flex; justify-content: space-between; }
    dt { color: var(--ld-text-muted); font-size: .85rem; } dd { margin: 0; font-weight: 600; }
    .windows { border: 1px solid var(--ld-border); border-radius: 10px; padding: 8px; } .xs { width: 80px; min-height: 36px; padding: 4px 8px; }
    .days label { margin-right: 4px; } .ver { padding: 6px 0; border-top: 1px solid var(--ld-border); }
  `,
})
export default class FaresPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  protected readonly categories = CATEGORIES;
  protected readonly dayLabel = ['', 'S', 'T', 'Q', 'Q', 'S', 'S', 'D'];
  protected readonly cities = signal<{ id: string; name: string; state: string }[]>([]);
  protected readonly configs = signal<FareConfig[]>([]);
  protected readonly editing = signal<string | null>(null);
  protected readonly historyOf = signal<string | null>(null);
  protected readonly versions = signal<Version[]>([]);
  protected readonly canEdit = () => this.a.session.can('ADMIN', 'OWNER');
  protected cityId = '';
  protected form: Form = this.empty();

  async ngOnInit(): Promise<void> {
    this.cities.set(await this.api.get('/admin/cities'));
    if (this.cities().length) {
      this.cityId = this.cities()[0].id;
      await this.load();
    }
  }

  protected async load(): Promise<void> {
    this.editing.set(null);
    this.historyOf.set(null);
    this.configs.set(await this.api.get<FareConfig[]>(`/admin/cities/${this.cityId}/fares`));
  }

  protected configOf(cat: string): FareConfig | undefined {
    return this.configs().find((c) => c.category === cat);
  }

  protected round(v: number): number {
    return Math.round(v);
  }

  private empty(): Form {
    return { base: 5, perKm: 2, perMin: 0.3, minimum: 8, cancel: 7, commission: 20, demand: 1, max: 2, active: true, windows: [] };
  }

  protected edit(cat: string): void {
    const c = this.configOf(cat);
    this.form = c ? { base: c.baseFareCents / 100, perKm: c.perKmCents / 100, perMin: c.perMinuteCents / 100, minimum: c.minimumFareCents / 100,
      cancel: c.cancellationFeeCents / 100, commission: c.commissionBp / 100, demand: c.demandMultiplierBp / 10000,
      max: c.maxMultiplierBp / 10000, active: c.active, windows: structuredClone(c.timeWindows ?? []) } : this.empty();
    this.editing.set(cat);
  }

  protected toggleDay(w: TimeWindow, d: number): void {
    w.daysOfWeek = w.daysOfWeek.includes(d) ? w.daysOfWeek.filter((x) => x !== d) : [...w.daysOfWeek, d].sort();
  }

  protected async save(cat: string): Promise<void> {
    const f = this.form;
    const cents = (v: number) => Math.round(Number(v) * 100);
    const config = { baseFareCents: cents(f.base), perKmCents: cents(f.perKm), perMinuteCents: cents(f.perMin),
      minimumFareCents: cents(f.minimum), cancellationFeeCents: cents(f.cancel), commissionBp: Math.round(Number(f.commission) * 100),
      demandMultiplierBp: Math.round(Number(f.demand) * 10000), maxMultiplierBp: Math.round(Number(f.max) * 10000),
      timeWindows: f.windows, active: f.active };
    const ok = await this.a.withReason('Salvar nova versão da tarifa',
      (reason) => this.api.put(`/admin/cities/${this.cityId}/fares/${cat}`, { config, reason }),
      { ok: 'Nova versão tarifária salva.', message: 'Operação crítica: pode ser solicitada a confirmação do segundo fator.' });
    if (ok) {
      this.configs.set(await this.api.get<FareConfig[]>(`/admin/cities/${this.cityId}/fares`));
      this.editing.set(null);
    }
  }

  protected async history(id: string): Promise<void> {
    if (this.historyOf() === id) {
      this.historyOf.set(null);
      return;
    }
    this.versions.set(await this.api.get<Version[]>(`/admin/fares/${id}/history`));
    this.historyOf.set(id);
  }
}
