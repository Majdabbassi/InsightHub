import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { AuthService } from './auth.service';

function b64url(json: string): string {
  return btoa(json).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function tokenWithExp(expSeconds: number): string {
  return `header.${b64url(JSON.stringify({ exp: expSeconds }))}.sig`;
}

describe('AuthService token hardening', () => {
  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient()] });
  });

  it('treats an expired stored token as logged out', () => {
    const past = Math.floor(Date.now() / 1000) - 3600;
    localStorage.setItem('da_token', tokenWithExp(past));
    localStorage.setItem('da_user', JSON.stringify({ email: 'a@b.c', fullName: 'A', role: 'USER' }));

    const service = TestBed.inject(AuthService);

    expect(service.isAuthenticated).toBe(false);
    expect(service.token).toBeNull();
  });

  it('keeps a live token authenticated', () => {
    const future = Math.floor(Date.now() / 1000) + 3600;
    localStorage.setItem('da_token', tokenWithExp(future));
    localStorage.setItem('da_user', JSON.stringify({ email: 'a@b.c', fullName: 'A', role: 'USER' }));

    const service = TestBed.inject(AuthService);

    expect(service.isAuthenticated).toBe(true);
    expect(service.token).not.toBeNull();
  });

  it('prunes an expired session at startup', () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [provideHttpClient()] });

    const past = Math.floor(Date.now() / 1000) - 60;
    localStorage.setItem('da_token', tokenWithExp(past));
    localStorage.setItem('da_user', JSON.stringify({ email: 'a@b.c', fullName: 'A', role: 'USER' }));

    const fresh = TestBed.inject(AuthService);

    expect(fresh.isAuthenticated).toBe(false);
    expect(localStorage.getItem('da_token')).toBeNull();
    expect(localStorage.getItem('da_user')).toBeNull();
  });
});