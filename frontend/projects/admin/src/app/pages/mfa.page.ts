import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiError, ApiService, AuthLayoutComponent, AuthService } from '@lucena/shared';
import { AdminSessionService } from '../core/admin-session.service';

interface MfaStatus { enrolled: boolean; locked: boolean; lockedUntil: string | null; recoveryCodesRemaining: number; }

/**
 * Segundo fator administrativo (TOTP). Primeiro acesso: cadastro do aplicativo autenticador e
 * exibição única dos códigos de recuperação. Acessos seguintes: código de 6 dígitos ou código de recuperação.
 */
@Component({
  selector: 'admin-mfa',
  imports: [FormsModule, SlicePipe, AuthLayoutComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ld-auth-layout tag="Admin">
      @if (!status()) {
        <p class="muted">{{ error() || 'Verificando…' }}</p>
        @if (error()) { <button class="btn ghost" (click)="logout()">Sair</button> }
      } @else if (recoveryCodes().length) {
        <h1>Guarde seus códigos de recuperação</h1>
        <p class="small">Cada código pode ser usado uma única vez se você perder o celular. Eles não serão exibidos novamente.</p>
        <ol class="codes mono">@for (c of recoveryCodes(); track c) { <li>{{ c }}</li> }</ol>
        <label class="check"><input type="checkbox" [(ngModel)]="saved" /> <span>Guardei os códigos em local seguro.</span></label>
        <button class="btn primary block" [disabled]="!saved" (click)="recoveryCodes.set([]); status.set({ enrolled: true, locked: false, lockedUntil: null, recoveryCodesRemaining: 10 })">Continuar</button>
      } @else if (!status()!.enrolled) {
        <h1>Ative o segundo fator</h1>
        @if (!secret()) {
          <p>O acesso administrativo exige um aplicativo autenticador (Google Authenticator, Microsoft Authenticator, 1Password etc.).</p>
          <button class="btn primary block" (click)="enroll()" [disabled]="busy()">Gerar chave</button>
        } @else {
          <p class="small">No aplicativo, adicione uma conta pela chave abaixo (ou abra o link em um dispositivo com o app):</p>
          <p class="secret mono" aria-label="Chave secreta">{{ secretGrouped() }}</p>
          <a class="small" [href]="uri()">Abrir no aplicativo autenticador</a>
          <form (submit)="confirm($event)" class="stack">
            <input class="input code" [(ngModel)]="code" name="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" aria-label="Código de 6 dígitos" />
            <button class="btn primary block" [disabled]="code.length !== 6 || busy()">Confirmar</button>
          </form>
        }
      } @else {
        <h1>Verificação em duas etapas</h1>
        @if (status()!.locked) {
          <p class="alert err">Muitas tentativas. Aguarde até {{ status()!.lockedUntil | slice: 11 : 16 }} UTC.</p>
        }
        <form (submit)="verify($event)" class="stack">
          @if (!useRecovery()) {
            <label for="code">Código do aplicativo autenticador</label>
            <input id="code" class="input code" [(ngModel)]="code" name="code" inputmode="numeric" maxlength="6" autocomplete="one-time-code" />
          } @else {
            <label for="rc">Código de recuperação</label>
            <input id="rc" class="input mono" [(ngModel)]="recovery" name="recovery" maxlength="20" />
          }
          <button class="btn primary block lg" [disabled]="busy()">Verificar</button>
        </form>
        <button class="btn link" (click)="useRecovery.set(!useRecovery())">{{ useRecovery() ? 'Usar o aplicativo' : 'Perdi o celular: usar código de recuperação' }}</button>
      }
      @if (error() && status()) { <p class="alert err" role="alert">{{ error() }}</p> }
      @if (status()) { <button class="btn link small" (click)="logout()">Entrar com outra conta</button> }
    </ld-auth-layout>
  `,
  styles: `
    .code { font-size: 1.6rem; letter-spacing: .4em; text-align: center; }
    .secret { font-size: 1.05rem; padding: 12px; background: var(--ld-surface-2); border-radius: 10px; word-break: break-all; }
    .codes { columns: 2; padding-left: 20px; }
  `,
})
export default class MfaPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly session = inject(AdminSessionService);
  private readonly router = inject(Router);
  protected readonly status = signal<MfaStatus | null>(null);
  protected readonly secret = signal('');
  protected readonly uri = signal('');
  protected readonly recoveryCodes = signal<string[]>([]);
  protected readonly useRecovery = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected code = '';
  protected recovery = '';
  protected saved = false;

  protected secretGrouped(): string {
    return this.secret().replace(/(.{4})/g, '$1 ').trim();
  }

  async ngOnInit(): Promise<void> {
    await this.auth.whenReady();
    try {
      this.status.set(await this.api.get<MfaStatus>('/admin/auth/status'));
    } catch (e) {
      const err = ApiError.from(e);
      this.error.set(err.status === 403 ? 'Esta conta não possui permissão administrativa.' : err.message);
    }
  }

  protected async enroll(): Promise<void> {
    await this.do(async () => {
      const r = await this.api.post<{ secret: string; otpauthUri: string }>('/admin/auth/totp/enroll');
      this.secret.set(r.secret);
      this.uri.set(r.otpauthUri);
    });
  }

  protected async confirm(e: Event): Promise<void> {
    e.preventDefault();
    await this.do(async () => {
      const r = await this.api.post<{ recoveryCodes: string[] }>('/admin/auth/totp/confirm', { code: this.code });
      this.recoveryCodes.set(r.recoveryCodes);
      this.code = '';
    });
  }

  protected async verify(e: Event): Promise<void> {
    e.preventDefault();
    await this.do(async () => {
      const body = this.useRecovery() ? { recoveryCode: this.recovery } : { code: this.code };
      const r = await this.api.post<{ sessionToken: string }>('/admin/auth/verify', body);
      this.session.setToken(r.sessionToken);
      await this.router.navigateByUrl('/painel');
    });
  }

  private async do(fn: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await fn();
    } catch (e) {
      const err = ApiError.from(e);
      this.error.set(err.code === 'REAUTHENTICATION_REQUIRED' ? 'Por segurança, saia e entre novamente para cadastrar o autenticador.' : err.message);
      if (err.code === 'MFA_LOCKED') this.status.update((s) => (s ? { ...s, locked: true } : s));
    } finally {
      this.busy.set(false);
    }
  }

  protected async logout(): Promise<void> {
    this.session.setToken(null);
    await this.auth.logout();
    await this.router.navigateByUrl('/entrar');
  }
}
