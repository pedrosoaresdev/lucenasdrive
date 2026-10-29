import { TestBed } from '@angular/core/testing';
import { ApiService } from '../core/api.service';
import { ToastService } from '../core/toast.service';
import { SafetyPanelComponent } from './safety-panel.component';

describe('SafetyPanelComponent', () => {
  function setup(canShare: boolean) {
    const api = { post: vi.fn().mockResolvedValue({ token: 'tok_abcdefghijklmnopqrstuvwxyz' }), delete: vi.fn().mockResolvedValue(null) };
    const toast = { ok: vi.fn(), error: vi.fn(), show: vi.fn() };
    TestBed.configureTestingModule({ providers: [{ provide: ApiService, useValue: api }, { provide: ToastService, useValue: toast }] });
    const fixture = TestBed.createComponent(SafetyPanelComponent);
    fixture.componentRef.setInput('rideId', 'r1');
    fixture.componentRef.setInput('canShare', canShare);
    fixture.detectChanges();
    return { fixture, api, el: fixture.nativeElement as HTMLElement };
  }

  it('sempre mostra os atalhos de emergência e deixa claro que não há central 24 h', () => {
    const { el } = setup(false);
    const links = Array.from(el.querySelectorAll('a')).map((a) => a.getAttribute('href'));
    expect(links).toContain('tel:190');
    expect(links).toContain('tel:192');
    expect(el.textContent).toContain('não tem central de monitoramento 24 horas');
    expect(el.textContent).not.toContain('Compartilhar viagem');
  });

  it('passageiro gera link de acompanhamento e envia alerta', async () => {
    const { fixture, api, el } = setup(true);
    const share = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Gerar link')) as HTMLButtonElement;
    share.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.post).toHaveBeenCalledWith('/rides/r1/share');
    expect((el.querySelector('input[aria-label="Link de acompanhamento"]') as HTMLInputElement).value)
      .toContain('/acompanhar/tok_abcdefghijklmnopqrstuvwxyz');
    const alert = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Enviar alerta')) as HTMLButtonElement;
    alert.click();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(api.post).toHaveBeenCalledWith('/rides/r1/safety-alert', { description: null });
    expect(el.textContent).toContain('Alerta enviado');
  });
});
