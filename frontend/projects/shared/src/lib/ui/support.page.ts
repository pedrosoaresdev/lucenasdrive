import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { ApiError } from '../core/errors';
import { DateTimePipe } from '../core/format';
import { SupportTicket, TicketMessage } from '../core/models';
import { ToastService } from '../core/toast.service';
import { AuthService } from '../core/auth.service';
import { FirebaseService } from '../core/firebase.service';
import { ref, uploadBytes } from 'firebase/storage';

const ATTACHMENT_TYPES: Record<string, string> = {
  'application/pdf': 'pdf', 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp',
};
const MAX_ATTACHMENTS = 3;
const MAX_BYTES = 10 * 1024 * 1024;
import { StateViewComponent } from './state-view.component';

export const TICKET_CATEGORIES: Record<string, string> = {
  RIDE_ISSUE: 'Problema na corrida', PAYMENT: 'Pagamento ou cobrança', SAFETY: 'Segurança / incidente',
  INAPPROPRIATE_BEHAVIOR: 'Comportamento inadequado', LOST_ITEM: 'Objeto esquecido', ACCOUNT: 'Minha conta',
  RATING_DISPUTE: 'Contestação de avaliação', OTHER: 'Outro assunto',
};
export const TICKET_STATUS: Record<string, string> = {
  OPEN: 'Aberto', IN_PROGRESS: 'Em atendimento', WAITING_USER: 'Aguardando você', ESCALATED: 'Escalonado',
  RESOLVED: 'Resolvido', CLOSED: 'Encerrado',
};

