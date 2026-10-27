import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, signal } from '@angular/core';
import { limit, orderBy, where } from 'firebase/firestore';
import { ApiService } from '../core/api.service';
import { fmtDateTime } from '../core/format';
import { AppNotification } from '../core/models';
import { RealtimeService } from '../core/realtime.service';

/** Sino de notificações no app (listener do Firestore filtrado pelo próprio usuário). */
@Component({
  selector: 'ld-notification-bell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bell">
      <button type="button" class="btn ghost icon-btn" (click)="open.set(!open())" [attr.aria-expanded]="open()"
              [attr.aria-label]="'Notificações' + (unread() ? ', ' + unread() + ' não lidas' : '')">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor"
          d="M12 22a2.5 2.5 0 0 0 2.45-2h-4.9A2.5 2.5 0 0 0 12 22Zm7-6V11a7 7 0 0 0-5-6.71V3.5a2 2 0 1 0-4 0v.79A7 7 0 0 0 5 11v5l-2 2v1h18v-1Z"/></svg>
        @if (unread()) { <span class="count">{{ unread() > 9 ? '9+' : unread() }}</span> }
      </button>
      @if (open()) {
        <div class="panel card fade-in" role="dialog" aria-label="Notificações">
          <strong>Notificações</strong>
          @for (n of items(); track n.id) {
            <button type="button" class="item" [class.unread]="!n.read" (click)="markRead(n)">
              <span class="t">{{ n.title }}</span>
              <span class="b small">{{ n.body }}</span>
              <span class="d small muted">{{ when(n) }}</span>
            </button>
          } @empty { <p class="small muted">Nenhuma notificação.</p> }
        </div>
      }
    </div>
  `,
  styles: `
    .bell { position: relative; }
    .count { position: absolute; top: 4px; right: 4px; min-width: 18px; height: 18px; border-radius: 9px; padding: 0 4px;
      background: var(--ld-danger); color: #fff; font-size: .7rem; font-weight: 800; display: grid; place-items: center; }
    .panel { position: absolute; right: 0; top: 52px; width: min(360px, calc(100vw - 32px)); max-height: 70vh; overflow: auto;
      z-index: 50; display: flex; flex-direction: column; gap: 6px; padding: 14px; }
    .item { display: flex; flex-direction: column; text-align: left; gap: 2px; padding: 10px; border-radius: 10px;
      border: 0; background: none; cursor: pointer; color: var(--ld-text); font: inherit; }
    .item:hover { background: var(--ld-surface-2); }
    .item.unread { background: var(--ld-primary-soft); }
    .t { font-weight: 650; }
  `,
})
export class NotificationBellComponent implements OnInit {
  private readonly rt = inject(RealtimeService);
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly uid = input.required<string>();
  protected readonly items = signal<AppNotification[]>([]);
  protected readonly open = signal(false);
  protected readonly unread = computed(() => this.items().filter((n) => !n.read).length);

  ngOnInit(): void {
    const sub = this.rt.query<AppNotification>('notifications', where('userId', '==', this.uid()),
      orderBy('createdAt', 'desc'), limit(20)).subscribe({ next: (l) => this.items.set(l), error: () => undefined });
    this.destroyRef.onDestroy(() => sub.unsubscribe());
  }

  protected when(n: AppNotification): string {
    return fmtDateTime(n.createdAt);
  }

  protected async markRead(n: AppNotification): Promise<void> {
    if (!n.read) await this.api.post(`/notifications/${n.id}/read`).catch(() => undefined);
  }
}
