import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastHostComponent } from '@lucena/shared';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHostComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<router-outlet /><ld-toasts />`,
})
export class App {}
