import { Routes } from '@angular/router';

import { authGuard } from './core/auth.guard';
import { roleGuard } from './core/role.guard';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./auth/login').then((m) => m.Login) },

  { path: '', loadComponent: () => import('./home/home').then((m) => m.Home), canActivate: [authGuard] },

  {
    path: 'customers',
    loadComponent: () => import('./customers/customer-list').then((m) => m.CustomerList),
    canActivate: [authGuard],
  },
  {
    path: 'customers/new',
    loadComponent: () => import('./customers/customer-form').then((m) => m.CustomerForm),
    canActivate: [authGuard, roleGuard('ADMIN', 'MANAGER')],
  },
  {
    path: 'customers/:id',
    loadComponent: () => import('./customers/customer-form').then((m) => m.CustomerForm),
    canActivate: [authGuard, roleGuard('ADMIN', 'MANAGER')],
  },

  {
    path: 'projects',
    loadComponent: () => import('./projects/project-list').then((m) => m.ProjectList),
    canActivate: [authGuard],
  },
  {
    path: 'projects/new',
    loadComponent: () => import('./projects/project-form').then((m) => m.ProjectForm),
    canActivate: [authGuard, roleGuard('ADMIN', 'MANAGER')],
  },
  {
    path: 'projects/:id',
    loadComponent: () => import('./projects/project-detail').then((m) => m.ProjectDetail),
    canActivate: [authGuard],
  },
  {
    path: 'projects/:id/edit',
    loadComponent: () => import('./projects/project-form').then((m) => m.ProjectForm),
    canActivate: [authGuard, roleGuard('ADMIN', 'MANAGER')],
  },

  {
    path: 'search',
    loadComponent: () => import('./search/search-page').then((m) => m.SearchPage),
    canActivate: [authGuard],
  },

  {
    path: 'tasks',
    loadComponent: () => import('./tasks/task-list').then((m) => m.TaskList),
    canActivate: [authGuard],
  },

  {
    path: 'chat',
    loadComponent: () => import('./chat/chat-page').then((m) => m.ChatPage),
    canActivate: [authGuard],
  },

  { path: '**', redirectTo: '' },
];
