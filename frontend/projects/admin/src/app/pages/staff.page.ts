import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, AuthService } from '@lucena/shared';
import { adminActions } from '../core/admin-actions';

interface Staff { id: string; fullName: string | null; email: string; roles: string[]; status: string; }

/**
 * Equipe e permissões. Regras aplicadas no servidor: ninguém altera os próprios papéis; OWNER só por
 * provisionamento seguro; ADMIN só gerencia SUPPORT; FINANCE/ADMIN só pelo proprietário.
 */
@Component({
  selector: 'admin-staff',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Equipe e permissões</h1>
    <section class="card stack grant">
      <h2>Conceder ou revogar papel</h2>
      <p class="small muted">A pessoa precisa ter criado uma conta (e-mail e senha). No primeiro acesso ao painel ela cadastrará o segundo fator.</p>
      <div class="row">
        <input class="input grow" type="email" [(ngModel)]="email" placeholder="E-mail da conta" aria-label="E-mail" />
        <select class="input sel" [(ngModel)]="role" aria-label="Papel">
          <option value="SUPPORT">SUPPORT</option>
          @if (isOwner()) { <option value="FINANCE">FINANCE</option><option value="ADMIN">ADMIN</option> }
        </select>
        <button class="btn primary" (click)="change(true)" [disabled]="!email">Conceder</button>
        <button class="btn ghost" (click)="change(false)" [disabled]="!email">Revogar</button>
      </div>
    </section>
    <div class="table-wrap"><table class="table">
      <thead><tr><th>Nome</th><th>E-mail</th><th>Papéis</th><th>Status</th></tr></thead>
      <tbody>@for (s of staff(); track s.id) {
        <tr><td>{{ s.fullName ?? '—' }}</td><td>{{ s.email }}</td><td>{{ s.roles.join(', ') }}</td><td>{{ s.status }}</td></tr>
      }</tbody>
    </table></div>
  `,
  styles: `.grant { margin: 12px 0 16px; } .grow { flex: 1; min-width: 220px; } .sel { width: 140px; }`,
})
export default class StaffPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly a = adminActions();
  protected readonly staff = signal<Staff[]>([]);
  protected email = '';
  protected role = 'SUPPORT';
  protected readonly isOwner = () => this.auth.hasRole('OWNER');

  async ngOnInit(): Promise<void> {
    this.staff.set(await this.api.get<Staff[]>('/admin/staff'));
  }

  protected async change(grant: boolean): Promise<void> {
    const ok = await this.a.withReason(grant ? `Conceder ${this.role}` : `Revogar ${this.role}`,
      (reason) => this.api.post('/admin/staff/roles', { email: this.email, role: this.role, grant, reason }),
      { ok: 'Permissões atualizadas.', danger: !grant });
    if (ok) {
      this.email = '';
      await this.ngOnInit();
    }
  }
}
