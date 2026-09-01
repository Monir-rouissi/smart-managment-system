import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home) },

  {
    path: 'customers',
    loadComponent: () => import('./customers/customer-list').then((m) => m.CustomerList),
  },
  {
    path: 'customers/new',
    loadComponent: () => import('./customers/customer-form').then((m) => m.CustomerForm),
  },
  {
    path: 'customers/:id',
    loadComponent: () => import('./customers/customer-form').then((m) => m.CustomerForm),
  },

  {
    path: 'projects',
    loadComponent: () => import('./projects/project-list').then((m) => m.ProjectList),
  },
  {
    path: 'projects/new',
    loadComponent: () => import('./projects/project-form').then((m) => m.ProjectForm),
  },
  {
    path: 'projects/:id',
    loadComponent: () => import('./projects/project-detail').then((m) => m.ProjectDetail),
  },
  {
    path: 'projects/:id/edit',
    loadComponent: () => import('./projects/project-form').then((m) => m.ProjectForm),
  },

  {
    path: 'tasks',
    loadComponent: () => import('./tasks/task-list').then((m) => m.TaskList),
  },

  { path: '**', redirectTo: '' },
];
