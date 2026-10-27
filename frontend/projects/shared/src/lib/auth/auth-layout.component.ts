import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LogoComponent } from '../ui/logo.component';

@Component({
  selector: 'ld-auth-layout',
  imports: [LogoComponent, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="auth">
      <div class="panel card fade-in">
        <a routerLink="/" class="brand"><ld-logo [size]="36" [tag]="tag()" /></a>
        <ng-content />
      </div>
      <p class="small muted foot">Ao continuar você concorda com os <a routerLink="/termos">Termos</a> e a
        <a routerLink="/privacidade">Política de privacidade</a>.</p>
    </div>
  `,
  styles: `
    .auth { min-height: 100dvh; display: flex; flex-direction: column; align-items: center; justify-content: center;
      padding: 24px 16px; background: radial-gradient(900px 380px at 50% -10%, var(--ld-primary-soft), transparent); }
    .panel { width: 100%; max-width: 460px; display: flex; flex-direction: column; gap: 16px; padding: 28px; }
    .brand { align-self: flex-start; }
    .foot { margin-top: 16px; text-align: center; max-width: 460px; }
  `,
})
export class AuthLayoutComponent {
  readonly tag = input('');
}
