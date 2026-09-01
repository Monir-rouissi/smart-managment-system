import { HttpInterceptorFn } from '@angular/common/http';

/**
 * Stub auth interceptor. Once JWT login lands it will attach the bearer token
 * and trigger refresh on 401. For now it is a transparent pass-through so the
 * wiring is already in place.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = localStorage.getItem('accessToken');
  if (token) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);
};
