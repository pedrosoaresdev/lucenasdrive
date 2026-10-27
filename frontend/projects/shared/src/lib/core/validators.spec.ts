import { FormControl, FormGroup } from '@angular/forms';
import { ageOn, cpfValidator, formatCpf, isValidCpf, matchFieldsValidator, minAgeValidator, normalizePhone, passwordStrength,
  phoneValidator, strongPasswordValidator } from './validators';

describe('validators', () => {
  it('valida CPF pelos dígitos verificadores', () => {
    expect(isValidCpf('529.982.247-25')).toBe(true);
    expect(isValidCpf('52998224724')).toBe(false);
    expect(isValidCpf('111.111.111-11')).toBe(false);
    expect(isValidCpf('')).toBe(false);
    expect(cpfValidator(new FormControl('123'))).toEqual({ cpf: true });
    expect(cpfValidator(new FormControl('390.533.447-05'))).toBeNull();
  });

  it('formata CPF progressivamente', () => {
    expect(formatCpf('52998224725')).toBe('529.982.247-25');
    expect(formatCpf('5299')).toBe('529.9');
  });

  it('calcula idade e aplica mínimo', () => {
    expect(ageOn('2000-06-15', new Date('2026-06-14T12:00:00'))).toBe(25);
    expect(ageOn('2000-06-15', new Date('2026-06-15T12:00:00'))).toBe(26);
    const tooYoung = new Date();
    tooYoung.setFullYear(tooYoung.getFullYear() - 17);
    expect(minAgeValidator(18)(new FormControl(tooYoung.toISOString().slice(0, 10)))).toEqual({ minAge: { min: 18 } });
  });

  it('normaliza telefone brasileiro', () => {
    expect(normalizePhone('(83) 99999-0000')).toBe('+5583999990000');
    expect(normalizePhone('+55 83 99999 0000')).toBe('+5583999990000');
    expect(phoneValidator(new FormControl('123'))).toEqual({ phone: true });
  });

  it('exige senha forte e confirmação', () => {
    expect(strongPasswordValidator(new FormControl('curta1'))).toEqual({ weakPassword: true });
    expect(strongPasswordValidator(new FormControl('senhaforte123'))).toBeNull();
    expect(passwordStrength('Senha#Forte2026')).toBe(3);
    const g = new FormGroup({ a: new FormControl('x'), b: new FormControl('y') }, { validators: matchFieldsValidator('a', 'b') });
    expect(g.hasError('mismatch')).toBe(true);
  });
});
