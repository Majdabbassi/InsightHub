import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { authGuard } from './auth.guard';

@Component({ template: '' })
class ProtectedComponent {}

@Component({ template: '' })
class LoginComponent {}

function setup() {
  TestBed.configureTestingModule({
    providers: [
      AuthService,
      provideHttpClient(),
      provideRouter([
        { path: '', component: LoginComponent },
        { path: 'login', component: LoginComponent },
        { path: 'protected', canActivate: [authGuard], component: ProtectedComponent },
      ]),
    ],
  });
  return { router: TestBed.inject(Router) };
}

describe('authGuard', () => {
  beforeEach(() => localStorage.clear());

  it('blocks unauthenticated users and redirects to /login with a returnUrl', async () => {
    const { router } = setup();

    await router.navigateByUrl('/protected');

    expect(router.url).toBe('/login?returnUrl=%2Fprotected');
  });

  it('allows authenticated users to enter the route', async () => {
    localStorage.setItem(
      'da_user',
      JSON.stringify({ email: 'dev@example.com', fullName: 'Dev', role: 'USER' })
    );
    const { router } = setup();

    await router.navigateByUrl('/protected');

    expect(router.url).toBe('/protected');
  });
});