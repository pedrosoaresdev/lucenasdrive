import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, DateTimePipe, MoneyPipe, RideStatusPipe, StateViewComponent } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Detail {
  user: { id: string; fullName: string; email: string; phoneNumber: string; roles: string[]; status: string; statusReason: string | null; cpfMasked: string; createdAt: string };
  passengerProfile: { completedRides: number; cancelledRides: number } | null;
  recentRides: { id: string; code: string; status: never; createdAt: number; fare: { finalCents: number } }[];
  ratingsGiven: { id: string; stars: number; comment: string; createdAt: number; status: string }[];
  tickets: { id: string; subject: string; status: string; createdAt: number }[];
}

@Component({
  selector: 'admin-user-detail',
  imports: [RouterLink, DateTimePipe, MoneyPipe, RideStatusPipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/passageiros" class="small">← Passageiros</a>
    @if (!d()) { <ld-state state="loading" /> }
    @else {
      @let u = d()!.user;
      <div class="row between head"><h1>{{ u.fullName }}</h1>
        <span class="badge" [class.ok]="u.status === 'ACTIVE'" [class.err]="u.status !== 'ACTIVE'">{{ u.status }}</span></div>
      <section class="card stack">
        <p>{{ u.email }} · {{ u.phoneNumber }} · CPF <span class="mono">{{ pii()?.['cpf'] ?? u.cpfMasked }}</span>
          @if (pii()) { · nascimento {{ pii()!['birthDate'] }} }</p>
        <p class="small muted">Papéis: {{ u.roles.join(', ') }} · cadastro em {{ u.createdAt | dt }}
          @if (u.statusReason) { · motivo: {{ u.statusReason }} }</p>
        @if (d()!.passengerProfile; as p) { <p class="small">{{ p.completedRides }} corridas concluídas · {{ p.cancelledRides }} canceladas</p> }
        <div class="row">
          @if (u.status === 'ACTIVE') { <button class="btn danger sm" (click)="setStatus('SUSPENDED')">Suspender</button> }
          @else { <button class="btn primary sm" (click)="setStatus('ACTIVE')">Reativar</button> }
          @if (!pii() && a.session.can('ADMIN', 'OWNER')) { <button class="btn ghost sm" (click)="reveal()">Ver dados pessoais completos</button> }
          <a class="btn ghost sm" routerLink="/auditoria" [queryParams]="{ targetId: u.id }">Histórico administrativo</a>
        </div>
      </section>
      <div class="grid cols">
        <section class="card stack"><h2>Corridas recentes</h2>
          @for (r of d()!.recentRides; track r.id) {
            <a class="row between small" [routerLink]="['/corridas', r.id]"><span class="mono">{{ r.code }}</span>
              <span>{{ r.createdAt | dt }}</span><span>{{ r.status | rideStatus }}</span><span>{{ r.fare.finalCents | money }}</span></a>
          } @empty { <p class="small muted">Sem corridas.</p> }</section>
        <section class="card stack"><h2>Avaliações feitas</h2>
          @for (r of d()!.ratingsGiven; track r.id) { <p class="small">{{ '★'.repeat(r.stars) }} · {{ r.comment || 'sem comentário' }}</p> }
          @empty { <p class="small muted">Nenhuma.</p> }</section>
        <section class="card stack"><h2>Ocorrências / chamados</h2>
          @for (t of d()!.tickets; track t.id) { <p class="small">{{ t.createdAt | dt }} · {{ t.subject }} · <span class="badge">{{ t.status }}</span></p> }
          @empty { <p class="small muted">Nenhum.</p> }</section>
      </div>
    }
  `,
  styles: `.head { margin: 8px 0 16px; } .cols { margin-top: 16px; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); }`,
})
export default class UserDetailPage implements OnInit {
  private readonly api = inject(ApiService);
  protected readonly a = adminActions();
  readonly uid = input.required<string>();
  protected readonly d = signal<Detail | null>(null);
  protected readonly pii = signal<Record<string, string> | null>(null);

  async ngOnInit(): Promise<void> {
    this.d.set(await this.api.get<Detail>(`/admin/users/${this.uid()}`));
  }

  protected async setStatus(status: string): Promise<void> {
    const r = await this.a.withReason(status === 'SUSPENDED' ? 'Suspender conta' : 'Reativar conta',
      (reason) => this.api.post(`/admin/users/${this.uid()}/status`, { status, reason }),
      { danger: status === 'SUSPENDED', ok: 'Status atualizado.', message: 'O histórico de corridas e avaliações é preservado.' });
    if (r) await this.ngOnInit();
  }

  protected async reveal(): Promise<void> {
    const r = await this.a.withReason('Acessar dados pessoais',
      (reason) => this.api.post<Record<string, string>>(`/admin/users/${this.uid()}/pii`, { reason }),
      { message: 'O acesso será registrado na auditoria com seu usuário.' });
    if (r) this.pii.set(r);
  }
}
