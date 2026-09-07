import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService, UserRole } from './auth.service';

/** Blocks a route unless the current user has one of the given roles; otherwise sends them home. */
export const roleGuard =
  (...roles: UserRole[]): CanActivateFn =>
  () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    if (auth.hasRole(...roles)) {
      return true;
    }
    return router.createUrlTree(['/']);
  };
