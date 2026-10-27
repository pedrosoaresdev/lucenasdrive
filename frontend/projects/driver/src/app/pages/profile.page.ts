import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AccountSettingsComponent, AuthService, AvatarComponent } from '@lucena/shared';
import { DriverLocationService } from '../location.service';

@Component({
  selector: 'app-driver-profile',
  imports: [RouterLink, AvatarComponent, AccountSettingsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Perfil profissional</h1>
      @if (auth.me(); as me) {
        <section class="card row">
          <ld-avatar [path]="me.photoUrl" [name]="me.fullName ?? ''" [size]="72" />
          <div class="stack" style="gap: 2px">
            <strong>{{ me.fullName }}</strong>
            <span class="small muted">{{ me.email }} · CPF {{ me.cpfMasked }}</span>
            <a routerLink="/documentos" class="small">Documentos, CNH e veículos →</a>
          </div>
        </section>
      }
      <ld-account-settings />
      <button class="btn ghost" (click)="logout()">Sair</button>
    </div>
  `,
  styles: `.page { max-width: 720px; padding-top: 24px; padding-bottom: 24px; }`,
})
export class ProfilePage {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly loc = inject(DriverLocationService);

  protected async logout(): Promise<void> {
    this.loc.stop();
    await this.auth.logout();
    await this.router.navigateByUrl('/entrar');
  }
}
