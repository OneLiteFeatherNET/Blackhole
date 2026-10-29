/*
 * The blocklists the backend refreshes (application.yml, blackhole.intel.lists). The panel cannot change a URL, and the
 * backend has no route for the refresh status yet, so the status is not part of this list. The attribution text is the
 * one the backend stores with each source; the X4BNet lists are MIT licensed.
 */

export interface ListSource {
  /** Which class the list feeds: VPN networks, or the hosting (datacenter) ranges. */
  kind: 'VPN' | 'HOSTING'
  licence: string
  attribution: string
}

const X4BNET_ATTRIBUTION = 'X4BNet lists_vpn, MIT, copyright 2024 X4B (Mathew Heard), https://github.com/X4BNet/lists_vpn'

export const LIST_SOURCES: ListSource[] = [
  { kind: 'VPN', licence: 'MIT', attribution: X4BNET_ATTRIBUTION },
  { kind: 'HOSTING', licence: 'MIT', attribution: X4BNET_ATTRIBUTION },
]
