import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, DateTimePipe, MoneyPipe, RideStatusPipe, StateViewComponent } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';
import { DRIVER_STATUS } from './drivers.page';

interface Doc { type: string; status: string; vehicleId: string | null; contentType: string; uploadedAt: number; reviewNote: string | null; expiresOn: string | null; }
interface Vehicle { id: string; make: string; model: string; year: number; color: string; plate: string; category: string; status: string; statusReason: string | null; }
interface Detail {
  user: { fullName: string; email: string; phoneNumber: string; cpfMasked: string; status: string } | null;
  profile: { id: string; status: string; statusReason: string | null; cnhNumberMasked: string; cnhCategory: string; cnhExpiresOn: string;
    documents: Doc[]; cityId: string; activeVehicleId: string | null; ratingAvg: number; ratingCount: number; completedRides: number;
    cancelledRides: number; warnings: number; starCounts: Record<string, number> };
  vehicles: Vehicle[];
  location: { online: boolean; lat: number | null; lng: number | null; updatedAt: number; currentRideId: string | null } | null;
  recentRides: { id: string; code: string; status: never; createdAt: number; fare: { totalCents: number; driverEarningCents: number } }[];
}
interface Finance { periods: { period: string; rides: number; earningsCents: number; commissionCents: number }[]; withdrawableCents: number; }

const DOC: Record<string, string> = { CNH: 'CNH', CRIMINAL_RECORD: 'Antecedentes', PROFILE_PHOTO: 'Foto de perfil', PROOF_OF_ADDRESS: 'Comprovante de residência', CRLV: 'CRLV' };

