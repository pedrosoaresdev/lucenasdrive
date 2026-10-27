import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import {
  AccountSettingsComponent, ApiError, ApiService, AuthService, AvatarComponent, FavoritePlace, FirebaseService,
  PassengerProfile, Place, PlaceSearchComponent, ToastService, normalizePhone,
} from '@lucena/shared';
import { ref, uploadBytes } from 'firebase/storage';

/** Perfil: dados permitidos, foto, locais favoritos (Casa/Trabalho), notificações, segurança e privacidade. */
@Component({
  selector: 'app-profile',
  imports: [FormsModule, AvatarComponent, PlaceSearchComponent, AccountSettingsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container page stack">
      <h1>Meu perfil</h1>
      @if (auth.me(); as me) {
        <section class="card stack">
          <div class="row">
            <ld-avatar [path]="me.photoUrl" [name]="me.fullName ?? ''" [size]="72" />
            <label class="btn ghost sm">Trocar foto<input type="file" accept="image/jpeg,image/png,image/webp" hidden (change)="upload($event)" /></label>
          </div>
          <div class="field"><label for="n">Nome completo</label><input id="n" class="input" [(ngModel)]="name" /></div>
          <div class="field"><label for="p">Celular</label><input id="p" class="input" type="tel" [(ngModel)]="phone" /></div>
          @if (myRating(); as mr) {
            <p class="small">Sua nota como passageiro(a):
              <strong>{{ mr.average !== null ? '★ ' + mr.average.toFixed(2) : 'sem avaliações ainda' }}</strong>
              @if (mr.count) { <span class="muted">({{ mr.count }} {{ mr.count === 1 ? 'avaliação' : 'avaliações' }})</span> }</p>
          }
          <p class="small muted">E-mail: {{ me.email }} {{ me.emailVerified ? '(confirmado)' : '(não confirmado)' }} · CPF: {{ me.cpfMasked }}</p>
          <button class="btn primary" (click)="save()" [disabled]="busy()">Salvar dados</button>
        </section>

        <section class="card stack">
          <h2>Locais favoritos</h2>
          @for (f of favorites(); track $index) {
            <div class="row between"><span><strong>{{ f.label }}</strong> · <span class="small muted">{{ f.place.address }}</span></span>
              <button class="btn ghost sm" (click)="removeFav($index)" [attr.aria-label]="'Remover ' + f.label">Remover</button></div>
          }
          <div class="row">
            <select class="input fav-kind" [(ngModel)]="favKind" aria-label="Tipo"><option value="HOME">Casa</option>
              <option value="WORK">Trabalho</option><option value="OTHER">Outro</option></select>
            <ld-place-search class="grow" placeholder="Endereço do favorito" (selected)="favPlace = $event" />
          </div>
          <button class="btn ghost" (click)="addFav()" [disabled]="!favPlace">Adicionar favorito</button>
        </section>

        <ld-account-settings />
        <button class="btn ghost" (click)="logout()">Sair da conta</button>
      }
    </div>
  `,
  styles: `
    .page { max-width: 720px; padding-top: 24px; padding-bottom: 24px; }
    .grow { flex: 1; min-width: 220px; } .fav-kind { width: 130px; }
  `,
})
export class ProfilePage implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly fb = inject(FirebaseService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  protected readonly favorites = signal<FavoritePlace[]>([]);
  protected readonly busy = signal(false);
  protected readonly myRating = signal<{ average: number | null; count: number } | null>(null);
  protected name = '';
  protected phone = '';
  protected favKind: FavoritePlace['kind'] = 'HOME';
  protected favPlace: Place | null = null;

  async ngOnInit(): Promise<void> {
    const me = this.auth.me();
    this.name = me?.fullName ?? '';
    this.phone = me?.phoneNumber ?? '';
    const p = await this.api.get<PassengerProfile>('/passengers/me').catch(() => null);
    this.favorites.set(p?.favorites ?? []);
    this.myRating.set(await this.api.get<{ average: number | null; count: number }>('/passengers/me/rating').catch(() => null));
  }

  protected async save(photoPath?: string): Promise<void> {
    this.busy.set(true);
    try {
      await this.api.patch('/me', { fullName: this.name, phoneNumber: normalizePhone(this.phone), photoPath: photoPath ?? null });
      await this.auth.refreshMe();
      this.toast.ok('Dados atualizados.');
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async upload(ev: Event): Promise<void> {
    const file = (ev.target as HTMLInputElement).files?.[0];
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      this.toast.error('A foto deve ter até 5 MB.');
      return;
    }
    const path = `profilePhotos/${this.auth.me()!.uid}/${Date.now()}.${file.type.split('/')[1]}`;
    try {
      await uploadBytes(ref(this.fb.storage, path), file, { contentType: file.type });
      await this.save(path);
    } catch {
      this.toast.error('Falha ao enviar a foto.');
    }
  }

  protected async addFav(): Promise<void> {
    const label = this.favKind === 'HOME' ? 'Casa' : this.favKind === 'WORK' ? 'Trabalho' : this.favPlace?.address?.split(',')[0] ?? 'Favorito';
    const next = [...this.favorites().filter((f) => f.kind === 'OTHER' || f.kind !== this.favKind),
      { kind: this.favKind, label, place: this.favPlace! }];
    await this.persist(next);
    this.favPlace = null;
  }

  protected removeFav(i: number): void {
    void this.persist(this.favorites().filter((_, idx) => idx !== i));
  }

  private async persist(list: FavoritePlace[]): Promise<void> {
    try {
      const p = await this.api.put<PassengerProfile>('/passengers/me/favorites', { favorites: list });
      this.favorites.set(p.favorites);
    } catch (e) {
      this.toast.error(ApiError.from(e).message);
    }
  }

  protected async logout(): Promise<void> {
    await this.auth.logout();
    await this.router.navigateByUrl('/');
  }
}
