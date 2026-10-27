import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'ld-toasts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="host" aria-live="polite" aria-atomic="false">
      @for (t of toasts.toasts(); track t.id) {
        <div class="toast fade-in" [class]="t.kind" role="status">
          <span>{{ t.text }}</span>
          <button type="button" class="x" (click)="toasts.dismiss(t.id)" aria-label="Fechar aviso">×</button>
        </div>
      }
    </div>
  `,
  styles: `
    .host { position: fixed; z-index: 1000; left: 50%; top: 72px; transform: translateX(-50%);
      display: flex; flex-direction: column; gap: 8px; width: min(440px, calc(100vw - 32px)); }
    .toast { display: flex; gap: 10px; align-items: center; padding: 12px 14px; border-radius: 12px;
      background: var(--ld-ink); color: #fff; box-shadow: var(--ld-shadow-lg); font-size: .92rem; }
    .toast.ok { background: #065f46; } .toast.err { background: #991b1b; } .toast.warn { background: #92400e; }
    .toast span { flex: 1; }
    .x { background: none; border: 0; color: inherit; font-size: 1.3rem; cursor: pointer; line-height: 1; }
  `,
})
export class ToastHostComponent {
  protected readonly toasts = inject(ToastService);
}
