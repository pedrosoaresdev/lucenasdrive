import { HttpErrorResponse } from '@angular/common/http';
import { ApiErrorBody } from './models';

/** Erro de API normalizado (código estável + mensagem amigável). */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
    public readonly body?: ApiErrorBody,
  ) {
    super(message);
  }

  static from(err: unknown): ApiError {
    if (err instanceof ApiError) return err;
    if (err instanceof HttpErrorResponse) {
      if (err.status === 0) {
        return new ApiError('NETWORK', 'Sem conexão com o servidor. Verifique sua internet.', 0);
      }
      const body = err.error as ApiErrorBody | null;
      if (body && typeof body === 'object' && 'code' in body) {
        return new ApiError(body.code, body.message, err.status, body);
      }
      return new ApiError('HTTP_' + err.status, 'Erro inesperado (' + err.status + ').', err.status);
    }
    const fbCode = (err as { code?: string })?.code;
    if (typeof fbCode === 'string' && fbCode.startsWith('auth/')) {
      return new ApiError(fbCode, firebaseAuthMessage(fbCode), 400);
    }
    return new ApiError('UNKNOWN', (err as Error)?.message || 'Erro inesperado.', 0);
  }
}

export function firebaseAuthMessage(code: string): string {
  const map: Record<string, string> = {
    'auth/invalid-credential': 'E-mail ou senha incorretos.',
    'auth/invalid-email': 'E-mail inválido.',
    'auth/user-disabled': 'Esta conta está desativada.',
    'auth/email-already-in-use': 'Já existe uma conta com este e-mail.',
    'auth/weak-password': 'Senha fraca. Use ao menos 10 caracteres com letras e números.',
    'auth/too-many-requests': 'Muitas tentativas. Aguarde alguns minutos.',
    'auth/requires-recent-login': 'Entre novamente para confirmar sua identidade.',
    'auth/network-request-failed': 'Falha de rede. Verifique sua conexão.',
    'auth/password-does-not-meet-requirements': 'A senha não atende à política de segurança.',
    'auth/expired-action-code': 'O link expirou. Solicite um novo.',
    'auth/invalid-action-code': 'Link inválido ou já utilizado.',
  };
  return map[code] ?? 'Não foi possível concluir a autenticação.';
}
