import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'login',
    title: 'Sign in · Data Analytics',
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: 'register',
    title: 'Create account · Data Analytics',
    loadComponent: () => import('./features/auth/register/register').then((m) => m.Register),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./core/layout/app-shell').then((m) => m.AppShell),
    children: [
      {
        path: 'dashboard',
        title: 'Dashboard · Data Analytics',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
      },
      {
        path: 'projects',
        title: 'Projects · Data Analytics',
        loadComponent: () =>
          import('./features/projects/project-list/project-list').then((m) => m.ProjectList),
      },
      {
        path: 'projects/new',
        title: 'New project · Data Analytics',
        loadComponent: () =>
          import('./features/projects/project-form/project-form').then((m) => m.ProjectForm),
      },
      {
        path: 'projects/:id',
        title: 'Project · Data Analytics',
        loadComponent: () =>
          import('./features/projects/project-detail/project-detail').then((m) => m.ProjectDetail),
      },
      {
        path: 'projects/:id/overview',
        redirectTo: '/projects/:id',
        pathMatch: 'full',
      },
      {
        path: 'projects/:id/datasets/:datasetId',
        title: 'Dataset · Data Analytics',
        loadComponent: () =>
          import('./features/datasets/dataset-viewer/dataset-viewer').then((m) => m.DatasetViewer),
      },
      {
        path: 'projects/:id/datasets/:datasetId/dashboard',
        title: 'Charts · Data Analytics',
        loadComponent: () =>
          import('./features/datasets/dataset-dashboard/dataset-dashboard-redirect').then(
            (m) => m.DatasetDashboardRedirect
          ),
      },
      {
        path: 'projects/:id/edit',
        title: 'Edit project · Data Analytics',
        loadComponent: () =>
          import('./features/projects/project-form/project-form').then((m) => m.ProjectForm),
      },
    ],
  },
  {
    path: '**',
    title: 'Page not found · Data Analytics',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];