/** Análise e gestão do motorista: documentos, veículos, decisões com motivo, advertências e métricas. */
@Component({
  selector: 'admin-driver-detail',
  imports: [RouterLink, DateTimePipe, MoneyPipe, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/motoristas" class="small">← Motoristas</a>
    @if (!d()) { <ld-state state="loading" /> }
    @else {
      @let p = d()!.profile;
      <div class="row between head">
        <h1>{{ d()!.user?.fullName }}</h1>
        <span class="badge" [class.ok]="p.status === 'APPROVED'" [class.warn]="p.status === 'PENDING_REVIEW'">{{ label[p.status] }}</span>
      </div>
      <section class="card stack">
        <p>{{ d()!.user?.email }} · {{ d()!.user?.phoneNumber }} · CPF <span class="mono">{{ d()!.user?.cpfMasked }}</span></p>
        <p class="small muted">CNH {{ p.cnhNumberMasked }} · cat. {{ p.cnhCategory }} · validade {{ p.cnhExpiresOn }} · cidade {{ p.cityId }}
          @if (p.statusReason) { · motivo: {{ p.statusReason }} }</p>
        <p class="small">★ {{ p.ratingCount ? p.ratingAvg.toFixed(2) : '—' }} ({{ p.ratingCount }}) · {{ p.completedRides }} concluídas ·
          {{ p.cancelledRides }} canceladas · {{ p.warnings }} advertência(s)
          @if (d()!.location; as l) { · {{ l.online ? 'online' : 'offline' }} @if (l.currentRideId) { · <a [routerLink]="['/corridas', l.currentRideId]">corrida atual</a> } }</p>
        @if (canDecide()) {
          <div class="row">
            @if (p.status === 'PENDING_REVIEW') {
              <button class="btn primary sm" (click)="status('APPROVED')">Aprovar motorista</button>
              <button class="btn danger sm" (click)="status('REJECTED')">Recusar</button>
            }
            @if (p.status === 'APPROVED' || p.status === 'DOCUMENTS_EXPIRED') { <button class="btn danger sm" (click)="status('SUSPENDED')">Suspender</button> }
            @if (p.status === 'SUSPENDED' || p.status === 'DOCUMENTS_EXPIRED') { <button class="btn primary sm" (click)="status('APPROVED')">Reativar</button> }
            @if (p.status === 'REJECTED') { <button class="btn ghost sm" (click)="status('PENDING_REVIEW')">Reabrir análise</button> }
          </div>
        }
        <div class="row">
          <button class="btn ghost sm" (click)="warn()">Registrar advertência</button>
          <a class="btn ghost sm" routerLink="/auditoria" [queryParams]="{ targetId: p.id }">Trilha de auditoria</a>
          <a class="btn ghost sm" routerLink="/corridas" [queryParams]="{ driverId: p.id }">Corridas</a>
        </div>
      </section>

      <div class="grid cols">
        <section class="card stack">
          <h2>Documentos</h2>
          @for (doc of p.documents; track doc.type + (doc.vehicleId ?? '')) {
            <div class="doc">
              <div class="row between"><strong>{{ docLabel[doc.type] }}</strong>
                <span class="badge" [class.ok]="doc.status === 'APPROVED'" [class.warn]="doc.status === 'PENDING'" [class.err]="doc.status === 'REJECTED'">{{ doc.status }}</span></div>
              <span class="small muted">Enviado {{ doc.uploadedAt | dt }} @if (doc.reviewNote) { · {{ doc.reviewNote }} }</span>
              <div class="row">
                <button class="btn ghost sm" (click)="view(doc)">Visualizar</button>
                @if (canDecide() && doc.status !== 'APPROVED') { <button class="btn primary sm" (click)="review(doc, true)">Aprovar</button> }
                @if (canDecide() && doc.status !== 'REJECTED') { <button class="btn ghost sm" (click)="review(doc, false)">Recusar</button> }
              </div>
            </div>
          } @empty { <p class="small muted">Nenhum documento enviado.</p> }
        </section>
        <section class="card stack">
          <h2>Veículos</h2>
          @for (v of d()!.vehicles; track v.id) {
            <div class="doc">
              <div class="row between"><strong>{{ v.make }} {{ v.model }} {{ v.year }}</strong><span class="badge">{{ v.status }}</span></div>
              <span class="small">{{ v.color }} · <span class="mono">{{ v.plate }}</span> · {{ v.category }} {{ p.activeVehicleId === v.id ? '· ativo' : '' }}</span>
              @if (canDecide()) {
                <div class="row">
                  @if (v.status !== 'APPROVED') { <button class="btn primary sm" (click)="vehicle(v, 'APPROVED')">Aprovar</button> }
                  @if (v.status === 'PENDING_REVIEW') { <button class="btn ghost sm" (click)="vehicle(v, 'REJECTED')">Recusar</button> }
                  @if (v.status === 'APPROVED') { <button class="btn ghost sm" (click)="vehicle(v, 'SUSPENDED')">Suspender</button> }
                </div>
              }
            </div>
          } @empty { <p class="small muted">Nenhum veículo.</p> }
        </section>
        <section class="card stack">
          <h2>Ganhos e comissões</h2>
          @if (fin(); as f) {
            @for (x of f.periods; track x.period) { <p class="small">{{ x.period }}: {{ x.rides }} corridas · ganhos {{ x.earningsCents | money }} · comissão {{ x.commissionCents | money }}</p> }
            <p class="small">Disponível para saque: {{ f.withdrawableCents | money }}</p>
          } @else { <p class="small muted">Visível para perfis financeiros.</p> }
        </section>
        <section class="card stack">
          <h2>Corridas recentes</h2>
          @for (r of d()!.recentRides; track r.id) {
            <a class="row between small" [routerLink]="['/corridas', r.id]"><span class="mono">{{ r.code }}</span><span>{{ r.createdAt | dt }}</span>
              <span>{{ r.status | rideStatus }}</span></a>
          } @empty { <p class="small muted">Nenhuma.</p> }
        </section>
      </div>
    }
  `,
  styles: `.head { margin: 8px 0 16px; } .cols { margin-top: 16px; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); }
    .doc { padding: 10px 0; border-top: 1px solid var(--ld-border); display: flex; flex-direction: column; gap: 6px; }`,
})
export default class DriverDetailPage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly a = adminActions();
  readonly driverId = input.required<string>();
  protected readonly d = signal<Detail | null>(null);
  protected readonly fin = signal<Finance | null>(null);
  protected readonly label = DRIVER_STATUS;
  protected readonly docLabel = DOC;
  protected readonly canDecide = () => this.a.session.can('ADMIN', 'OWNER');

  async ngOnInit(): Promise<void> {
    this.d.set(await this.api.get<Detail>(`/admin/drivers/${this.driverId()}`));
    if (this.a.session.can('FINANCE', 'ADMIN', 'OWNER')) {
      this.fin.set(await this.api.get<Finance>(`/admin/drivers/${this.driverId()}/finance`).catch(() => null));
    }
  }

  protected async status(s: string): Promise<void> {
    const titles: Record<string, string> = { APPROVED: 'Aprovar / reativar motorista', REJECTED: 'Recusar cadastro',
      SUSPENDED: 'Suspender motorista', PENDING_REVIEW: 'Reabrir análise' };
    const ok = await this.a.withReason(titles[s], (reason) => this.api.post(`/admin/drivers/${this.driverId()}/status`, { status: s, reason }),
      { minLength: 5, danger: s !== 'APPROVED' && s !== 'PENDING_REVIEW', ok: 'Status atualizado e motorista notificado.' });
    if (ok) await this.ngOnInit();
  }

  protected async review(doc: Doc, approve: boolean): Promise<void> {
    const body = (note: string) => this.api.post(`/admin/drivers/${this.driverId()}/documents/review`,
      { type: doc.type, vehicleId: doc.vehicleId, approve, note: note || null });
    const ok = approve ? await this.a.exec(() => body(''), 'Documento aprovado.')
      : await this.a.withReason('Recusar documento', body, { minLength: 5, ok: 'Documento recusado.' });
    if (ok !== null) await this.ngOnInit();
  }

  protected async view(doc: Doc): Promise<void> {
    const r = await this.a.exec(() => this.api.get<{ url: string }>(`/admin/drivers/${this.driverId()}/documents/url`,
      { type: doc.type, vehicleId: doc.vehicleId }));
    if (r) window.open(r.url, '_blank', 'noopener');
  }

  protected async vehicle(v: Vehicle, status: string): Promise<void> {
    const call = (reason: string) => this.api.post(`/admin/drivers/vehicles/${v.id}/status`, { status, reason: reason || null });
    const ok = status === 'APPROVED' ? await this.a.exec(() => call(''), 'Veículo aprovado.')
      : await this.a.withReason(status === 'REJECTED' ? 'Recusar veículo' : 'Suspender veículo', call, { minLength: 5, danger: true });
    if (ok !== null) await this.ngOnInit();
  }

  protected async warn(): Promise<void> {
    const ok = await this.a.withReason('Registrar advertência', (reason) => this.api.post(`/admin/drivers/${this.driverId()}/warnings`, { reason }),
      { minLength: 5, ok: 'Advertência registrada.' });
    if (ok) await this.ngOnInit();
  }
}
