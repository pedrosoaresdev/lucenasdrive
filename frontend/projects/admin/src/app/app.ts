import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ToastHostComponent } from '@lucena/shared';
import { ReasonDialogComponent } from './core/reason-dialog.component';
import { StepUpDialogComponent } from './core/step-up-dialog.component';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, ToastHostComponent, StepUpDialogComponent, ReasonDialogComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<router-outlet /><admin-reason-dialog /><admin-step-up /><ld-toasts />`,
})
export class App {}
