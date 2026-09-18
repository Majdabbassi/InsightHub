import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AuthResponse,
  AuthUser,
  LoginRequest,
  RegisterRequest,
  UserResponse,
} from '../models/user.model';

const TOKEN_KEY = 'da_token';
const USER_KEY = 'da_user';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = `${environment.apiUrl}/auth`;

  private readonly currentUserSignal = signal<AuthUser | null>(this.restoreUserFromStorage());

  readonly currentUser = this.currentUserSignal.asReadonly();

  /** Fresh per read: cached user plus a non-expired token decide auth. */
  get isAuthenticated(): boolean {
    return this.currentUserSignal() !== null && !this.isTokenExpired();
  }

  constructor() {
    this.pruneExpiredSession();
  }

  get token(): string | null {
    if (this.isTokenExpired()) {
      this.logout();
      return null;
    }
    return localStorage.getItem(TOKEN_KEY);
  }

  register(payload: RegisterRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/register`, payload)
      .pipe(tap((response) => this.setSession(response)));
  }

  login(credentials: LoginRequest): Observable<AuthResponse> {
    return this.http
      .post<AuthResponse>(`${this.apiUrl}/login`, credentials)
      .pipe(tap((response) => this.setSession(response)));
  }

  logout(): void {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    this.currentUserSignal.set(null);
  }

  getCurrentUser(): Observable<UserResponse> {
    return this.http.get<UserResponse>(`${this.apiUrl}/me`).pipe(
      tap((user) => {
        const authUser: AuthUser = {
          email: user.email,
          fullName: user.fullName,
          role: user.role,
        };
        this.persistUser(authUser);
        this.currentUserSignal.set(authUser);
      })
    );
  }

  private setSession(response: AuthResponse): void {
    const user: AuthUser = {
      email: response.email,
      fullName: response.fullName,
      role: response.role,
    };
    localStorage.setItem(TOKEN_KEY, response.token);
    this.persistUser(user);
    this.currentUserSignal.set(user);
  }

  private persistUser(user: AuthUser): void {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  private restoreUserFromStorage(): AuthUser | null {
    try {
      const raw = localStorage.getItem(USER_KEY);
      return raw ? (JSON.parse(raw) as AuthUser) : null;
    } catch {
      return null;
    }
  }

  /** True when a stored token exists but its JWT `exp` claim has passed. */
  private isTokenExpired(): boolean {
    const raw = localStorage.getItem(TOKEN_KEY);
    if (!raw) {
      return false;
    }
    const expSeconds = this.readExpirySeconds(raw);
    if (expSeconds === null) {
      // Not parseable (e.g. opaque token) — let the backend 401 handling decide.
      return false;
    }
    return expSeconds * 1000 <= Date.now();
  }

  /** Drops an already-expired session so stale tokens never linger. */
  private pruneExpiredSession(): void {
    if (this.isTokenExpired()) {
      this.logout();
    }
  }

  private readExpirySeconds(token: string): number | null {
    try {
      const payload = token.split('.')[1];
      if (!payload) {
        return null;
      }
      const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
      const data = JSON.parse(atob(normalized)) as { exp?: unknown };
      return typeof data.exp === 'number' ? data.exp : null;
    } catch {
      return null;
    }
  }
}
