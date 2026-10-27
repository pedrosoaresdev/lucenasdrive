import { TestBed } from '@angular/core/testing';
import { StarRatingComponent } from './star-rating.component';

describe('StarRatingComponent', () => {
  function setup() {
    const fixture = TestBed.createComponent(StarRatingComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('renderiza 5 estrelas como grupo de rádio acessível', () => {
    const { el } = setup();
    expect(el.querySelector('[role="radiogroup"]')).toBeTruthy();
    const stars = el.querySelectorAll('[role="radio"]');
    expect(stars.length).toBe(5);
    expect(stars[0].getAttribute('aria-label')).toContain('muito ruim');
    expect(stars[4].getAttribute('aria-label')).toContain('excelente');
  });

  it('seleciona por clique e por teclado, limitado entre 1 e 5', async () => {
    const { fixture, el } = setup();
    (el.querySelectorAll('[role="radio"]')[3] as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.componentInstance.value()).toBe(4);
    expect(el.querySelectorAll('[aria-checked="true"]').length).toBe(1);
    const group = el.querySelector('[role="radiogroup"]')!;
    group.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    group.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    expect(fixture.componentInstance.value()).toBe(5);
    group.dispatchEvent(new KeyboardEvent('keydown', { key: 'Home' }));
    expect(fixture.componentInstance.value()).toBe(1);
    group.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft' }));
    expect(fixture.componentInstance.value()).toBe(1);
    fixture.detectChanges();
    expect(el.querySelector('.caption')?.textContent).toContain('muito ruim');
  });

  it('modo somente leitura não altera o valor', () => {
    const { fixture, el } = setup();
    fixture.componentRef.setInput('readonly', true);
    fixture.componentInstance.value.set(3);
    fixture.detectChanges();
    (el.querySelectorAll('[role="radio"]')[0] as HTMLButtonElement).click();
    expect(fixture.componentInstance.value()).toBe(3);
  });
});
