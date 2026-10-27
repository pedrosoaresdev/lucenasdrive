import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { JsonPipe } from '@angular/common';
import { ApiService, DateTimePipe, Page } from '@lucena/shared';

interface Log { id: string; actorId: string; actorRoles: string[]; action: string; targetType: string; targetId: string;
  before: unknown; after: unknown; reason: string | null; ip: string | null; at: string; }
interface SecEvent { id: string; uid: string; type: string; ip: string | null; userAgent: string | null; at: string; }

/** Trilha de auditoria das operações privilegiadas e eventos de segurança. */
@Component({
  selector: 'admin-audit',
  imports: [FormsModule, JsonPipe, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Auditoria</h1>
    <div class="row filters">
      <input class="input" [(ngModel)]="f.targetType" placeholder="Tipo do alvo (ex.: fareConfig)" aria-label="Tipo" />
      <input class="input" [(ngModel)]="f.targetId" placeholder="ID do alvo" aria-label="ID do alvo" />
      <input class="input" [(ngModel)]="f.actorId" placeholder="ID do autor" aria-label="Autor" />
      <button class="btn primary" (click)="reload()">Filtrar</button>
    </div>
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Data</th><th>Ação</th><th>Alvo</th><th>Autor</th><th>Motivo</th><th>IP</th><th>Antes → depois</th></tr></thead>
      <tbody>@for (l of logs(); track l.id) {
        <tr><td>{{ l.at | dt }}</td><td><strong>{{ l.action }}</strong></td><td class="mono small">{{ l.targetType }}/{{ l.targetId }}</td>
          <td class="mono small">{{ l.actorId }}<br />{{ l.actorRoles.join(',') }}</td><td class="wrap">{{ l.reason ?? '—' }}</td><td class="mono small">{{ l.ip ?? '—' }}</td>
          <td><details><summary class="small">ver</summary><pre class="small">{{ l.before | json }}
→ {{ l.after | json }}</pre></details></td></tr>
      } @empty { <tr><td colspan="7" class="muted">Sem registros.</td></tr> }</tbody>
    </table></div>
    @if (cursor()) { <button class="btn ghost more" (click)="more()">Carregar mais</button> }

    <h2 class="sec">Eventos de segurança</h2>
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Data</th><th>Evento</th><th>Usuário</th><th>IP</th><th>Dispositivo</th></tr></thead>
      <tbody>@for (e of events(); track e.id) {
        <tr><td>{{ e.at | dt }}</td><td>{{ e.type }}</td><td class="mono small">{{ e.uid }}</td><td class="mono small">{{ e.ip ?? '—' }}</td><td class="small trunc">{{ e.userAgent ?? '—' }}</td></tr>
      }</tbody>
    </table></div>
  `,
  styles: `.filters { margin: 12px 0 16px; } .filters .input { max-width: 240px; } .wrap { white-space: normal; max-width: 260px; }
    pre { white-space: pre-wrap; max-width: 420px; } .more { margin-top: 12px; } .sec { margin-top: 28px; } .trunc { max-width: 280px; overflow: hidden; text-overflow: ellipsis; }`,
})
export default class AuditPage implements OnInit {
  private readonly api = inject(ApiService);
  readonly targetId = input<string>();
  protected f = { targetType: '', targetId: '', actorId: '' };
  protected readonly logs = signal<Log[]>([]);
  protected readonly events = signal<SecEvent[]>([]);
  protected readonly cursor = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    this.f.targetId = this.targetId() ?? '';
    await this.reload();
    this.events.set(await this.api.get<SecEvent[]>('/admin/security-events', { limit: 100 }).catch(() => []));
  }

  protected async reload(): Promise<void> {
    this.logs.set([]);
    this.cursor.set(null);
    await this.more();
  }

  protected async more(): Promise<void> {
    const p = await this.api.get<Page<Log>>('/admin/audit', { ...this.f, cursor: this.cursor(), limit: 50 });
    this.logs.update((l) => [...l, ...p.items]);
    this.cursor.set(p.nextCursor);
  }
}
