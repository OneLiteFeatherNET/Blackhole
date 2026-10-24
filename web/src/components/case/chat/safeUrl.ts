/** The longest link target the backend accepts; the panel checks the same limit again. */
export const MAX_URL_LENGTH = 2048

// Control characters, white space and the characters that never belong in a target.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/
const UNSAFE_CHARACTERS = /[ \\<>"]/

/**
 * The normalised absolute URL when `value` is an http(s) link the chat may show, otherwise null.
 * It mirrors the backend's rules: no control characters, spaces, backslashes or `<>"`, scheme
 * http or https, a host, no user information and a port in 1..65535. Nothing is fetched.
 */
export function safeHttpUrl(value: string): string | null {
  const trimmed = value.trim()
  if (trimmed === '' || trimmed.length > MAX_URL_LENGTH) return null
  if (CONTROL.test(trimmed) || UNSAFE_CHARACTERS.test(trimmed)) return null

  let url: URL
  try {
    url = new URL(trimmed)
  } catch {
    return null
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null
  if (url.username !== '' || url.password !== '' || url.hostname === '') return null
  if (url.port !== '' && (Number(url.port) < 1 || Number(url.port) > 65535)) return null
  return url.href
}

/** The fragment of the interstitial link: base64url of the UTF-8 target, so it never reaches a server or a Referer. */
export function encodeLeaveTarget(url: string): string {
  let binary = ''
  for (const byte of new TextEncoder().encode(url)) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** The target of an interstitial fragment, or null when the fragment is not a base64url UTF-8 text. */
export function decodeLeaveTarget(fragment: string): string | null {
  if (!/^[A-Za-z0-9_-]+$/.test(fragment)) return null
  try {
    const base64 = fragment.replace(/-/g, '+').replace(/_/g, '/')
    const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4))
    const bytes = Uint8Array.from(binary, (char) => char.charCodeAt(0))
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    return null
  }
}
