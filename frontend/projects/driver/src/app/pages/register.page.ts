import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  ApiError, ApiService, AuthLayoutComponent, AuthService, PublicConfig, ToastService, cpfValidator, formatCpf,
  matchFieldsValidator, minAgeValidator, normalizePhone, phoneValidator, strongPasswordValidator,
} from '@lucena/shared';

/** Cadastro do motorista: conta + dados pessoais + CNH + cidade de atuação. Documentos na etapa seguinte. */
@Component({
  selector: 'app-driver-register',
  imports: [ReactiveFormsModule, RouterLink, AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout tag="Motorista">
      <div>
        <h1>Dirija com o Lucena Drive</h1>
        <p class="muted">Etapa 1 de 2 · Dados pessoais e CNH. Depois você envia documentos e o veículo.</p>
      </div>
      <form [formGroup]="form" (ngSubmit)="submit()" class="stack" novalidate>
        <div class="field"><label for="n">Nome completo</label><input id="n" class="input" formControlName="fullName" autocomplete="name" /></div>
        <div class="field"><label for="cpf">CPF</label>
          <input id="cpf" class="input" formControlName="cpf" inputmode="numeric" maxlength="14" (input)="maskCpf()" />
          @if (bad('cpf')) { <span class="error">CPF inválido.</span> }</div>
        <div class="grid">
          <div class="field"><label for="b">Nascimento</label><input id="b" class="input" type="date" formControlName="birthDate" />
            @if (form.controls.birthDate.hasError('minAge')) { <span class="error">Idade mínima: 21 anos.</span> }</div>
          <div class="field"><label for="p">Celular</label><input id="p" class="input" type="tel" formControlName="phone" />
            @if (bad('phone')) { <span class="error">Telefone inválido.</span> }</div>
        </div>
        <fieldset class="stack box">
          <legend>CNH</legend>
          <div class="grid">
            <div class="field"><label for="cnh">Número (11 dígitos)</label><input id="cnh" class="input" formControlName="cnhNumber" inputmode="numeric" maxlength="11" /></div>
            <div class="field"><label for="cat">Categoria</label>
              <select id="cat" class="input" formControlName="cnhCategory">
                @for (c of ['B', 'AB', 'C', 'AC', 'D', 'AD', 'E', 'AE']; track c) { <option [value]="c">{{ c }}</option> }
              </select></div>
            <div class="field"><label for="exp">Validade</label><input id="exp" class="input" type="date" formControlName="cnhExpiresOn" /></div>
          </div>
          <label class="check"><input type="checkbox" formControlName="cnhEar" />
            <span>Minha CNH possui a observação EAR (exerce atividade remunerada).</span></label>
        </fieldset>
        <div class="field"><label for="city">Cidade de atuação</label>
          <select id="city" class="input" formControlName="cityId">
            <option value="" disabled>Selecione</option>
            @for (c of config()?.cities ?? []; track c.id) { <option [value]="c.id">{{ c.name }} - {{ c.state }}</option> }
          </select>
          @if (config() && !config()!.cities.length) { <span class="hint">Nenhuma cidade atendida cadastrada ainda.</span> }
        </div>
        @if (!auth.isLoggedIn()) {
          <div class="field"><label for="e">E-mail</label><input id="e" class="input" type="email" formControlName="email" autocomplete="email" /></div>
          <div class="field"><label for="pw">Senha</label><input id="pw" class="input" type="password" formControlName="password" autocomplete="new-password" />
            <span class="hint">Mínimo de 10 caracteres, com letras e números.</span></div>
          <div class="field"><label for="pw2">Confirmar senha</label><input id="pw2" class="input" type="password" formControlName="confirm" autocomplete="new-password" />
            @if (form.hasError('mismatch') && form.controls.confirm.touched) { <span class="error">As senhas não coincidem.</span> }</div>
        } @else {
          <p class="small muted">Usando a conta {{ auth.firebaseUser()?.email }}.</p>
        }
        <label class="check"><input type="checkbox" formControlName="acceptTerms" /><span>Aceito os <a routerLink="/termos" target="_blank">Termos de uso</a>.</span></label>
        <label class="check"><input type="checkbox" formControlName="acceptPrivacy" /><span>Declaro ciência da <a routerLink="/privacidade" target="_blank">Política de privacidade</a>.</span></label>
        @if (error()) { <p class="alert err" role="alert">{{ error() }}</p> }
        <button class="btn primary lg block" [disabled]="busy()">{{ busy() ? 'Enviando…' : 'Continuar' }}</button>
      </form>
      @if (!auth.isLoggedIn()) { <p class="small">Já tem cadastro? <a routerLink="/entrar">Entrar</a></p> }
    </ld-auth-layout>
  `,
  styles: `.box { border: 1px solid var(--ld-border); border-radius: 12px; padding: 12px 14px; } legend { font-weight: 700; padding: 0 6px; }`,
})
export class RegisterPage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  protected readonly config = signal<PublicConfig | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly form = inject(FormBuilder).nonNullable.group({
    fullName: ['', [Validators.required, Validators.pattern(/\S+\s+\S+/)]],
    cpf: ['', [Validators.required, cpfValidator]],
    birthDate: ['', [Validators.required, minAgeValidator(21)]],
    phone: ['', [Validators.required, phoneValidator]],
    cnhNumber: ['', [Validators.required, Validators.pattern(/^\d{11}$/)]],
    cnhCategory: ['B', Validators.required],
    cnhExpiresOn: ['', Validators.required],
    cnhEar: [false, Validators.requiredTrue],
    cityId: ['', Validators.required],
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required, strongPasswordValidator]],
    confirm: ['', Validators.required],
    acceptTerms: [false, Validators.requiredTrue],
    acceptPrivacy: [false, Validators.requiredTrue],
  }, { validators: matchFieldsValidator('password', 'confirm') });

  async ngOnInit(): Promise<void> {
    await this.auth.whenReady();
    if (this.auth.hasRole('DRIVER')) {
      await this.router.navigateByUrl('/documentos');
      return;
    }
    if (this.auth.isLoggedIn()) {
      for (const c of ['email', 'password', 'confirm'] as const) this.form.controls[c].disable();
      this.form.setValidators(null);
      const me = this.auth.me();
      if (me?.fullName) this.form.patchValue({ fullName: me.fullName, phone: me.phoneNumber ?? '' });
    }
    this.config.set(await this.api.get<PublicConfig>('/public/config').catch(() => null));
  }

  protected bad(c: 'cpf' | 'phone'): boolean {
    return this.form.controls[c].touched && this.form.controls[c].invalid;
  }

  protected maskCpf(): void {
    this.form.controls.cpf.setValue(formatCpf(this.form.controls.cpf.value), { emitEvent: false });
  }

  protected async submit(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid) {
      this.error.set('Revise os campos obrigatórios.');
      return;
    }
    this.busy.set(true);
    this.error.set('');
    const v = this.form.getRawValue();
    try {
      if (!this.auth.isLoggedIn()) await this.auth.createAccount(v.email, v.password);
      await this.api.post('/drivers/register', {
        fullName: v.fullName.trim(), cpf: v.cpf, birthDate: v.birthDate, phoneNumber: normalizePhone(v.phone),
        acceptTerms: true, acceptPrivacy: true, termsVersion: this.config()?.termsVersion ?? '2026-10',
        privacyVersion: this.config()?.privacyVersion ?? '2026-10', cnhNumber: v.cnhNumber, cnhCategory: v.cnhCategory,
        cnhExpiresOn: v.cnhExpiresOn, cnhEar: v.cnhEar, cityId: v.cityId,
      });
      await this.auth.refreshMe();
      this.toast.ok('Cadastro iniciado! Agora envie documentos e o veículo.');
      await this.router.navigateByUrl('/documentos');
    } catch (e) {
      this.error.set(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
