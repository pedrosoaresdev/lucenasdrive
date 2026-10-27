import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiError, ApiService, DateTimePipe, StateViewComponent } from '@lucena/shared';

interface UserRow { id: string; fullName: string; email: string; phoneNumber: string; roles: string[]; status: string; createdAt: string; cpfMasked: string; }

@Component({
  selector: 'admin-users',
  imports: [FormsModule, DateTimePipe, StateViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Passageiros</h1>
    <div class="row filters">
      <select class="input" [(ngModel)]="status" (change)="load()" aria-label="Status">
        <option value="">Todos os status</option><option value="ACTIVE">Ativos</option><option value="SUSPENDED">Suspensos</option>
        <option value="PENDING_DELETION">Exclusão solicitada</option></select>
      <input class="input" [(ngModel)]="email" placeholder="Buscar por e-mail exato" (keydown.enter)="load()" aria-label="E-mail" />
      <button class="btn primary" (click)="load()">Filtrar</button>
    </div>
    @if (loading()) { <ld-state state="loading" /> }
    @else if (error()) { <ld-state state="error" [message]="error()" (retry)="load()" /> }
    @else {
      <div class="table-wrap"><table class="table">
        <thead><tr><th>Nome</th><th>E-mail</th><th>CPF</th><th>Papéis</th><th>Status</th><th>Cadastro</th></tr></thead>
        <tbody>@for (u of rows(); track u.id) {
          <tr class="clickable" tabindex="0" (click)="open(u)" (keydown.enter)="open(u)">
            <td>{{ u.fullName }}</td><td>{{ u.email }}</td><td class="mono">{{ u.cpfMasked }}</td><td>{{ u.roles.join(', ') }}</td>
            <td><span class="badge" [class.ok]="u.status === 'ACTIVE'" [class.err]="u.status !== 'ACTIVE'">{{ u.status }}</span></td>
            <td>{{ u.createdAt | dt }}</td></tr>
        } @empty { <tr><td colspan="6" class="muted">Nenhum registro.</td></tr> }</tbody>
      </table></div>
    }
  `,
  styles: `.filters { margin: 12px 0 16px; } .filters .input { max-width: 280px; }`,
})
export default class UsersPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  protected readonly rows = signal<UserRow[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal('');
  protected status = '';
  protected email = '';

  ngOnInit(): void {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.rows.set(await this.api.get<UserRow[]>('/admin/users', { role: 'PASSENGER', status: this.status, email: this.email, limit: 100 }));
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.loading.set(false);
    }
  }

  protected open(u: UserRow): void {
    void this.router.navigate(['/passageiros', u.id]);
  }
}
