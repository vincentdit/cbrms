import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** A request id. randomUUID exists only on HTTPS and localhost, so a LAN address over HTTP needs the fallback. */
function correlationId(): string {
  const webCrypto = globalThis.crypto;
  if (typeof webCrypto?.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof webCrypto?.getRandomValues === 'function') {
    webCrypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) {
      bytes[i] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/** Adds the sign-in header (never on public lookup calls) and a correlation ID; sends the user to sign-in on 401. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const isPublic = req.url.startsWith('/public/');
  const headers = req.headers.set('X-Correlation-Id', correlationId());
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
