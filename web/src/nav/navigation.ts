import type { CountKey } from './navCounts'
import {
  ADMIN_ICON,
  APPEAL_CONDITIONS_ICON,
  APPEALS_ICON,
  CASES_ICON,
  CLIENT_RULES_ICON,
  DASHBOARD_ICON,
  PLAYERS_ICON,
  PUNISHED_ICON,
  REPORTS_ICON,
  SECTIONS_ICON,
  TEMPLATES_ICON,
} from '../icons'

/* The destinations of the side navigation, grouped. Which of them a viewer sees follows the role matrix. */

export interface NavDestination {
  to: string
  /** Last part of the translation key `nav.<key>`. */
  key: string
  /** Material Symbols-style path (24dp); decorative, the text labels the item. */
  icon: string
  /** Offered to ADMIN only; the backend stays authoritative. */
  admin?: boolean
  /** The open-work count shown as a badge. */
  count?: CountKey
}

export interface NavGroup {
  /** Last part of `nav.groups.<key>`. */
  key: string
  /** Whether the group is introduced by a heading; the overview stands alone. */
  heading: boolean
  items: NavDestination[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'overview',
    heading: false,
    items: [{ to: '/', key: 'dashboard', icon: DASHBOARD_ICON }],
  },
  {
    key: 'moderation',
    heading: true,
    items: [
      { to: '/cases', key: 'cases', count: 'cases', icon: CASES_ICON },
      { to: '/appeals', key: 'appeals', count: 'appeals', icon: APPEALS_ICON },
      { to: '/reports', key: 'reports', count: 'reports', icon: REPORTS_ICON },
      { to: '/punished', key: 'punished', icon: PUNISHED_ICON },
      { to: '/players', key: 'players', icon: PLAYERS_ICON },
    ],
  },
  {
    key: 'rules',
    heading: true,
    items: [
      { to: '/sections', key: 'sections', icon: SECTIONS_ICON },
      { to: '/templates', key: 'templates', icon: TEMPLATES_ICON },
      { to: '/appeal-conditions', key: 'appealConditions', icon: APPEAL_CONDITIONS_ICON },
      { to: '/client-rules', key: 'clientRules', icon: CLIENT_RULES_ICON },
    ],
  },
  {
    key: 'system',
    heading: true,
    items: [{ to: '/admin', key: 'admin', admin: true, icon: ADMIN_ICON }],
  },
]

/** The groups a viewer may use: nothing without access, no admin entry for other roles, and no empty group. */
export function visibleGroups(access: { hasAccess: boolean; administrator: boolean }): NavGroup[] {
  if (!access.hasAccess) return []
  return NAV_GROUPS.map((group) => ({ ...group, items: group.items.filter((item) => !item.admin || access.administrator) })).filter(
    (group) => group.items.length > 0,
  )
}
