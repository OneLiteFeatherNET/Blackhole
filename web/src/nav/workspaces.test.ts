import { describe, expect, it } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import { createAppRouter } from '../router'
import { visibleWorkspaces, WORKSPACES } from './workspaces'

const ADMIN = { hasAccess: true, administrator: true }
const MODERATOR = { hasAccess: true, administrator: false }
const VIEWER = { hasAccess: true, administrator: false }

describe('workspaces', () => {
  it('defines Moderation first and Administration second, with their homes', () => {
    expect(WORKSPACES.map((workspace) => [workspace.key, workspace.home])).toEqual([
      ['moderation', '/'],
      ['administration', '/admin/templates'],
    ])
  })

  it('offers both workspaces to an administrator', () => {
    expect(visibleWorkspaces(ADMIN).map((workspace) => workspace.key)).toEqual(['moderation', 'administration'])
  })

  it.each([['moderator', MODERATOR], ['viewer', VIEWER]])('offers only Moderation to a %s', (_role, access) => {
    expect(visibleWorkspaces(access).map((workspace) => workspace.key)).toEqual(['moderation'])
  })

  it('offers no workspace without access', () => {
    expect(visibleWorkspaces({ hasAccess: false, administrator: false })).toEqual([])
  })

  it('gives every route the workspace it belongs to', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve('/cases/CASE-1').meta.workspace).toBe('moderation')
    expect(router.resolve('/punished').meta.workspace).toBe('moderation')
    expect(router.resolve('/admin/templates').meta.workspace).toBe('administration')
    expect(router.resolve('/admin/cloudnet').meta.workspace).toBe('administration')
  })
})
