import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Adds the sign-in header (never on public lookup calls) and a correlation ID; sends the user to sign-in on 401. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const isPublic = req.url.startsWith('/public/');
  const headers = req.headers.set('X-Correlation-Id', crypto.randomUUID());
  if (isPublic || headers.has('Authorization')) {
    return next(req.clone({ headers }));
  }
  return from(auth.authorization()).pipe(
    switchMap((value) => next(req.clone({ headers: value ? headers.set('Authorization', value) : headers }))),
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && !req.url.endsWith('/me')) {
        auth.me.set(null);
        if (!auth.sso()) {
          auth.logout();
        }
        router.navigate(['/sign-in']);
      }
      return throwError(() => error);
    }),
  );
};
