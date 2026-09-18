/** Shared HTTP error helpers. Keeps per-page error handling consistent:
 *  network failures / 503 share one message, backend messages pass through. */

/** Extracts the HTTP status from an error-like object. Live Angular
 *  `HttpErrorResponse` instances carry a numeric `status`. */
export function httpErrorStatus(err: unknown): number {
  if (err && typeof err === 'object' && 'status' in err) {
    const status = (err as { status?: unknown }).status;
    return typeof status === 'number' ? status : 0;
  }
  return 0;
}

/** Extracts the backend-provided message, if any (the API returns
 *  `{ "message": "..." }` payloads). Falls back to a top-level `message`. */
export function readErrorMessage(err: unknown): string | null {
  if (!err || typeof err !== 'object') return null;
  const body = (err as { error?: { message?: unknown } | string; message?: unknown }).error;
  if (body && typeof body === 'object') {
    const nested = (body as { message?: unknown }).message;
    if (typeof nested === 'string') return nested;
  }
  const message = (err as { message?: unknown }).message;
  if (typeof message === 'string') return message;
  return null;
}

/** A network-level failure (status 0) or an upstream 503 read as the
 *  analytics/assistant service being down. */
export function isServiceUnavailable(err: unknown): boolean {
  const status = httpErrorStatus(err);
  return status === 0 || status === 503;
}

/** Produces a user-facing message: backend message if present, otherwise the
 *  generic fallback. When `unavailableMessage` is given and the failure looks
 *  like a service outage, that message wins. */
export function friendlyErrorMessage(
  err: unknown,
  fallback: string,
  unavailableMessage?: string,
): string {
  if (unavailableMessage && isServiceUnavailable(err)) {
    return unavailableMessage;
  }
  return readErrorMessage(err) ?? fallback;
}