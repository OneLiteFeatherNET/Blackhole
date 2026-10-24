/**
 * Link rules of the browser side. The backend decides what a link is (`blocks`, see the markdown
 * module); this file checks the same rules again, because a `/leave#...` URL can be handed to anybody
 * and the page must not trust what it finds in the fragment.
 */

/** The only domain whose links skip the interstitial, together with its true subdomains. */
export const TRUSTED_DOMAIN = 'onelitefeather.net'

const MAX_URL_LENGTH = 2048
/** Control characters, space, backslash and the characters that break out of an attribute. */
const FORBIDDEN = /[\u0000- \u007f-\u009f\\<>"]/

/** Lower case, without the trailing dot a fully qualified name may carry. */
export function normaliseHost(host: string): string {
  return host.toLowerCase().replace(/\.$/, '')
}

/** True for `onelitefeather.net` and its real subdomains; never for a host that merely ends with the same letters. */
export function isTrustedHost(host: string): boolean {
  // Every label before the domain must be a real label: no empty label, so `.onelitefeather.net` is not trusted.
  return /^(?:[a-z0-9_-]+\.)*onelitefeather\.net$/.test(normaliseHost(host))
}

/**
 * The URL when it is a plain absolute http or https link, otherwise null. Same rules as the backend:
 * at most 2048 characters, no control character, space, backslash or `<>"`, no user info, a host,
 * and a port in range. The returned string is the normalised absolute URL.
 */
export function safeHttpUrl(raw: unknown): string | null {
  if (typeof raw !== 'string') return null
  const value = raw.trim()
  if (value.length === 0 || value.length > MAX_URL_LENGTH || FORBIDDEN.test(value)) return null
  let url: URL
  try {
    url = new URL(value)
  } catch {
    return null
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
  if (url.username !== '' || url.password !== '') return null
  if (url.hostname === '') return null
  if (url.port !== '' && (Number(url.port) < 1 || Number(url.port) > 65535)) return null
  return url.href
}

/** Whether a link to this URL may go straight out: a safe http(s) URL on a trusted host. */
export function isTrustedUrl(url: string): boolean {
  const safe = safeHttpUrl(url)
  return safe !== null && isTrustedHost(new URL(safe).hostname)
}

function toBase64Url(text: string): string {
  const bytes = new TextEncoder().encode(text)
  let binary = ''
  for (const byte of bytes) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(encoded: string): string | null {
  if (!/^[A-Za-z0-9_-]*$/.test(encoded)) return null
  const padded = encoded.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(encoded.length / 4) * 4, '=')
  try {
    const binary = atob(padded)
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return null
  }
}

/** The `/leave` path for an external link. The target is in the fragment, so no server log or Referer sees it. */
export function leavePath(url: string): string {
  return `/leave#${toBase64Url(url)}`
}

/** The target of a `/leave` fragment, validated again; null for anything that is not a safe http(s) URL. */
export function leaveTarget(fragment: string): string | null {
  const encoded = fragment.startsWith('#') ? fragment.slice(1) : fragment
  const decoded = fromBase64Url(encoded)
  return decoded === null ? null : safeHttpUrl(decoded)
}
