import type { CountKey } from './navCounts'
import {
  ADMIN_ICON,
  APPEAL_CONDITIONS_ICON,
  CHECK_ICON,
  APPEALS_ICON,
  CASES_ICON,
  CLIENT_RULES_ICON,
  INBOX_ICON,
  PLAYERS_ICON,
  PUNISHED_ICON,
  REPORTS_ICON,
  SECTIONS_ICON,
  TEMPLATES_ICON,
} from '../icons'

/*
 * The destinations of the side navigation, one rail per workspace (see the redesign design, "Decision 2026-10-25").
 * Moderation: the inbox first, without a heading, then Work and Players. Administration (ADMIN only): Configuration
 * and System. Labels are glossary terms; the workspace decides which rail is shown, the router guard decides access.
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
}

export interface NavGroup {
  /** Last part of `nav.groups.<key>`. */
  key: string
  /** Whether the group is introduced by a heading; the inbox stands alone. */
  heading: boolean
  items: NavDestination[]
}

export const MODERATION_GROUPS: NavGroup[] = [
  {
    key: 'home',
    heading: false,
    items: [{ to: '/', key: 'inbox', count: 'inbox', icon: INBOX_ICON }],
  },
  {
    key: 'work',
    heading: true,
    items: [
      { to: '/cases', key: 'cases', count: 'cases', icon: CASES_ICON },
      { to: '/appeals', key: 'appeals', count: 'appeals', icon: APPEALS_ICON },
      { to: '/reports', key: 'reports', count: 'reports', icon: REPORTS_ICON },
    ],
  },
  {
    key: 'players',
    heading: true,
    items: [
      { to: '/players', key: 'playerLookup', icon: PLAYERS_ICON },
      { to: '/punished', key: 'activePunishments', icon: PUNISHED_ICON },
      { to: '/intel/allow-list', key: 'allowList', icon: CHECK_ICON },
      { to: '/intel/policy', key: 'vpnPolicy', icon: ADMIN_ICON },
    ],
  },
]

export const ADMINISTRATION_GROUPS: NavGroup[] = [
  {
    key: 'configuration',
    heading: true,
    items: [
      { to: '/admin/templates', key: 'templates', icon: TEMPLATES_ICON },
      { to: '/admin/sections', key: 'sections', icon: SECTIONS_ICON },
      { to: '/admin/client-rules', key: 'clientRules', icon: CLIENT_RULES_ICON },
      { to: '/admin/appeal-terms', key: 'appealTerms', icon: APPEAL_CONDITIONS_ICON },
    ],
  },
  {
    key: 'review',
    heading: true,
    items: [{ to: '/admin/exemption-reviews', key: 'exemptionReviews', icon: PUNISHED_ICON }],
  },
  {
    key: 'system',
    heading: true,
    items: [
      { to: '/admin/staff', key: 'staffAccounts', icon: ADMIN_ICON },
      { to: '/admin/rollback', key: 'rollback', icon: ADMIN_ICON },
      { to: '/admin/imports', key: 'imports', icon: ADMIN_ICON },
      { to: '/admin/cloudnet', key: 'cloudnet', icon: ADMIN_ICON },
      { to: '/admin/kick', key: 'kick', icon: ADMIN_ICON },
      { to: '/admin/audit', key: 'audit', icon: ADMIN_ICON },
    ],
  },
]

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
