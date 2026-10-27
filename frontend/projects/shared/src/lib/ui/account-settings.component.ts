import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../core/api.service';
import { AuthService } from '../core/auth.service';
import { ApiError } from '../core/errors';
import { DateTimePipe } from '../core/format';
import { NotificationPreferences } from '../core/models';
import { ToastService } from '../core/toast.service';
import { PushService } from '../core/push.service';
import { strongPasswordValidator } from '../core/validators';
import { FormControl } from '@angular/forms';

interface PrivacyRequest { id: string; type: string; status: string; createdAt: string; resolutionNote: string | null; }

/** Preferências de notificação, segurança da conta (senha, sessões) e solicitações LGPD. */
@Component({
  selector: 'ld-account-settings',
  imports: [FormsModule, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card stack">
      <h2>Notificações</h2>
      @if (prefs(); as p) {
        @for (opt of options; track opt.key) {
          <label class="check"><input type="checkbox" [ngModel]="p[opt.key]" (ngModelChange)="setPref(opt.key, $event)" />
            <span>{{ opt.label }}</span></label>
        }
        <p class="small muted">Alertas de segurança e eventos da corrida em andamento são sempre enviados no app.</p>
      }
      <div class="row between push">
        <span class="small"><strong>Push neste navegador</strong><br />
          <span class="muted">{{ pushText[push.state()] }}</span></span>
        @switch (push.state()) {
          @case ('off') { <button class="btn ghost sm" (click)="push.enable()">Ativar</button> }
          @case ('on') { <button class="btn ghost sm" (click)="push.disable()">Desativar</button> }
          @case ('working') { <button class="btn ghost sm" disabled>Ativando…</button> }
          @case ('not-configured') { <span class="badge warn">Integração pendente</span> }
        }
      </div>
    </section>

    <section class="card stack">
      <h2>Segurança</h2>
      <div class="stack">
        <strong class="small">Alterar senha</strong>
        <input class="input" type="password" [(ngModel)]="pw.current" placeholder="Senha atual" autocomplete="current-password" aria-label="Senha atual" />
        <input class="input" type="password" [(ngModel)]="pw.next" placeholder="Nova senha (mín. 10, letras e números)" autocomplete="new-password" aria-label="Nova senha" />
        <button class="btn ghost" (click)="changePassword()" [disabled]="busy() || !pw.current || !pw.next">Alterar senha</button>
      </div>
      <div class="row between">
        <span class="small">Desconectar de todos os dispositivos</span>
        <button class="btn ghost sm" (click)="revokeAll()" [disabled]="busy()">Encerrar sessões</button>
      </div>
    </section>

    <section class="card stack">
      <h2>Privacidade (LGPD)</h2>
      <p class="small muted">Solicite cópia dos seus dados, correção ou exclusão da conta. Registros de corridas e
        financeiros podem ser mantidos pelo prazo exigido por lei.</p>
      <div class="row">
        <button class="btn ghost sm" (click)="exportData()" [disabled]="busy()">Baixar meus dados</button>
        <button class="btn ghost sm" (click)="privacy('CORRECTION')" [disabled]="busy()">Pedir correção</button>
        <button class="btn danger sm" (click)="confirmDelete.set(true)" [disabled]="busy()">Excluir conta</button>
      </div>
      @if (needReauth()) {
        <form class="alert info stack" (submit)="reauthAndExport($event)">
          <strong class="small">Confirme sua senha para baixar seus dados</strong>
          <input class="input" type="password" name="reauth" [(ngModel)]="reauthPassword" autocomplete="current-password"
                 aria-label="Senha atual" />
          <div class="row"><button class="btn primary sm" type="submit" [disabled]="busy() || !reauthPassword">Confirmar e baixar</button>
            <button class="btn ghost sm" type="button" (click)="needReauth.set(false)">Cancelar</button></div>
        </form>
      }
      @if (confirmDelete()) {
        <div class="alert err stack">
          <strong>Confirmar pedido de exclusão?</strong>
          <span class="small">Sua conta ficará indisponível enquanto a equipe processa o pedido.</span>
          <div class="row"><button class="btn danger sm" (click)="privacy('ACCOUNT_DELETION')">Confirmar</button>
            <button class="btn ghost sm" (click)="confirmDelete.set(false)">Cancelar</button></div>
        </div>
      }
      @for (r of requests(); track r.id) {
        <div class="row between small"><span>{{ labels[r.type] }} · {{ r.createdAt | dt }}</span><span class="badge">{{ r.status }}</span></div>
      }
    </section>
  `,
  styles: `:host { display: flex; flex-direction: column; gap: 16px; } .push { border-top: 1px solid var(--ld-border); padding-top: 12px; }`,
})
export class AccountSettingsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  protected readonly push = inject(PushService);
  protected readonly pushText: Record<string, string> = {
    'not-configured': 'Requer chave VAPID do Firebase Cloud Messaging (ver docs/INTEGRATIONS.md). Os avisos continuam no app.',
    unsupported: 'Este navegador não oferece suporte a notificações push.',
    denied: 'Bloqueado nas configurações do navegador. Libere as notificações deste site para ativar.',
    off: 'Receba avisos da corrida mesmo com o app fechado.',
    on: 'Ativado neste dispositivo.',
    working: 'Solicitando permissão…',
  };
  protected readonly prefs = signal<NotificationPreferences | null>(null);
  protected readonly requests = signal<PrivacyRequest[]>([]);
  protected readonly busy = signal(false);
  protected readonly confirmDelete = signal(false);
  protected readonly needReauth = signal(false);
  protected reauthPassword = '';
  protected pw = { current: '', next: '' };
  protected readonly labels: Record<string, string> = {
    DATA_EXPORT: 'Cópia dos dados', ACCOUNT_DELETION: 'Exclusão de conta', CORRECTION: 'Correção', CONSENT_REVOCATION: 'Revogação',
  };
  protected readonly options: { key: keyof NotificationPreferences; label: string }[] = [
    { key: 'chatMessages', label: 'Mensagens do chat' },
    { key: 'financial', label: 'Pagamentos, reembolsos e repasses' },
    { key: 'promotions', label: 'Promoções e novidades' },
    { key: 'push', label: 'Notificações push neste dispositivo' },
    { key: 'hideContentOnLockScreen', label: 'Ocultar conteúdo na tela bloqueada' },
  ];

  async ngOnInit(): Promise<void> {
    this.prefs.set(this.auth.me()?.notificationPreferences ?? null);
    this.requests.set(await this.api.get<PrivacyRequest[]>('/me/privacy-requests').catch(() => []));
  }

  protected async setPref(key: keyof NotificationPreferences, value: boolean): Promise<void> {
    const next = { ...this.prefs()!, [key]: value };
    try {
      this.prefs.set(await this.api.put<NotificationPreferences>('/me/notification-preferences', next));
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    }
  }

  protected async changePassword(): Promise<void> {
    if (strongPasswordValidator(new FormControl(this.pw.next))) {
      this.toast.error('A nova senha precisa de ao menos 10 caracteres, com letras e números.');
      return;
    }
    this.busy.set(true);
    try {
      await this.auth.changePassword(this.pw.current, this.pw.next);
      this.pw = { current: '', next: '' };
      this.toast.ok('Senha alterada.');
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async revokeAll(): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post('/me/sessions/revoke-all');
      await this.auth.logout();
      await this.router.navigateByUrl('/entrar');
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  /** Exportação imediata (LGPD art. 18, II e V). O servidor exige login recente. */
  protected async exportData(): Promise<void> {
    this.busy.set(true);
    try {
      const data = await this.api.get<unknown>('/me/data-export');
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'lucena-drive-meus-dados.json';
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      this.toast.ok('Arquivo com seus dados gerado.');
      this.requests.set(await this.api.get<PrivacyRequest[]>('/me/privacy-requests').catch(() => this.requests()));
      this.needReauth.set(false);
    } catch (e) {
      const err = ApiError.from(e);
      if (err.code === 'REAUTHENTICATION_REQUIRED') this.needReauth.set(true);
      else this.toast.error(err.message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async reauthAndExport(ev: Event): Promise<void> {
    ev.preventDefault();
    this.busy.set(true);
    try {
      await this.auth.reauthenticate(this.reauthPassword);
      this.reauthPassword = '';
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
      this.busy.set(false);
      return;
    }
    this.busy.set(false);
    await this.exportData();
  }

  protected async privacy(type: string): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.post('/me/privacy-requests', { type });
      this.confirmDelete.set(false);
      this.toast.ok('Solicitação registrada. Prazo de resposta conforme a LGPD.');
      this.requests.set(await this.api.get<PrivacyRequest[]>('/me/privacy-requests'));
      if (type === 'ACCOUNT_DELETION') {
        await this.auth.logout();
        await this.router.navigateByUrl('/');
      }
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }
}
