import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { NavigationEnd, provideRouter, Router } from '@angular/router';
import { filter, firstValueFrom, of } from 'rxjs';
import { Login } from './login';
import { AuthService } from '../../../core/services/auth.service';
import { AuthResponse } from '../../../core/models/user.model';

const authServiceMock = {
  login: vi.fn(),
};

@Component({ template: '' })
class DashboardStub {}

describe('Login smoke tests', () => {
  beforeEach(() => {
    authServiceMock.login.mockReset();
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: authServiceMock },
        provideRouter([{ path: 'dashboard', component: DashboardStub }]),
      ],
    });
  });

  function fillAndSubmit(email: string, password: string): HTMLElement {
    const fixture = TestBed.createComponent(Login);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const emailInput = el.querySelector<HTMLInputElement>('#email')!;
    const passwordInput = el.querySelector<HTMLInputElement>('#password')!;
    emailInput.value = email;
    emailInput.dispatchEvent(new Event('input'));
    passwordInput.value = password;
    passwordInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    el.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    return el;
  }

  it('renders the sign-in form', () => {
    const el = fillAndSubmit('', '');

    expect(el.querySelector('h1')!.textContent).toContain('Welcome back');
    expect(el.querySelector('#email')).toBeTruthy();
    expect(el.querySelector('#password')).toBeTruthy();
    expect(
      el.querySelector<HTMLButtonElement>('button[type="submit"]')!.textContent
    ).toContain('Sign in');
  });

  it('does not call login when the form is invalid and surfaces field errors', () => {
    const el = fillAndSubmit('', '');

    expect(el.querySelectorAll('.field-error').length).toBeGreaterThan(0);
    expect(authServiceMock.login).not.toHaveBeenCalled();
  });

  it('calls login and navigates to /dashboard on success', async () => {
    authServiceMock.login.mockReturnValue(
      of<AuthResponse>({
        token: 'jwt-token',
        email: 'dev@example.com',
        fullName: 'Dev',
        role: 'USER',
      })
    );

    const router = TestBed.inject(Router);
    const navigationDone = firstValueFrom(
      router.events.pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd)
      )
    );

    fillAndSubmit('dev@example.com', 'correct-pass');

    await navigationDone;
    expect(authServiceMock.login).toHaveBeenCalledWith({
      email: 'dev@example.com',
      password: 'correct-pass',
    });
    expect(router.url).toBe('/dashboard');
  });
});