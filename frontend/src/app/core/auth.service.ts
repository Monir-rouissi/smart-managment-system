import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

import { environment } from '../../environments/environment';

export type UserRole = 'ADMIN' | 'MANAGER' | 'USER';

export interface CurrentUser {
  id: string;
  email: string;
  fullName: string | null;
  role: UserRole;
}

interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: CurrentUser;
}

const ACCESS_TOKEN_KEY = 'accessToken';
const REFRESH_TOKEN_KEY = 'refreshToken';
const USER_KEY = 'currentUser';

/**
 * Holds the JWT session client-side: access/refresh tokens and the current
 * user in localStorage, plus a signal so templates can react to login/logout
 * without a page reload.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly userSignal = signal<CurrentUser | null>(this.readStoredUser());
  readonly user = this.userSignal.asReadonly();
  readonly isAuthenticated = computed(() => this.userSignal() !== null);

  login(email: string, password: string): Observable<LoginResponse> {
    return this.http
      .post<LoginResponse>(`${environment.apiUrl}/auth/login`, { email, password })
      .pipe(tap((res) => this.storeSession(res)));
  }

  /** Best-effort server-side revoke; the local session is always cleared regardless. */
  logout(): void {
    const refreshToken = this.getRefreshToken();
    this.clearSession();
    if (refreshToken) {
      this.http.post(`${environment.apiUrl}/auth/logout`, { refreshToken }).subscribe({ error: () => {} });
    }
  }

  refresh(): Observable<LoginResponse> {
    const refreshToken = this.getRefreshToken();
    return new Observable<LoginResponse>((subscriber) => {
      if (!refreshToken) {
        subscriber.error(new Error('No refresh token'));
        return;
      }
      this.http.post<LoginResponse>(`${environment.apiUrl}/auth/refresh`, { refreshToken }).subscribe({
        next: (res) => {
          this.storeSession(res);
          subscriber.next(res);
          subscriber.complete();
        },
        error: (err) => {
          this.clearSession();
          subscriber.error(err);
        },
      });
    });
  }

  getAccessToken(): string | null {
    return localStorage.getItem(ACCESS_TOKEN_KEY);
  }

  getRefreshToken(): string | null {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  }

  hasRole(...roles: UserRole[]): boolean {
    const current = this.userSignal();
    return current !== null && roles.includes(current.role);
  }

  private storeSession(res: LoginResponse): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, res.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, res.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify(res.user));
    this.userSignal.set(res.user);
  }

  private clearSession(): void {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.userSignal.set(null);
  }

  private readStoredUser(): CurrentUser | null {
    try {
      const raw = localStorage.getItem(USER_KEY);
      return raw ? (JSON.parse(raw) as CurrentUser) : null;
    } catch {
      return null;
    }
  }
}
