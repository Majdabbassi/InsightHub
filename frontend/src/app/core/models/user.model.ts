export type Role = 'USER' | 'ADMIN';

export interface AuthUser {
  email: string;
  fullName: string;
  role: Role;
}

export interface AuthResponse extends AuthUser {
  token: string;
}

export interface UserResponse extends AuthUser {
  id: number;
  createdAt: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  email: string;
  password: string;
  fullName: string;
}
