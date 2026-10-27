import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ApiError, ApiService, AuthLayoutComponent, AuthService, PublicConfig, ToastService, cpfValidator, formatCpf,
  matchFieldsValidator, minAgeValidator, normalizePhone, passwordStrength, phoneValidator, strongPasswordValidator,
} from '@lucena/shared';

/**
 * Cadastro do passageiro. A conta (e-mail/senha) é criada no Firebase Authentication; os dados do
 * perfil vão para a API, que valida CPF, idade e unicidade. No modo "completar", a conta já existe.
 */
@Component({
  selector: 'app-register',
  imports: [ReactiveFormsModule, RouterLink, AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout>
      <div>
        <h1>{{ complete() ? 'Complete seu cadastro' : 'Crie sua conta' }}</h1>
        <p class="muted">Leva menos de 2 minutos.</p>
      </div>
      <form [formGroup]="form" (ngSubmit)="submit()" class="stack" novalidate>
        <div class="field">
          <label for="name">Nome completo</label>
          <input id="name" class="input" formControlName="fullName" autocomplete="name" />
          @if (invalid('fullName')) { <span class="error">Informe seu nome completo.</span> }
        </div>
        <div class="field">
          <label for="cpf">CPF</label>
          <input id="cpf" class="input" formControlName="cpf" inputmode="numeric" placeholder="000.000.000-00"
                 (input)="maskCpf()" maxlength="14" />
          @if (invalid('cpf')) { <span class="error">CPF inválido.</span> }
          <span class="hint">Usado para segurança da plataforma. Fica criptografado e nunca é exibido completo.</span>
        </div>
        <div class="grid two">
          <div class="field">
            <label for="birth">Data de nascimento</label>
            <input id="birth" class="input" type="date" formControlName="birthDate" />
            @if (form.controls.birthDate.hasError('minAge')) { <span class="error">É preciso ter 18 anos ou mais.</span> }
          </div>
          <div class="field">
            <label for="phone">Celular</label>
            <input id="phone" class="input" type="tel" formControlName="phone" placeholder="(83) 99999-0000" autocomplete="tel" />
            @if (invalid('phone')) { <span class="error">Telefone inválido.</span> }
          </div>
        </div>
        @if (!complete()) {
          <div class="field">
            <label for="email">E-mail</label>
            <input id="email" class="input" type="email" formControlName="email" autocomplete="email" />
            @if (invalid('email')) { <span class="error">E-mail inválido.</span> }
          </div>
          <div class="field">
            <label for="pw">Senha</label>
            <input id="pw" class="input" type="password" formControlName="password" autocomplete="new-password" />
            <div class="meter" aria-hidden="true"><span [style.width.%]="strength() * 33.4" [attr.data-s]="strength()"></span></div>
            <span class="hint">Mínimo de 10 caracteres, com letras e números.</span>
          </div>
          <div class="field">
            <label for="pw2">Confirmar senha</label>
            <input id="pw2" class="input" type="password" formControlName="confirm" autocomplete="new-password" />
            @if (form.hasError('mismatch') && form.controls.confirm.touched) { <span class="error">As senhas não coincidem.</span> }
          </div>
        }
        <label class="check"><input type="checkbox" formControlName="acceptTerms" />
          <span>Li e aceito os <a routerLink="/termos" target="_blank">Termos de uso</a>.</span></label>
        <label class="check"><input type="checkbox" formControlName="acceptPrivacy" />
          <span>Declaro ciência da <a routerLink="/privacidade" target="_blank">Política de privacidade</a>.</span></label>
        @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
        <button class="btn primary lg block" type="submit" [disabled]="busy()">
          {{ busy() ? 'Enviando…' : complete() ? 'Concluir cadastro' : 'Criar conta' }}</button>
      </form>
      @if (!complete()) { <p class="small">Já tem conta? <a routerLink="/entrar">Entrar</a></p> }
    </ld-auth-layout>
  `,
  styles: `
    .two { grid-template-columns: 1fr 1fr; }
    @media (max-width: 480px) { .two { grid-template-columns: 1fr; } }
    .meter { height: 6px; background: var(--ld-surface-2); border-radius: 3px; overflow: hidden; }
    .meter span { display: block; height: 100%; background: var(--ld-danger); transition: width .2s; }
    .meter span[data-s='2'] { background: var(--ld-warning); } .meter span[data-s='3'] { background: var(--ld-accent); }
  `,
})
export class RegisterPage {
  private readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly complete = input(false);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', [Validators.required, Validators.minLength(5), Validators.pattern(/\S+\s+\S+/)]],
    cpf: ['', [Validators.required, cpfValidator]],
    birthDate: ['', [Validators.required, minAgeValidator(18)]],
    phone: ['', [Validators.required, phoneValidator]],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, strongPasswordValidator]],
    confirm: ['', Validators.required],
    acceptTerms: [false, Validators.requiredTrue],
    acceptPrivacy: [false, Validators.requiredTrue],
  }, { validators: matchFieldsValidator('password', 'confirm') });

  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly strength = signal(0);

  constructor() {
    this.form.controls.password.valueChanges.subscribe((v) => this.strength.set(passwordStrength(v)));
    queueMicrotask(() => {
      if (this.complete()) {
        for (const c of ['email', 'password', 'confirm'] as const) this.form.controls[c].disable();
        this.form.setValidators(null);
      }
    });
  }

  protected invalid(name: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[name];
    return c.touched && c.invalid;
  }

  protected maskCpf(): void {
    this.form.controls.cpf.setValue(formatCpf(this.form.controls.cpf.value), { emitEvent: false });
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.error.set('Revise os campos destacados.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    const v = this.form.getRawValue();
    try {
      if (!this.complete()) {
        await this.auth.createAccount(v.email, v.password);
      }
      const cfg = await this.api.get<PublicConfig>('/public/config');
      await this.api.post('/passengers/register', {
        fullName: v.fullName.trim(), cpf: v.cpf, birthDate: v.birthDate, phoneNumber: normalizePhone(v.phone),
        acceptTerms: v.acceptTerms, acceptPrivacy: v.acceptPrivacy,
        termsVersion: cfg.termsVersion, privacyVersion: cfg.privacyVersion,
      });
      await this.auth.refreshMe();
      this.toast.ok('Cadastro realizado!');
      await this.router.navigateByUrl(this.auth.firebaseUser()?.emailVerified ? '/inicio' : '/verificar-email');
    } catch (e) {
      const err = ApiError.from(e);
      this.error.set(err.message);
      // Conta Firebase criada mas perfil recusado: o usuário corrige em "completar cadastro".
      if (!this.complete() && this.auth.isLoggedIn()) {
        await this.router.navigateByUrl('/completar-cadastro');
        this.toast.error(err.message);
      }
    } finally {
      this.busy.set(false);
    }
  }
}
