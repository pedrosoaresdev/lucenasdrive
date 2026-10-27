import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Validação matemática de CPF (dígitos verificadores) — não confirma identidade. */
export function isValidCpf(raw: string | null | undefined): boolean {
  const cpf = (raw ?? '').replace(/\D/g, '');
  if (cpf.length !== 11 || /^(\d)\1{10}$/.test(cpf)) return false;
  const digit = (len: number) => {
    let sum = 0;
    for (let i = 0; i < len; i++) sum += Number(cpf[i]) * (len + 1 - i);
    const r = (sum * 10) % 11;
    return r === 10 ? 0 : r;
  };
  return digit(9) === Number(cpf[9]) && digit(10) === Number(cpf[10]);
}

export function formatCpf(raw: string): string {
  const d = raw.replace(/\D/g, '').slice(0, 11);
  return d.replace(/(\d{3})(\d)/, '$1.$2').replace(/(\d{3})(\d)/, '$1.$2').replace(/(\d{3})(\d{1,2})$/, '$1-$2');
}

export const cpfValidator: ValidatorFn = (c: AbstractControl): ValidationErrors | null =>
  !c.value || isValidCpf(c.value) ? null : { cpf: true };

export function ageOn(birth: string, today = new Date()): number {
  const b = new Date(birth + 'T12:00:00');
  let age = today.getFullYear() - b.getFullYear();
  const m = today.getMonth() - b.getMonth();
  if (m < 0 || (m === 0 && today.getDate() < b.getDate())) age--;
  return age;
}

export function minAgeValidator(min: number): ValidatorFn {
  return (c) => {
    if (!c.value) return null;
    const age = ageOn(c.value);
    if (Number.isNaN(age) || age > 120) return { birthDate: true };
    return age < min ? { minAge: { min } } : null;
  };
}

/** Telefone brasileiro, normalizado para +55DDDNÚMERO. */
export function normalizePhone(raw: string): string {
  const d = (raw ?? '').replace(/\D/g, '').replace(/^55(?=\d{10,11}$)/, '');
  return '+55' + d;
}

export const phoneValidator: ValidatorFn = (c) =>
  !c.value || /^\+55\d{10,11}$/.test(normalizePhone(c.value)) ? null : { phone: true };

/** Senha: mínimo 10 caracteres, com letras e números (alinhar com a política do Firebase Auth). */
export const strongPasswordValidator: ValidatorFn = (c) => {
  const v: string = c.value ?? '';
  if (!v) return null;
  return v.length >= 10 && /[A-Za-z]/.test(v) && /\d/.test(v) ? null : { weakPassword: true };
};

export function matchFieldsValidator(a: string, b: string): ValidatorFn {
  return (group) => (group.get(a)?.value === group.get(b)?.value ? null : { mismatch: true });
}

export function passwordStrength(v: string): 0 | 1 | 2 | 3 {
  let score = 0;
  if (v.length >= 10) score++;
  if (/[A-Z]/.test(v) && /[a-z]/.test(v) && /\d/.test(v)) score++;
  if (/[^A-Za-z0-9]/.test(v) || v.length >= 14) score++;
  return score as 0 | 1 | 2 | 3;
}
