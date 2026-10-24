/**
 * Headers every response carries. The portal talks to its own origin only (the BFF), so the policy
 * has no external source at all; styles are one hashed file, so no inline styles either.
 */
export function securityHeaders(): Record<string, string> {
  return {
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': [
      "default-src 'self'",
      "script-src 'self'",
      "style-src 'self'",
      "img-src 'self' blob:",
      "font-src 'self'",
      "connect-src 'self'",
      "object-src 'none'",
      "frame-ancestors 'none'",
      "base-uri 'none'",
      "form-action 'self'",
    ].join('; '),
  }
}

/** The start page is revalidated on every load; hashed assets are cached by their own rule. */
export function isIndexDocument(path: string): boolean {
  const pathname = path.split('?')[0]
  return pathname === '/' || pathname === '/index.html'
}
