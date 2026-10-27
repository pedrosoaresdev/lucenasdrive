import { fmtDateTime, km, minutes, money, rideStatusTone } from './format';
import { ApiError } from './errors';
import { HttpErrorResponse } from '@angular/common/http';

describe('formatação', () => {
  it('formata centavos em reais sem ponto flutuante na origem', () => {
    expect(money(12345).replace(/\s/g, ' ')).toBe('R$ 123,45');
    expect(money(null)).toBe('—');
  });

  it('formata distância e duração', () => {
    expect(km(12340)).toBe('12,3 km');
    expect(minutes(30)).toBe('1 min');
    expect(minutes(3900)).toBe('1 h 5 min');
  });

  it('aceita instantes ISO (API) e epoch ms (Firestore)', () => {
    expect(fmtDateTime('2026-10-20T15:00:00Z')).not.toBe('—');
    expect(fmtDateTime(1792508400000)).not.toBe('—');
    expect(fmtDateTime(null)).toBe('—');
  });

  it('classifica status da corrida', () => {
    expect(rideStatusTone('COMPLETED')).toBe('ok');
    expect(rideStatusTone('CANCELLED')).toBe('err');
  });

  it('normaliza erros da API e do Firebase', () => {
    const http = new HttpErrorResponse({ status: 409, error: { code: 'RIDE_ALREADY_ACCEPTED', message: 'Indisponível', status: 409 } });
    expect(ApiError.from(http).code).toBe('RIDE_ALREADY_ACCEPTED');
    expect(ApiError.from(new HttpErrorResponse({ status: 0 })).code).toBe('NETWORK');
    expect(ApiError.from({ code: 'auth/invalid-credential' }).message).toContain('incorretos');
  });
});