/** Central de ajuda: abrir chamados/denúncias, acompanhar status e conversar com a equipe. */
@Component({
  selector: 'ld-support-page',
  imports: [FormsModule, DateTimePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Ajuda e suporte</h1>
      <div class="alert info small">Em situação de emergência, ligue 190 (Polícia) ou 192 (SAMU). O suporte do
        Lucena Drive não substitui serviços de emergência.</div>

      @if (selected(); as t) {
        <section class="card stack">
          <button class="btn link" (click)="selected.set(null)">← Voltar</button>
          <div class="row between"><h2>{{ t.subject }}</h2><span class="badge info">{{ status[t.status] }}</span></div>
          <p class="small muted">{{ categories[t.category] }} · aberto em {{ t.createdAt | dt }}</p>
          <p class="pre">{{ t.description }}</p>
          @if (t.attachmentPaths?.length) { <p class="small muted">📎 {{ t.attachmentPaths!.length }} anexo(s) enviado(s)</p> }
          @for (m of messages(); track m.id) {
            <div class="msg" [class.staff]="m.fromStaff"><strong class="small">{{ m.fromStaff ? 'Equipe Lucena Drive' : 'Você' }}</strong>
              <p class="pre">{{ m.text }}</p><span class="small muted">{{ m.createdAt | dt }}</span></div>
          }
          @if (t.status !== 'CLOSED') {
            <textarea class="input" [(ngModel)]="reply" placeholder="Escreva uma mensagem" aria-label="Resposta" maxlength="2000"></textarea>
            <button class="btn primary" (click)="sendReply(t)" [disabled]="!reply.trim() || busy()">Enviar</button>
          }
        </section>
      } @else {
        <section class="card stack">
          <h2>Abrir chamado</h2>
          <div class="field"><label for="cat">Assunto</label>
            <select id="cat" class="input" [(ngModel)]="form.category">
              @for (c of categoryKeys; track c) { <option [value]="c">{{ categories[c] }}</option> }
            </select></div>
          <div class="field"><label for="sub">Título</label>
            <input id="sub" class="input" [(ngModel)]="form.subject" maxlength="120" /></div>
          <div class="field"><label for="desc">Descreva o que aconteceu</label>
            <textarea id="desc" class="input" [(ngModel)]="form.description" maxlength="4000"></textarea></div>
          <div class="field">
            <label for="att">Anexos (opcional) — PDF ou imagem, até {{ maxAttachments }} arquivos de 10 MB</label>
            <input id="att" type="file" multiple accept="application/pdf,image/jpeg,image/png,image/webp"
                   (change)="pickFiles($event)" [disabled]="busy()" />
            @for (f of files(); track $index) {
              <span class="row between small"><span>📎 {{ f.name }} ({{ (f.size / 1024 / 1024).toFixed(1) }} MB)</span>
                <button type="button" class="btn link sm" (click)="removeFile($index)" [attr.aria-label]="'Remover ' + f.name">Remover</button></span>
            }
            <span class="hint">Anexos são vistos apenas pela equipe de suporte, com registro de acesso.</span>
          </div>
          @if (form.rideId) { <p class="small muted">Corrida relacionada: <span class="mono">{{ form.rideId }}</span></p> }
          @if (error()) { <p class="alert err">{{ error() }}</p> }
          <button class="btn primary" (click)="open()" [disabled]="busy() || !form.subject.trim() || !form.description.trim()">
            Enviar chamado</button>
        </section>
        <section class="stack">
          <h2>Meus chamados</h2>
          @if (loading()) { <ld-state state="loading" /> }
          @for (t of tickets(); track t.id) {
            <button class="card flat ticket" (click)="openTicket(t)">
              <span class="row between"><strong>{{ t.subject }}</strong><span class="badge info">{{ status[t.status] }}</span></span>
              <span class="small muted">{{ categories[t.category] }} · {{ t.createdAt | dt }}</span>
            </button>
          } @empty { @if (!loading()) { <ld-state state="empty" title="Nenhum chamado aberto" /> } }
        </section>
      }
    </div>
  `,
  styles: `
    .page { max-width: 760px; padding-top: 24px; padding-bottom: 24px; }
    .ticket { text-align: left; cursor: pointer; display: flex; flex-direction: column; gap: 4px; font: inherit; color: inherit; width: 100%; }
    .msg { padding: 10px 12px; border-radius: 12px; background: var(--ld-surface-2); }
    .msg.staff { background: var(--ld-primary-soft); }
    .pre { white-space: pre-wrap; margin: 4px 0; }
  `,
})
export class SupportPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly fb = inject(FirebaseService);
  protected readonly maxAttachments = MAX_ATTACHMENTS;
  protected readonly files = signal<File[]>([]);
  readonly corrida = input<string | undefined>(undefined);
  protected readonly categories = TICKET_CATEGORIES;
  protected readonly categoryKeys = Object.keys(TICKET_CATEGORIES).filter((c) => c !== 'RATING_DISPUTE');
  protected readonly status = TICKET_STATUS;
  protected readonly tickets = signal<SupportTicket[]>([]);
  protected readonly selected = signal<SupportTicket | null>(null);
  protected readonly messages = signal<TicketMessage[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected form = { category: 'RIDE_ISSUE', subject: '', description: '', rideId: undefined as string | undefined };
  protected reply = '';

  async ngOnInit(): Promise<void> {
    this.form.rideId = this.corrida();
    await this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.tickets.set(await this.api.get<SupportTicket[]>('/support/tickets'));
    } finally {
      this.loading.set(false);
    }
  }

  protected pickFiles(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const picked = Array.from(input.files ?? []);
    input.value = '';
    for (const f of picked) {
      if (!ATTACHMENT_TYPES[f.type]) { this.toast.error(`${f.name}: envie PDF ou imagem (JPG, PNG, WEBP).`); continue; }
      if (f.size > MAX_BYTES) { this.toast.error(`${f.name}: o limite é 10 MB.`); continue; }
      if (this.files().length >= MAX_ATTACHMENTS) { this.toast.error(`Máximo de ${MAX_ATTACHMENTS} anexos.`); break; }
      this.files.update((list) => [...list, f]);
    }
  }

  protected removeFile(i: number): void {
    this.files.update((list) => list.filter((_, idx) => idx !== i));
  }

  /** Envia os arquivos ao Storage (regras: só o dono cria, ninguém lê pelo cliente). */
  private async uploadAttachments(): Promise<string[]> {
    const uid = this.auth.me()?.uid;
    if (!uid || !this.files().length) return [];
    const paths: string[] = [];
    for (const f of this.files()) {
      const path = `supportAttachments/${uid}/${Date.now()}-${crypto.randomUUID().slice(0, 8)}.${ATTACHMENT_TYPES[f.type]}`;
      await uploadBytes(ref(this.fb.storage, path), f, { contentType: f.type });
      paths.push(path);
    }
    return paths;
  }

  protected async open(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      const attachmentPaths = await this.uploadAttachments();
      await this.api.post('/support/tickets', { ...this.form, rideId: this.form.rideId ?? null, attachmentPaths });
      this.toast.ok('Chamado aberto. Responderemos pelo app.');
      this.files.set([]);
      this.form = { category: 'RIDE_ISSUE', subject: '', description: '', rideId: undefined };
      await this.load();
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async openTicket(t: SupportTicket): Promise<void> {
    this.selected.set(t);
    this.messages.set(await this.api.get<TicketMessage[]>(`/support/tickets/${t.id}/messages`));
  }

  protected async sendReply(t: SupportTicket): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post(`/support/tickets/${t.id}/messages`, { text: this.reply });
      this.reply = '';
      await this.openTicket(t);
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
