import type { CountKey } from './navCounts'

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
    items: [{ to: '/', key: 'dashboard', icon: 'M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z' }],
  },
  {
    key: 'moderation',
    heading: true,
    items: [
      { to: '/cases', key: 'cases', count: 'cases', icon: 'M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z' },
      { to: '/appeals', key: 'appeals', count: 'appeals', icon: 'M14.4 6L14 4H5v17h2v-7h5.6l.4 2h7V6z' },
      { to: '/reports', key: 'reports', count: 'reports', icon: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z' },
      {
        to: '/punished',
        key: 'punished',
        icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z',
      },
      {
        to: '/players',
        key: 'players',
        icon: 'M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z',
      },
    ],
  },
  {
    key: 'rules',
    heading: true,
    items: [
      {
        to: '/sections',
        key: 'sections',
        icon: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z',
      },
      {
        to: '/templates',
        key: 'templates',
        icon: 'M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
      },
    ],
  },
  {
    key: 'system',
    heading: true,
    items: [
      {
        to: '/admin',
        key: 'admin',
        admin: true,
        icon: 'M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 4c1.66 0 3 1.34 3 3s-1.34 3-3 3-3-1.34-3-3 1.34-3 3-3zm0 14.3c-2.5-.8-4.5-2.9-5.4-5.3 1-1.6 3.4-2.5 5.4-2.5s4.4.9 5.4 2.5c-.9 2.4-2.9 4.5-5.4 5.3z',
      },
    ],
  },
]

/** The groups a viewer may use: nothing without access, no admin entry for other roles, and no empty group. */
export function visibleGroups(access: { hasAccess: boolean; administrator: boolean }): NavGroup[] {
  if (!access.hasAccess) return []
  return NAV_GROUPS.map((group) => ({ ...group, items: group.items.filter((item) => !item.admin || access.administrator) })).filter(
    (group) => group.items.length > 0,
  )
}
