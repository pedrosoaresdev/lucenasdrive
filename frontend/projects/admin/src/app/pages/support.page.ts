import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService, AuthService, DateTimePipe, SupportTicket, TICKET_CATEGORIES, TICKET_STATUS, TicketMessage } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

/** Atendimento: fila de chamados e incidentes, prioridade, responsável, escalonamento e respostas. */
@Component({
  selector: 'admin-support',
  imports: [FormsModule, RouterLink, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Suporte</h1>
    <div class="row filters">
      <select class="input" [(ngModel)]="status" (change)="load()" aria-label="Status"><option value="">Todos</option>
        @for (s of statusKeys; track s) { <option [value]="s">{{ statusLabel[s] }}</option> }</select>
      <select class="input" [(ngModel)]="category" (change)="load()" aria-label="Categoria"><option value="">Todas as categorias</option>
        @for (c of categoryKeys; track c) { <option [value]="c">{{ categories[c] }}</option> }</select>
    </div>
    <div class="grid layout">
      <div class="stack list">
        @for (t of tickets(); track t.id) {
          <button class="card flat item" [class.sel]="sel()?.id === t.id" (click)="open(t)">
            <span class="row between"><strong>{{ t.subject }}</strong><span class="badge" [class.err]="t.priority === 'HIGH' || t.priority === 'URGENT'">{{ t.priority }}</span></span>
            <span class="small muted">{{ categories[t.category] }} · {{ statusLabel[t.status] }} · {{ t.createdAt | dt }}</span>
          </button>
        } @empty { <p class="muted small">Nenhum chamado.</p> }
      </div>
      @if (sel(); as t) {
        <section class="card stack">
          <div class="row between"><h2>{{ t.subject }}</h2><span class="badge info">{{ statusLabel[t.status] }}</span></div>
          <p class="small muted">{{ categories[t.category] }} · aberto por <a [routerLink]="[t.userRole === 'DRIVER' ? '/motoristas' : '/passageiros', t.userId]">{{ t.userRole }}</a>
            @if (t.rideId) { · <a [routerLink]="['/corridas', t.rideId]">corrida</a> }</p>
          <p class="pre">{{ t.description }}</p>
          @if (t.attachmentPaths?.length) {
            <div class="row">
              @for (p of t.attachmentPaths!; track $index) {
                <button class="btn ghost sm" (click)="viewAttachment(t, $index)">📎 Ver anexo {{ $index + 1 }}</button>
              }
            </div>
            <p class="small muted">A visualização de anexos é registrada na auditoria.</p>
          }
          @for (m of messages(); track m.id) { <div class="msg" [class.staff]="m.fromStaff"><strong class="small">{{ m.fromStaff ? 'Equipe' : 'Usuário' }}</strong>
            <p class="pre">{{ m.text }}</p><span class="small muted">{{ m.createdAt | dt }}</span></div> }
          <textarea class="input" [(ngModel)]="reply" placeholder="Responder ao usuário" aria-label="Resposta"></textarea>
          <button class="btn primary" (click)="send(t)" [disabled]="!reply.trim()">Enviar resposta</button>
          <div class="row">
            <select class="input sm-in" [(ngModel)]="upd.status" aria-label="Novo status">@for (s of statusKeys; track s) { <option [value]="s">{{ statusLabel[s] }}</option> }</select>
            <select class="input sm-in" [(ngModel)]="upd.priority" aria-label="Prioridade">@for (p of ['LOW','NORMAL','HIGH','URGENT']; track p) { <option [value]="p">{{ p }}</option> }</select>
            <button class="btn ghost" (click)="update(t)">Atualizar</button>
            <button class="btn ghost" (click)="assignMe(t)">Assumir</button>
          </div>
          <details><summary class="small">Histórico</summary>
            @for (h of t.history; track $index) { <p class="small">{{ h.at | dt }} · {{ statusLabel[h.status] }} · {{ h.byUserId }} {{ h.note ?? '' }}</p> }</details>
        </section>
      }
    </div>
  `,
  styles: `.filters { margin: 12px 0 16px; } .filters .input { max-width: 240px; } .layout { grid-template-columns: 360px 1fr; align-items: start; }
    .item { text-align: left; cursor: pointer; font: inherit; color: inherit; display: flex; flex-direction: column; gap: 4px; width: 100%; }
    .item.sel { border-color: var(--ld-primary); } .list { max-height: 75vh; overflow: auto; }
    .msg { padding: 10px; border-radius: 10px; background: var(--ld-surface-2); } .msg.staff { background: var(--ld-primary-soft); }
    .pre { white-space: pre-wrap; margin: 4px 0; } .sm-in { width: auto; }
    @media (max-width: 960px) { .layout { grid-template-columns: 1fr; } }`,
})
export default class SupportAdminPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly a = adminActions();
  private readonly auth = inject(AuthService);
  protected readonly categories = TICKET_CATEGORIES;
  protected readonly categoryKeys = Object.keys(TICKET_CATEGORIES);
  protected readonly statusLabel = TICKET_STATUS;
  protected readonly statusKeys = Object.keys(TICKET_STATUS);
  protected readonly tickets = signal<SupportTicket[]>([]);
  protected readonly sel = signal<SupportTicket | null>(null);
  protected readonly messages = signal<TicketMessage[]>([]);
  protected status = 'OPEN';
  protected category = '';
  protected reply = '';
  protected upd = { status: 'IN_PROGRESS', priority: 'NORMAL' };

  /** Anexo entregue pelo backend (sem URL pública); cada acesso gera registro de auditoria. */
  protected async viewAttachment(t: SupportTicket, i: number): Promise<void> {
    const blob = await this.a.exec(() => this.api.download(`/admin/support/tickets/${t.id}/attachments/${i}`));
    if (!blob) return;
    const ext = blob.type === 'application/pdf' ? 'pdf' : (blob.type.split('/')[1] ?? 'bin');
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `chamado-${t.id}-anexo-${i + 1}.${ext}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  }

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  protected async load(): Promise<void> {
    this.tickets.set(await this.api.get<SupportTicket[]>('/admin/support/tickets', { status: this.status, category: this.category }));
  }

  protected async open(t: SupportTicket): Promise<void> {
    this.sel.set(await this.api.get<SupportTicket>(`/admin/support/tickets/${t.id}`));
    this.messages.set(await this.api.get<TicketMessage[]>(`/admin/support/tickets/${t.id}/messages`));
    this.upd = { status: t.status, priority: t.priority };
  }

  protected async send(t: SupportTicket): Promise<void> {
    if (await this.a.exec(() => this.api.post(`/admin/support/tickets/${t.id}/messages`, { text: this.reply }), 'Resposta enviada.')) {
      this.reply = '';
      await this.open(t);
    }
  }

  protected async update(t: SupportTicket): Promise<void> {
    if (await this.a.exec(() => this.api.post(`/admin/support/tickets/${t.id}`, { status: this.upd.status, priority: this.upd.priority }), 'Chamado atualizado.')) {
      await this.open(t);
      await this.load();
    }
  }

  protected async assignMe(t: SupportTicket): Promise<void> {
    const uid = this.auth.me()?.uid;
    if (await this.a.exec(() => this.api.post(`/admin/support/tickets/${t.id}`, { assigneeId: uid, status: 'IN_PROGRESS', note: 'Assumido' }), 'Chamado assumido.')) {
      await this.open(t);
    }
  }
}
