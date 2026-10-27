import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiError } from '../core/errors';
import { AuthLayoutComponent } from './auth-layout.component';

@Component({
  selector: 'ld-forgot-page',
  imports: [ReactiveFormsModule, RouterLink, AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout [tag]="tag() ?? ''">
      <h1>Recuperar senha</h1>
      @if (sent()) {
        <p class="alert ok" role="status">Se houver uma conta com este e-mail, enviaremos um link para redefinir a senha.
          Verifique também a caixa de spam.</p>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()" class="stack" novalidate>
          <div class="field">
            <label for="email">E-mail da conta</label>
            <input id="email" class="input" type="email" formControlName="email" autocomplete="email" />
          </div>
          @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
          <button class="btn primary block lg" [disabled]="busy() || form.invalid">Enviar link</button>
        </form>
      }
      <a routerLink="/entrar" class="small">Voltar ao login</a>
    </ld-auth-layout>
  `,
})
export class ForgotPage {
  private readonly auth = inject(AuthService);
  readonly tag = input<string | undefined>();
  protected readonly form = inject(FormBuilder).nonNullable.group({ email: ['', [Validators.required, Validators.email]] });
  protected readonly busy = signal(false);
  protected readonly sent = signal(false);
  protected readonly error = signal('');

  protected async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.requestPasswordReset(this.form.value.email!);
      this.sent.set(true);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
