import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import {
  EmailAuthProvider,
  User,
  confirmPasswordReset,
  createUserWithEmailAndPassword,
  onIdTokenChanged,
  reauthenticateWithCredential,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
  updatePassword,
} from 'firebase/auth';
import { firstValueFrom } from 'rxjs';
import { LUCENA_CONFIG } from './config';
import { ApiError } from './errors';
import { FirebaseService } from './firebase.service';
import { Me, Role } from './models';

/**
 * Autenticação: Firebase Authentication (senha com hash gerenciado pelo Firebase — nunca passa
 * pelo backend) + perfil/papéis carregados da API. Papéis exibidos aqui servem só para a UI:
 * o backend revalida tudo em cada requisição.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly fb = inject(FirebaseService);
  private readonly http = inject(HttpClient);
  private readonly config = inject(LUCENA_CONFIG);

  readonly firebaseUser = signal<User | null>(null);
  readonly me = signal<Me | null>(null);
  readonly ready = signal(false);
  readonly isLoggedIn = computed(() => this.firebaseUser() !== null);
  readonly roles = computed<Role[]>(() => this.me()?.roles ?? []);

  private readyResolve!: () => void;
  private readonly readyPromise = new Promise<void>((r) => (this.readyResolve = r));

  constructor() {
    onIdTokenChanged(this.fb.auth, async (user) => {
      const changedUser = user?.uid !== this.firebaseUser()?.uid;
      this.firebaseUser.set(user);
      if (!user) {
        this.me.set(null);
      } else if (changedUser || !this.me()) {
        await this.refreshMe().catch(() => this.me.set(null));
      }
      if (!this.ready()) {
        this.ready.set(true);
        this.readyResolve();
      }
    });
  }

  whenReady(): Promise<void> {
    return this.readyPromise;
  }

  async idToken(forceRefresh = false): Promise<string | null> {
    const u = this.fb.auth.currentUser;
    return u ? u.getIdToken(forceRefresh) : null;
  }

  async refreshMe(): Promise<Me> {
    const me = await firstValueFrom(this.http.get<Me>(`${this.config.apiBaseUrl}/api/v1/me`));
    this.me.set(me);
    return me;
  }

  hasRole(role: Role): boolean {
    return this.roles().includes(role);
  }

  async login(email: string, password: string): Promise<void> {
    try {
      await signInWithEmailAndPassword(this.fb.auth, email.trim(), password);
      await this.refreshMe().catch(() => undefined);
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  async createAccount(email: string, password: string): Promise<User> {
    try {
      const cred = await createUserWithEmailAndPassword(this.fb.auth, email.trim(), password);
      await sendEmailVerification(cred.user).catch(() => undefined);
      return cred.user;
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  async resendVerification(): Promise<void> {
    const u = this.fb.auth.currentUser;
    if (u) await sendEmailVerification(u);
  }

  /** Recarrega o usuário para refletir a confirmação de e-mail (novo token com email_verified). */
  async reloadVerification(): Promise<boolean> {
    const u = this.fb.auth.currentUser;
    if (!u) return false;
    await u.reload();
    await u.getIdToken(true);
    await this.refreshMe().catch(() => undefined);
    return u.emailVerified;
  }

  async requestPasswordReset(email: string): Promise<void> {
    try {
      await sendPasswordResetEmail(this.fb.auth, email.trim());
    } catch (e) {
      const err = ApiError.from(e);
      // Não revela se o e-mail existe (evita enumeração de contas).
      if (err.code !== 'auth/user-not-found') throw err;
    }
  }

  async confirmPasswordReset(oobCode: string, newPassword: string): Promise<void> {
    try {
      await confirmPasswordReset(this.fb.auth, oobCode, newPassword);
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  /** Reautenticação para operações sensíveis (gera auth_time recente, exigido pelo backend). */
  async reauthenticate(password: string): Promise<void> {
    const u = this.fb.auth.currentUser;
    if (!u?.email) throw new ApiError('UNAUTHENTICATED', 'Sessão expirada.', 401);
    try {
      await reauthenticateWithCredential(u, EmailAuthProvider.credential(u.email, password));
      await u.getIdToken(true);
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  async changePassword(current: string, next: string): Promise<void> {
    await this.reauthenticate(current);
    try {
      await updatePassword(this.fb.auth.currentUser!, next);
    } catch (e) {
      throw ApiError.from(e);
    }
  }

  private readonly beforeLogout: (() => Promise<void>)[] = [];

  /** Tarefas executadas antes de sair (ex.: desvincular o token de push deste dispositivo). */
  onBeforeLogout(fn: () => Promise<void>): void {
    this.beforeLogout.push(fn);
  }

  async logout(): Promise<void> {
    for (const fn of this.beforeLogout) await fn().catch(() => undefined);
    await signOut(this.fb.auth);
    this.me.set(null);
  }
}
