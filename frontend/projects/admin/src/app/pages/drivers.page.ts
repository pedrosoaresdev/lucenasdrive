import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ApiError, ApiService, DateTimePipe, StateViewComponent } from '@lucena/shared';

interface Row { id: string; status: string; cityId: string; ratingAvg: number; ratingCount: number; completedRides: number;
  warnings: number; submittedAt: string | null; updatedAt: string; cnhExpiresOn: string; }

export const DRIVER_STATUS: Record<string, string> = {
  INCOMPLETE: 'Cadastro incompleto', PENDING_REVIEW: 'Pendente de análise', APPROVED: 'Aprovado', REJECTED: 'Recusado',
  SUSPENDED: 'Suspenso', DOCUMENTS_EXPIRED: 'Documentação vencida',
};

@Component({
  selector: 'admin-drivers',
  imports: [DateTimePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Motoristas</h1>
    <div class="nav-tabs tabs" role="tablist">
      @for (s of tabs; track s) {
        <a href="#" role="tab" [class.active]="status() === s" (click)="$event.preventDefault(); setStatus(s)">{{ s ? label[s] : 'Todos' }}</a>
      }
    </div>
    @if (loading()) { <ld-state state="loading" /> }
    @else if (error()) { <ld-state state="error" [message]="error()" (retry)="load()" /> }
    @else {
      <div class="table-wrap"><table class="table">
        <thead><tr><th>ID</th><th>Status</th><th>Cidade</th><th>Avaliação</th><th>Corridas</th><th>Advertências</th><th>CNH até</th><th>Enviado</th></tr></thead>
        <tbody>@for (d of rows(); track d.id) {
          <tr class="clickable" tabindex="0" (click)="open(d.id)" (keydown.enter)="open(d.id)">
            <td class="mono">{{ d.id }}</td><td><span class="badge" [class.ok]="d.status === 'APPROVED'" [class.warn]="d.status === 'PENDING_REVIEW'"
              [class.err]="d.status === 'SUSPENDED' || d.status === 'REJECTED' || d.status === 'DOCUMENTS_EXPIRED'">{{ label[d.status] }}</span></td>
            <td>{{ d.cityId }}</td><td>★ {{ d.ratingCount ? d.ratingAvg.toFixed(2) : '—' }} ({{ d.ratingCount }})</td>
            <td>{{ d.completedRides }}</td><td>{{ d.warnings }}</td><td>{{ d.cnhExpiresOn }}</td><td>{{ d.submittedAt | dt }}</td></tr>
        } @empty { <tr><td colspan="8" class="muted">Nenhum motorista neste filtro.</td></tr> }</tbody>
      </table></div>
    }
  `,
  styles: `.tabs { margin: 12px 0 16px; }`,
})
export default class DriversPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly label = DRIVER_STATUS;
  protected readonly tabs = ['PENDING_REVIEW', 'APPROVED', 'SUSPENDED', 'DOCUMENTS_EXPIRED', 'REJECTED', 'INCOMPLETE', ''];
  protected readonly status = signal('PENDING_REVIEW');
  protected readonly rows = signal<Row[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal('');

  ngOnInit(): void {
    void this.load();
  }

  protected setStatus(s: string): void {
    this.status.set(s);
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.rows.set(await this.api.get<Row[]>('/admin/drivers', { status: this.status(), limit: 100 }));
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.loading.set(false);
    }
  }

  protected open(id: string): void {
    void this.router.navigate(['/motoristas', id]);
  }
}
