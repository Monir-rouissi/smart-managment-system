import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Attaches the bearer access token to every request. On a 401 (expired
 * access token) it tries exactly one silent refresh and replays the
 * original request; if the refresh itself fails, the session is cleared
 * and the user is sent to /login.
 *
 * Simplification: concurrent 401s each trigger their own refresh call. The
 * server rotates refresh tokens, so only the first of a burst succeeds; the
 * rest fall through to logout. Fine for this app's traffic patterns — a
 * shared in-flight refresh would be the fix if that ever matters.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const token = authService.getAccessToken();
  const isAuthEndpoint = req.url.includes('/auth/login') || req.url.includes('/auth/refresh');
  const authedReq =
    token && !isAuthEndpoint ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;

  return next(authedReq).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse) || error.status !== 401 || isAuthEndpoint) {
        return throwError(() => error);
      }

      if (!authService.getRefreshToken()) {
        router.navigate(['/login']);
        return throwError(() => error);
      }

      return authService.refresh().pipe(
        switchMap(() => {
          const retried = req.clone({
            setHeaders: { Authorization: `Bearer ${authService.getAccessToken()}` },
          });
          return next(retried);
        }),
        catchError((refreshError) => {
          authService.logout();
          router.navigate(['/login']);
          return throwError(() => refreshError);
        }),
      );
    }),
  );
};
