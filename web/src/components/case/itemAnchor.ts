// The address of one item inside a page (`restructure-staff-panel`, decision 3): `#event-<id>`. The fragment is never
// sent to the server, so it stays out of access logs. Only ids of the shape the panel shows are accepted; anything
// else is no anchor at all, and the page opens at its top.

const ANCHOR = /^#event-([A-Za-z0-9-]{1,64})$/

/** The item id of a `#event-<id>` fragment, or null for any other fragment (or none). */
export function anchorIdOf(hash: string): string | null {
  const match = ANCHOR.exec(hash)
  return match ? match[1]! : null
}

/** The DOM id of an item. */
export const anchorDomId = (id: string | number): string => `event-${id}`

/** The fragment that addresses an item. */
export const anchorHash = (id: string | number): string => `#${anchorDomId(id)}`
