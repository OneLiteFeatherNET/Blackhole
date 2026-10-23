/** The origin of the sign-in authority; the browser fetches its discovery document and token endpoint. */
function connectSources(authority: string | undefined): string {
  return authority ? `'self' ${new URL(authority).origin}` : "'self'"
}

/**
 * Headers every response carries. The CSP is built here so other settings extend it in one place;
 * `connect-src` allows exactly the origin of the configured sign-in authority, never a wildcard.
 */
export function securityHeaders(authority?: string): Record<string, string> {
  return {
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': [
      "default-src 'self'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob:",
      "media-src 'self' blob:",
      `connect-src ${connectSources(authority)}`,
      "frame-ancestors 'none'",
      "base-uri 'self'",
      "form-action 'self'",
    ].join('; '),
  }
}

/** The start page is revalidated on every load; hashed assets are cached by their own rule. */
export function isIndexDocument(path: string): boolean {
  const pathname = path.split('?')[0]
  return pathname === '/' || pathname === '/index.html'
}
