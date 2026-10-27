import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { getDownloadURL, ref } from 'firebase/storage';
import { FirebaseService } from '../core/firebase.service';

/** Foto de perfil (caminho no Storage) com fallback de iniciais. */
@Component({
  selector: 'ld-avatar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (url()) {
      <img [src]="url()" [alt]="'Foto de ' + name()" [style.width.px]="size()" [style.height.px]="size()" />
    } @else {
      <span class="ini" [style.width.px]="size()" [style.height.px]="size()" aria-hidden="true">{{ initials() }}</span>
    }
  `,
  styles: `
    img, .ini { border-radius: 50%; object-fit: cover; display: inline-grid; place-items: center; flex: none; }
    .ini { background: var(--ld-primary-soft); color: var(--ld-primary); font-weight: 800; }
  `,
})
export class AvatarComponent {
  private readonly fb = inject(FirebaseService);
  readonly path = input<string | null | undefined>(null);
  readonly name = input('');
  readonly size = input(44);
  protected readonly url = signal<string | null>(null);
  protected readonly initials = computed(() =>
    this.name().split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]?.toUpperCase()).join('') || '?');

  constructor() {
    effect(() => {
      const p = this.path();
      this.url.set(null);
      if (p && p.startsWith('profilePhotos/')) {
        getDownloadURL(ref(this.fb.storage, p)).then((u) => this.url.set(u)).catch(() => this.url.set(null));
      }
    });
  }
}
