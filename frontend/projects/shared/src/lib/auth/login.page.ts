import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { ApiError } from '../core/errors';
import { AuthLayoutComponent } from './auth-layout.component';

/** Login por e-mail e senha (Firebase Authentication). Reutilizado pelos três portais. */
@Component({
  selector: 'ld-login-page',
  imports: [ReactiveFormsModule, RouterLink, AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout [tag]="tag() ?? ''">
      <div>
        <h1>{{ heading() ?? 'Bem-vindo de volta' }}</h1>
        <p class="muted">{{ subheading() ?? 'Entre para continuar.' }}</p>
      </div>
      <form [formGroup]="form" (ngSubmit)="submit()" class="stack" novalidate>
        <div class="field">
          <label for="email">E-mail</label>
          <input id="email" class="input" type="email" formControlName="email" autocomplete="username" inputmode="email" />
          @if (form.controls.email.touched && form.controls.email.invalid) { <span class="error">Informe um e-mail válido.</span> }
        </div>
        <div class="field">
          <label for="password">Senha</label>
          <div class="row" style="flex-wrap: nowrap">
            <input id="password" class="input" [type]="show() ? 'text' : 'password'" formControlName="password"
                   autocomplete="current-password" />
            <button type="button" class="btn ghost sm" (click)="show.set(!show())" [attr.aria-pressed]="show()">
              {{ show() ? 'Ocultar' : 'Mostrar' }}</button>
          </div>
        </div>
        @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
        <button class="btn primary lg block" type="submit" [disabled]="busy()">{{ busy() ? 'Entrando…' : 'Entrar' }}</button>
      </form>
      <div class="row between small">
        <a routerLink="/recuperar-senha">Esqueci minha senha</a>
        @if (registerHref(); as link) { <a [routerLink]="link">Criar conta</a> }
      </div>
    </ld-auth-layout>
  `,
})
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  // Com withComponentInputBinding, dados de rota ausentes chegam como undefined: os padrões ficam no uso.
  readonly tag = input<string | undefined>();
  readonly heading = input<string | undefined>();
  readonly subheading = input<string | undefined>();
  readonly registerLink = input<string | null | undefined>();
  readonly after = input<string | undefined>();
  readonly voltar = input<string | undefined>();
  protected readonly registerHref = computed(() => (this.registerLink() === undefined ? '/cadastro' : this.registerLink()));

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly show = signal(false);

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await this.auth.login(this.form.value.email!, this.form.value.password!);
      const back = this.voltar();
      const target = back?.startsWith('/') && !back.startsWith('//') ? back : this.after() ?? '/inicio';
      await this.router.navigateByUrl(target);
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
