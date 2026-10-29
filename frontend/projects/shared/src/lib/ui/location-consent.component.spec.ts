import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PresenceService } from '../core/presence.service';
import { LocationConsentComponent } from './location-consent.component';

describe('LocationConsentComponent', () => {
  function setup(ask: boolean, enabled = false, denied = false) {
    const fake = {
      askNeeded: signal(ask), enabled: signal(enabled), browserDenied: signal(denied),
      start: vi.fn().mockResolvedValue(undefined), enable: vi.fn().mockResolvedValue(undefined), dismiss: vi.fn(),
    };
    TestBed.configureTestingModule({ providers: [{ provide: PresenceService, useValue: fake }] });
    const fixture = TestBed.createComponent(LocationConsentComponent);
    fixture.componentRef.setInput('kind', 'DRIVER');
    fixture.detectChanges();
    return { fixture, fake, el: fixture.nativeElement as HTMLElement };
  }

  it('convida de forma opcional e explica quem vê e o quê', () => {
    const { el, fake } = setup(true);
    expect(fake.start).toHaveBeenCalledWith('DRIVER');
    const text = el.textContent ?? '';
    expect(text).toContain('É opcional');
    expect(text).toContain('motorista');
    const buttons = Array.from(el.querySelectorAll('button')).map((b) => b.textContent?.trim());
    expect(buttons).toEqual(['Permitir', 'Agora não']);
  });

  it('"Permitir" ativa e "Agora não" dispensa', () => {
    const { el, fake } = setup(true);
    const [allow, later] = Array.from(el.querySelectorAll('button')) as HTMLButtonElement[];
    allow.click();
    later.click();
    expect(fake.enable).toHaveBeenCalledTimes(1);
    expect(fake.dismiss).toHaveBeenCalledTimes(1);
  });

  it('não aparece sem convite pendente e avisa quando o navegador bloqueia', () => {
    expect(setup(false).el.querySelector('[role="dialog"]')).toBeNull();
    TestBed.resetTestingModule();
    const { el } = setup(false, true, true);
    expect(el.textContent).toContain('bloqueada no navegador');
  });
});
