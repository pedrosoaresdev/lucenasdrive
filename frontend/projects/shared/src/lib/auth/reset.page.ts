import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiError } from '../core/errors';
import { matchFieldsValidator, strongPasswordValidator } from '../core/validators';
import { AuthLayoutComponent } from './auth-layout.component';

/** Redefinição de senha pelo link do Firebase (?mode=resetPassword&oobCode=...). */
@Component({
  selector: 'ld-reset-page',
  imports: [ReactiveFormsModule, RouterLink, AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout [tag]="tag() ?? ''">
      <h1>Nova senha</h1>
      @if (!oobCode()) {
        <p class="alert err">Link inválido. Solicite uma nova recuperação de senha.</p>
      } @else if (done()) {
        <p class="alert ok">Senha alterada. Você já pode entrar com a nova senha.</p>
        <a class="btn primary block" routerLink="/entrar">Ir para o login</a>
      } @else {
        <form [formGroup]="form" (ngSubmit)="submit()" class="stack" novalidate>
          <div class="field">
            <label for="pw">Nova senha</label>
            <input id="pw" class="input" type="password" formControlName="password" autocomplete="new-password" />
            <span class="hint">Mínimo de 10 caracteres, com letras e números.</span>
          </div>
          <div class="field">
            <label for="pw2">Confirme a nova senha</label>
            <input id="pw2" class="input" type="password" formControlName="confirm" autocomplete="new-password" />
            @if (form.hasError('mismatch') && form.controls.confirm.touched) { <span class="error">As senhas não coincidem.</span> }
          </div>
          @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
          <button class="btn primary block lg" [disabled]="busy() || form.invalid">Salvar nova senha</button>
        </form>
      }
    </ld-auth-layout>
  `,
})
export class ResetPage {
  private readonly auth = inject(AuthService);
  readonly tag = input<string | undefined>();
  readonly oobCode = input<string | undefined>(undefined);
  protected readonly form = inject(FormBuilder).nonNullable.group(
    { password: ['', [Validators.required, strongPasswordValidator]], confirm: ['', Validators.required] },
    { validators: matchFieldsValidator('password', 'confirm') },
  );
  protected readonly busy = signal(false);
  protected readonly done = signal(false);
  protected readonly error = signal('');

  protected async submit(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.confirmPasswordReset(this.oobCode()!, this.form.value.password!);
      this.done.set(true);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
