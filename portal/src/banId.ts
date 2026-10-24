const ALPHABET = /^[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{8}$/

/**
 * Normalises a typed Ban ID the way the backend does (spaces, grouping hyphens and one leading `#`
 * removed, upper-cased, `O` read as `0`, `I` and `L` as `1`). Returns null when what is left is not
 * exactly eight Crockford characters; the caller then shows the same message as for a wrong ID.
 */
export function normaliseBanId(input: string): string | null {
  const cleaned = input
    .trim()
    .replace(/^#/, '')
    .replace(/[\s-]/g, '')
    .toUpperCase()
    .replace(/O/g, '0')
    .replace(/[IL]/g, '1')
  return ALPHABET.test(cleaned) ? cleaned : null
}
