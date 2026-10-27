import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, DateTimePipe } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Req { id: string; userId: string; type: string; status: string; details: string | null; resolutionNote: string | null; createdAt: string; }

/** Solicitações de titulares (LGPD): acesso, correção, exclusão. Prazo e decisão registrados. */
@Component({
  selector: 'admin-privacy',
  imports: [RouterLink, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Solicitações LGPD</h1>
    <p class="small muted">A exclusão deve preservar registros exigidos por lei (corridas, financeiro, fiscal) e anonimizar o restante.
      Procedimento em docs/LGPD.md.</p>
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Data</th><th>Titular</th><th>Tipo</th><th>Detalhes</th><th>Status</th><th>Ações</th></tr></thead>
      <tbody>@for (r of list(); track r.id) {
        <tr><td>{{ r.createdAt | dt }}</td><td><a [routerLink]="['/passageiros', r.userId]" class="mono small">{{ r.userId }}</a></td>
          <td>{{ typeLabel[r.type] ?? r.type }}</td><td class="wrap">{{ r.details ?? '—' }}</td>
          <td class="wrap"><span class="badge">{{ statusLabel[r.status] ?? r.status }}</span> {{ r.resolutionNote ?? '' }}</td>
          <td class="row">
            @if (r.status === 'OPEN') { <button class="btn ghost sm" (click)="set(r, 'IN_PROGRESS')">Iniciar</button> }
            @if (r.status !== 'COMPLETED' && r.status !== 'REJECTED') {
              @if (r.type === 'ACCOUNT_DELETION') {
                <button class="btn danger sm" (click)="anonymize(r)">Anonimizar conta</button>
              } @else {
                <button class="btn primary sm" (click)="set(r, 'COMPLETED')">Concluir</button>
              }
              <button class="btn ghost sm" (click)="set(r, 'REJECTED')">Indeferir</button> }</td></tr>
      } @empty { <tr><td colspan="6" class="muted">Nenhuma solicitação.</td></tr> }</tbody>
    </table></div>
  `,
  styles: `.wrap { white-space: normal; max-width: 300px; }`,
})
export default class PrivacyPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  protected readonly list = signal<Req[]>([]);
  protected readonly typeLabel: Record<string, string> = {
    DATA_EXPORT: 'Cópia dos dados', ACCOUNT_DELETION: 'Exclusão de conta', CORRECTION: 'Correção', CONSENT_REVOCATION: 'Revogação',
  };
  protected readonly statusLabel: Record<string, string> = {
    OPEN: 'Aberta', IN_PROGRESS: 'Em andamento', COMPLETED: 'Concluída', REJECTED: 'Indeferida',
  };

  async ngOnInit(): Promise<void> {
    this.list.set(await this.api.get<Req[]>('/admin/privacy-requests'));
  }

  /** Anonimização irreversível: exige justificativa e MFA recente (step-up); tudo auditado no servidor. */
  protected async anonymize(r: Req): Promise<void> {
    const res = await this.a.withReason('Anonimizar conta do titular',
      (reason) => this.api.post<{ ridesUpdated: number; filesDeleted: number }>(`/admin/privacy-requests/${r.id}/anonymize`, { reason }),
      { danger: true, message: 'Ação irreversível: remove nome, contatos, CPF, documentos, endereços e mensagens. '
        + 'Registros financeiros são mantidos pelo prazo legal, sem identificação. Bloqueada se houver corrida ativa ou saldo a repassar.' });
    if (res) {
      this.a.toast.ok(`Conta anonimizada (${res.ridesUpdated} corridas, ${res.filesDeleted} arquivos).`);
      await this.ngOnInit();
    }
  }

  protected async set(r: Req, status: string): Promise<void> {
    const ok = await this.a.withReason(`Marcar como ${status}`, (note) => this.api.post(`/admin/privacy-requests/${r.id}`, { status, note }),
      { minLength: 5, ok: 'Solicitação atualizada.' });
    if (ok) await this.ngOnInit();
  }
}
