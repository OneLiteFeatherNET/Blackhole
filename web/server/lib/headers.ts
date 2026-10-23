/** Headers every response carries. The CSP is built here so other settings (SSO) extend it in one place. */
export function securityHeaders(): Record<string, string> {
  return {
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': [
      "default-src 'self'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data:",
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
