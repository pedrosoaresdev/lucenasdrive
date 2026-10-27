import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ToastService } from '../core/toast.service';
import { AuthLayoutComponent } from './auth-layout.component';

@Component({
  selector: 'ld-verify-email-page',
  imports: [AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout [tag]="tag() ?? ''">
      <h1>Confirme seu e-mail</h1>
      <p>Enviamos um link de confirmação para <strong>{{ auth.firebaseUser()?.email }}</strong>.
        Abra o link e depois toque em “Já confirmei”.</p>
      <p class="small muted">Em desenvolvimento com o Firebase Emulator, o link aparece no terminal do emulador
        e na interface em http://localhost:4000/auth.</p>
      @if (message()) { <p class="alert info" role="status">{{ message() }}</p> }
      <button class="btn primary block lg" (click)="check()" [disabled]="busy()">Já confirmei</button>
      <div class="row between">
        <button class="btn link" (click)="resend()" [disabled]="busy()">Reenviar e-mail</button>
        <button class="btn link" (click)="logout()">Sair</button>
      </div>
    </ld-auth-layout>
  `,
})
export class VerifyEmailPage {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly tag = input<string | undefined>();
  readonly after = input<string | undefined>();
  protected readonly busy = signal(false);
  protected readonly message = signal('');

  protected async check(): Promise<void> {
    this.busy.set(true);
    try {
      if (await this.auth.reloadVerification()) {
        this.toast.ok('E-mail confirmado!');
        await this.router.navigateByUrl(this.after() ?? '/inicio');
      } else {
        this.message.set('Ainda não identificamos a confirmação. Verifique o link enviado.');
      }
    } finally {
      this.busy.set(false);
    }
  }

  protected async resend(): Promise<void> {
    this.busy.set(true);
    try {
      await this.auth.resendVerification();
      this.message.set('Novo e-mail enviado.');
    } catch {
      this.message.set('Aguarde alguns minutos antes de reenviar.');
    } finally {
      this.busy.set(false);
    }
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/entrar');
  }
}
