import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { AuthLayoutComponent, AuthService } from '@lucena/shared';
import { Router } from '@angular/router';

@Component({
  selector: 'admin-no-access',
  imports: [AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout tag="Admin">
      <h1>Sem acesso</h1>
      <p>Esta conta não possui permissão administrativa. O acesso é concedido apenas pelo proprietário ou
        administradores autorizados.</p>
      <button class="btn primary" (click)="logout()">Sair</button>
    </ld-auth-layout>
  `,
})
export default class NoAccessPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/entrar');
  }
}
