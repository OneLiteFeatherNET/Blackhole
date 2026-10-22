/**
 * The URL when `value` is an absolute http(s) URL, otherwise null. Used before rendering any anchor
 * and when validating a link the user adds: a stored `javascript:` or `data:` value must never
 * become clickable.
 */
export function safeHttpUrl(value: string | null | undefined): URL | null {
  if (!value) return null
  try {
    const url = new URL(value.trim())
    return url.protocol === 'https:' || url.protocol === 'http:' ? url : null
  } catch {
    return null
  }
}
