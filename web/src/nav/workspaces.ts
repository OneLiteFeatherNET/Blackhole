import { visibleGroups, type Access, type WorkspaceKey } from './navigation'

/*
 * The two workspaces of the panel. The URL decides which workspace a page is in (each route declares
 * `meta.workspace`); the last one used is kept in localStorage for convenience only and is read and written
 * inside try/catch, because storage may be blocked or may throw.
 */

export const LAST_WORKSPACE_STORAGE_KEY = 'blackhole.workspace'

export interface Workspace {
  key: WorkspaceKey
  /** Where the switcher and the bare sign-in land. */
  home: string
  /** Label key `workspace.<key>`. */
  label: string
  /** Offered to ADMIN only. */
  admin: boolean
}

export const WORKSPACES: Workspace[] = [
  { key: 'moderation', home: '/', label: 'workspace.moderation', admin: false },
  { key: 'administration', home: '/admin/templates', label: 'workspace.administration', admin: true },
]

/** The workspaces a viewer may switch to: Moderation for every role with access, Administration for ADMIN. */
export function visibleWorkspaces(access: Access): Workspace[] {
  return WORKSPACES.filter((workspace) => visibleGroups(access, workspace.key).length > 0)
}

export function workspaceByKey(key: WorkspaceKey): Workspace {
  const workspace = WORKSPACES.find((candidate) => candidate.key === key)
  if (!workspace) throw new Error(`unknown workspace ${key}`)
  return workspace
}

/**
 * A breadcrumb trail with the workspace in front, when the switcher is shown (ADMIN); without it the trail is unchanged,
 * because a single workspace would only add noise. The workspace crumb links to its home.
 */
export function withWorkspace(
  items: { label: string; to?: string }[],
  options: { show: boolean; workspace: WorkspaceKey; translate: (key: string) => string },
): { label: string; to?: string }[] {
  if (!options.show) return items
  const workspace = workspaceByKey(options.workspace)
  return [{ label: options.translate(workspace.label), to: workspace.home }, ...items]
}

/** The last workspace the viewer used, or null when none is stored or storage is blocked. */
export function readStoredWorkspace(): WorkspaceKey | null {
  try {
    const value = localStorage.getItem(LAST_WORKSPACE_STORAGE_KEY)
    return value === 'moderation' || value === 'administration' ? value : null
  } catch {
    return null
  }
}

/** Remembers the workspace for the next visit; a blocked storage is ignored. */
export function storeWorkspace(key: WorkspaceKey): void {
  try {
    localStorage.setItem(LAST_WORKSPACE_STORAGE_KEY, key)
  } catch {
    // Convenience only: the URL is the source of truth.
  }
}
