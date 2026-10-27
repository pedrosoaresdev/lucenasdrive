import { HttpClient, HttpErrorResponse, HttpHeaders, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { from, switchMap, throwError, catchError, firstValueFrom } from 'rxjs';
import { LUCENA_CONFIG } from './config';
import { ApiError } from './errors';
import { FirebaseService } from './firebase.service';

/** Token de sessão administrativa pós-MFA (somente memória/sessionStorage, nunca localStorage). */
export const MFA_STORAGE_KEY = 'lucena.adminMfa';

export function readMfaToken(): string | null {
  try {
    return sessionStorage.getItem(MFA_STORAGE_KEY);
  } catch {
    return null;
  }
}

/** Anexa o ID token do Firebase (e o token MFA no portal administrativo) às chamadas da API. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const config = inject(LUCENA_CONFIG);
  const fb = inject(FirebaseService);
  if (!req.url.startsWith(config.apiBaseUrl)) {
    return next(req);
  }
  return from(fb.auth.currentUser ? fb.auth.currentUser.getIdToken() : Promise.resolve(null)).pipe(
    switchMap((token) => {
      let headers = req.headers;
      if (token) headers = headers.set('Authorization', `Bearer ${token}`);
      const mfa = config.portal === 'admin' ? readMfaToken() : null;
      if (mfa) headers = headers.set('X-Lucena-MFA', mfa);
      return next(req.clone({ headers }));
    }),
    catchError((err: unknown) => throwError(() => (err instanceof HttpErrorResponse ? ApiError.from(err) : err))),
  );
};

type Query = Record<string, string | number | boolean | null | undefined>;

/** Cliente HTTP tipado da API Lucena Drive (Promises para uso simples em componentes). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(LUCENA_CONFIG).apiBaseUrl + '/api/v1';

  get<T>(path: string, query?: Query): Promise<T> {
    return this.wrap(this.http.get<T>(this.base + path, { params: params(query) }));
  }

  post<T>(path: string, body?: unknown, opts?: { idempotencyKey?: string }): Promise<T> {
    let headers = new HttpHeaders();
    if (opts?.idempotencyKey) headers = headers.set('Idempotency-Key', opts.idempotencyKey);
    return this.wrap(this.http.post<T>(this.base + path, body ?? {}, { headers }));
  }

  put<T>(path: string, body: unknown): Promise<T> {
    return this.wrap(this.http.put<T>(this.base + path, body));
  }

  patch<T>(path: string, body: unknown): Promise<T> {
    return this.wrap(this.http.patch<T>(this.base + path, body));
  }

  delete<T>(path: string): Promise<T> {
    return this.wrap(this.http.delete<T>(this.base + path));
  }

  download(path: string, query?: Query): Promise<Blob> {
    return this.wrap(this.http.get(this.base + path, { params: params(query), responseType: 'blob' }));
  }

  private async wrap<T>(obs: import('rxjs').Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(obs, { defaultValue: null as T });
    } catch (e) {
      throw ApiError.from(e);
    }
  }
}

function params(q?: Query): HttpParams {
  let p = new HttpParams();
  for (const [k, v] of Object.entries(q ?? {})) {
    if (v !== undefined && v !== null && v !== '') p = p.set(k, String(v));
  }
  return p;
}

export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
