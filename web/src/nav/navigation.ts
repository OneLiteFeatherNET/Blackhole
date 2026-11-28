import type { CountKey } from './navCounts'
import {
  APPEAL_CONDITIONS_ICON,
  BLOCK_ICON,
  CLIENT_RULES_ICON,
  CLOUD_ICON,
  FACT_CHECK_ICON,
  FLAG_ICON,
  FOLDER_OPEN_ICON,
  GAVEL_ICON,
  HISTORY_ICON,
  INBOX_ICON,
  MANAGE_ACCOUNTS_ICON,
  MAP_ICON,
  PERSON_REMOVE_ICON,
  PERSON_SEARCH_ICON,
  PLAYLIST_ADD_CHECK_ICON,
  RECEIPT_LONG_ICON,
  ROUTER_ICON,
  SECTIONS_ICON,
  SHIELD_ICON,
  TEMPLATES_ICON,
  UPLOAD_FILE_ICON,
  VPN_LOCK_ICON,
} from '../icons'

/*
 * The destinations of the side navigation, one rail per workspace. Moderation is grouped by the job a moderator does
 * (Today, Work, Players, Protection); Administration (ADMIN only) by Configuration, Review and System. Labels are glossary
 * terms; the workspace decides which rail is shown, the router guard decides access. Every destination has its own icon.
 */

export type WorkspaceKey = 'moderation' | 'administration'

export interface NavDestination {
  to: string
  /** Last part of the translation key `nav.<key>`. */
  key: string
  /** Material Symbols-style path (24dp); decorative, the text labels the item. */
  icon: string
  /** The open-work count shown as a badge. */
  count?: CountKey
  /** One of the five top destinations: the compact bar shows the first four, the medium rail all five. */
  primary?: boolean
}

export interface NavGroup {
  /** Last part of `nav.groups.<key>`. */
  key: string
  /** Whether the group is introduced by a heading. */
  heading: boolean
  items: NavDestination[]
}

export const MODERATION_GROUPS: NavGroup[] = [
  {
    key: 'today',
    heading: true,
    items: [{ to: '/', key: 'inbox', count: 'inbox', icon: INBOX_ICON, primary: true }],
  },
  {
    key: 'work',
    heading: true,
    items: [
      { to: '/cases', key: 'cases', count: 'cases', icon: FOLDER_OPEN_ICON, primary: true },
      { to: '/appeals', key: 'appeals', count: 'appeals', icon: GAVEL_ICON, primary: true },
      { to: '/reports', key: 'reports', count: 'reports', icon: FLAG_ICON, primary: true },
    ],
  },
  {
    key: 'players',
    heading: true,
    items: [
      { to: '/players', key: 'playerLookup', icon: PERSON_SEARCH_ICON, primary: true },
      { to: '/punished', key: 'activePunishments', icon: BLOCK_ICON },
    ],
  },
  {
    key: 'protection',
    heading: true,
    items: [
      { to: '/guard', key: 'joinGuard', icon: SHIELD_ICON },
      { to: '/area-bans', key: 'areaBans', icon: MAP_ICON },
      { to: '/ip-bans', key: 'ipBans', icon: ROUTER_ICON },
      { to: '/intel/allow-list', key: 'allowList', icon: PLAYLIST_ADD_CHECK_ICON },
      { to: '/intel/policy', key: 'vpnPolicy', icon: VPN_LOCK_ICON },
    ],
  },
]

export const ADMINISTRATION_GROUPS: NavGroup[] = [
  {
    key: 'configuration',
    heading: true,
    items: [
      { to: '/admin/templates', key: 'templates', icon: TEMPLATES_ICON, primary: true },
      { to: '/admin/sections', key: 'sections', icon: SECTIONS_ICON, primary: true },
      { to: '/admin/client-rules', key: 'clientRules', icon: CLIENT_RULES_ICON, primary: true },
      { to: '/admin/appeal-terms', key: 'appealTerms', icon: APPEAL_CONDITIONS_ICON, primary: true },
    ],
  },
  {
    key: 'review',
    heading: true,
    items: [{ to: '/admin/exemption-reviews', key: 'exemptionReviews', icon: FACT_CHECK_ICON }],
  },
  {
    key: 'system',
    heading: true,
    items: [
      { to: '/admin/staff', key: 'staffAccounts', icon: MANAGE_ACCOUNTS_ICON, primary: true },
      { to: '/admin/rollback', key: 'rollback', icon: HISTORY_ICON },
      { to: '/admin/imports', key: 'imports', icon: UPLOAD_FILE_ICON },
      { to: '/admin/cloudnet', key: 'cloudnet', icon: CLOUD_ICON },
      { to: '/admin/kick', key: 'kick', icon: PERSON_REMOVE_ICON },
      { to: '/admin/audit', key: 'audit', icon: RECEIPT_LONG_ICON },
    ],
  },
]

/** The chords `g` then a letter, outside text fields: the top destinations, in the order of the shortcut list. */
export const SHORTCUT_TARGETS: Record<string, string> = { i: '/', c: '/cases', a: '/appeals', r: '/reports', p: '/players' }

/** How many destinations the compact bar shows before "More". */
export const BAR_DESTINATION_COUNT = 4

const GROUPS_BY_WORKSPACE: Record<WorkspaceKey, NavGroup[]> = {
  moderation: MODERATION_GROUPS,
  administration: ADMINISTRATION_GROUPS,
}

export interface Access {
  hasAccess: boolean
  administrator: boolean
}

/** The groups of a workspace a viewer may use: nothing without access, and the administration rail for ADMIN only. */
export function visibleGroups(access: Access, workspace: WorkspaceKey): NavGroup[] {
  if (!access.hasAccess) return []
  if (workspace === 'administration' && !access.administrator) return []
  return GROUPS_BY_WORKSPACE[workspace]
}

/** Every destination of the groups, in order. */
export function flatDestinations(groups: NavGroup[]): NavDestination[] {
  return groups.flatMap((group) => group.items)
}

/** The top destinations of the groups, in order (see `primary`). */
export function primaryDestinations(groups: NavGroup[]): NavDestination[] {
  return flatDestinations(groups).filter((item) => item.primary)
}
