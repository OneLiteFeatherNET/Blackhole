import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, RouterView } from 'vue-router'
import { defineComponent, h } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { Appeal, ApiResult, CaseTimelinePage } from '../api/types'
import { installAuthGuard } from '../router/authGuard'
import { createAppRouter } from '../router'
import { useSessionStore } from '../stores/session'
import { caseResponse, timelineItem, timelinePage } from '../test/cases'
import { evidencePage } from '../test/evidence'
import { fakeAuthService } from '../test/auth'
import { mockApi, mountWithApp, ok } from '../test/mount'
import { chooseOption } from '../test/select'

const ID = '3f2b8c1e-0000-4000-8000-0000000000a1'
const PUNISHMENT = 'punishment-0001'
const APPELLANT = 'ab'.repeat(64)
const STATEMENT = 'It was not me, the spawn was empty'

function appeal(overrides: Partial<Appeal> = {}): Appeal {
  return {
    identifier: ID,
    punishmentIdentifier: PUNISHMENT,
    appellantHash: APPELLANT,
    statement: STATEMENT,
    status: 'ELIGIBLE_PENDING_REVIEW',
    eligibilityCheckResult: {},
    verified: true,
    evidenceCount: 2,
    createdAt: Date.UTC(2030, 0, 2, 10, 0),
    updatedAt: Date.UTC(2030, 0, 2, 10, 0),
    metaData: {},
    ...overrides,
  } as Appeal
}

// The router's root: the real route table renders the appeal page and, below it, its decision child route.
const Root = defineComponent(() => () => h(RouterView))

function setup(options: { appeal?: ApiResult<Appeal>; roles?: string[]; last?: CaseTimelinePage; extra?: Record<string, unknown> } = {}) {
  const api = {
    appeal: vi.fn(async () => options.appeal ?? ok(appeal())),
    caseDetail: vi.fn(async () => ok(caseResponse({ punishmentIdentifier: PUNISHMENT }))),
    caseTimeline: vi.fn(async () => ok(options.last ?? timelinePage([]))),
    reviewAppeal: vi.fn(async () => ok(appeal({ status: 'DENIED' }))),
    // The decision dialog lists the evidence of the punishment it decides on.
    evidence: vi.fn(async () => ok(evidencePage([]))),
    evidenceCounts: vi.fn(async () => ok({ count: 0, playerSupplied: 0 })),
    ...options.extra,
  }
  return {
    api,
    mount: (path = `/appeals/${ID}`) => mountWithApp(Root, { api: mockApi(api), path, roles: options.roles ?? ['MODERATOR', 'VIEWER'] }),
  }
}

type Wrapper = VueWrapper

function dialog(wrapper: Wrapper) {
  return wrapper.find('[role="dialog"]')
}

describe('AppealView: page', () => {
  it('shows the appeal statement and its state as text', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain(STATEMENT)
    expect(wrapper.get('[data-testid="state"]').text()).toBe('Awaiting review')

    wrapper.unmount()
  })

  it('shows the appeal title as the only h1 and keeps the punishment identifier out of the page', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.findAll('h1').map((heading) => heading.text())).toEqual(['Appeal'])
    expect(wrapper.text()).not.toContain(PUNISHMENT)

    wrapper.unmount()
  })

  it('reads the appeal by its identifier and the case of its punishment, nothing else', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(api.appeal).toHaveBeenCalledWith(ID)
    expect(api.caseDetail).toHaveBeenCalledWith(PUNISHMENT)
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('links the appellant to the player page by hash, not by name', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get(`a[href="/players/${APPELLANT}"]`).text()).toBe(`${APPELLANT.slice(0, 8)}…`)

    wrapper.unmount()
  })

  it('shows the breadcrumbs back to the appeals list', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const crumbs = wrapper.findAll('nav[aria-label="Breadcrumbs"] a')
    expect(crumbs.map((crumb) => crumb.text())).toContain('Appeals')
    expect(crumbs.find((crumb) => crumb.text() === 'Appeals')!.attributes('href')).toBe('/appeals')

    wrapper.unmount()
  })
})

describe('AppealView: tabs', () => {
  it('opens the decision tab when the address names no tab', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="decision-panel"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="case-panel"]').isVisible()).toBe(false)

    wrapper.unmount()
  })

  it('opens the tab named in the query and keeps it on reload', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(`/appeals/${ID}?tab=case`)

    expect(wrapper.get('[data-testid="case-panel"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="decision-panel"]').isVisible()).toBe(false)

    wrapper.unmount()
  })

  it('falls back to the decision tab for an unknown tab name', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(`/appeals/${ID}?tab=unknown`)

    expect(wrapper.get('[data-testid="decision-panel"]').isVisible()).toBe(true)

    wrapper.unmount()
  })

  it('says the player wrote last and links to the case conversation', async () => {
    const { api, mount } = setup({ last: timelinePage([timelineItem({ actor: { trust: 'PLAYER' } as never })]) })
    const { wrapper } = await mount(`/appeals/${ID}?tab=conversation`)

    expect(api.caseTimeline).toHaveBeenCalledWith(PUNISHMENT, { types: ['message.added'], order: 'desc', size: 1 })
    expect(wrapper.get('[data-testid="awaiting-answer"]').text()).toBe('The player wrote last. Answer in the case conversation.')
    expect(wrapper.get('[data-testid="open-conversation"]').attributes('href')).toBe(`/cases/${PUNISHMENT}?tab=conversation`)

    wrapper.unmount()
  })

  it('says that no message exists yet when the conversation is empty', async () => {
    const { mount } = setup({ last: timelinePage([]) })
    const { wrapper } = await mount(`/appeals/${ID}?tab=conversation`)

    expect(wrapper.get('[data-testid="conversation-state"]').text()).toBe('No message has been written yet.')

    wrapper.unmount()
  })

  it('links the case of the punishment with its short id on the case tab', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(`/appeals/${ID}?tab=case`)

    expect(wrapper.get('[data-testid="case-panel"] a').attributes('href')).toBe(`/cases/${PUNISHMENT}`)
    expect(wrapper.get('[data-testid="case-panel"]').text()).toContain('#AB12CD')

    wrapper.unmount()
  })
})

describe('AppealView: decision', () => {
  it('offers the decide action to a moderator for a pending appeal', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount()

    expect(wrapper.find('[data-testid="decide"]').exists()).toBe(true)

    wrapper.unmount()
  })

  it('opens the decision dialog from the decide action and the route', async () => {
    const { mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="decide"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('appeal-decide')
    expect(dialog(wrapper).text()).toContain('Decide appeal')

    wrapper.unmount()
  })

  it('hides the decide action and the dialog from a viewer, also on the decide address', async () => {
    const { api, mount } = setup({ roles: ['VIEWER'] })
    const { wrapper } = await mount(`/appeals/${ID}/decide`)

    expect(wrapper.find('[data-testid="decide"]').exists()).toBe(false)
    expect(dialog(wrapper).exists()).toBe(false)
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('hides the decide action once the appeal is decided and opens no dialog on its address', async () => {
    const { api, mount } = setup({ appeal: ok(appeal({ status: 'DENIED', decisionNote: 'Checked the logs' })) })
    const { wrapper } = await mount(`/appeals/${ID}/decide`)

    expect(wrapper.find('[data-testid="decide"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('This appeal is already decided.')
    expect(dialog(wrapper).exists()).toBe(false)
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('sends nothing when the decide address is opened or reloaded', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper } = await mount(`/appeals/${ID}/decide`)

    expect(dialog(wrapper).text()).toContain('Decide appeal')
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('closes the dialog with Cancel and leaves the decision unsent', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper, router } = await mount()

    await wrapper.get('[data-testid="decide"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="cancel"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('appeal')
    expect(dialog(wrapper).exists()).toBe(false)
    expect(api.reviewAppeal).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('sends the decision once and reads the appeal again afterwards', async () => {
    const { api, mount } = setup({ roles: ['MODERATOR', 'VIEWER'] })
    const { wrapper, router } = await mount(`/appeals/${ID}/decide`)

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DENIED')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.reviewAppeal).toHaveBeenCalledTimes(1)
    expect(api.reviewAppeal).toHaveBeenCalledWith(ID, expect.objectContaining({ decision: 'DENIED' }))
    expect(api.appeal).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.name).toBe('appeal')

    wrapper.unmount()
  })
})

describe('AppealView: states', () => {
  it('shows the not-found text and no appeal data for an unknown identifier', async () => {
    const { api, mount } = setup({ appeal: { ok: false, error: { status: 404, message: 'there is no appeal', kind: 'NOT_FOUND' } } })
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('There is no such appeal.')
    expect(wrapper.text()).not.toContain(STATEMENT)
    expect(wrapper.find('[data-testid="decide"]').exists()).toBe(false)
    expect(api.caseDetail).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('links back to the appeals from the not-found text', async () => {
    const { mount } = setup({ appeal: { ok: false, error: { status: 404, message: 'there is no appeal', kind: 'NOT_FOUND' } } })
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="appeal-back"]').attributes('href')).toBe('/appeals')

    wrapper.unmount()
  })

  it('shows no appeal data when the read is refused with a 403', async () => {
    const { api, mount } = setup({ appeal: { ok: false, error: { status: 403, message: 'Forbidden', kind: 'FORBIDDEN' } } })
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('You are not permitted to do this')
    expect(wrapper.text()).not.toContain(STATEMENT)
    expect(api.caseDetail).not.toHaveBeenCalled()

    wrapper.unmount()
  })
})

describe('AppealView: authorization', () => {
  // The route guard decides who reaches the page at all. These tests run the real guard in front of the real route table.
  function guarded(options: { token: string | null; roles?: string[] }) {
    const router = createAppRouter(createMemoryHistory())
    const pinia = createPinia()
    const session = useSessionStore(pinia)
    if (options.roles) session.me = { oid: '00000000-0000-4000-8000-0000000000a1', displayName: 'Mira', roles: options.roles } as never
    const { service, calls } = fakeAuthService({ token: options.token })
    const api = mockApi({ me: vi.fn(async () => ok({ oid: '', roles: [] })), appeal: vi.fn(async () => ok(appeal())) })
    installAuthGuard(router, { auth: service, session, api })
    return { router, calls, api }
  }

  it('sends an anonymous visitor to sign-in and keeps the appeal address', async () => {
    const { router, calls, api } = guarded({ token: null })

    await router.push(`/appeals/${ID}`)

    expect(calls.signIn).toEqual([`/appeals/${ID}`])
    expect(router.currentRoute.value.name).not.toBe('appeal')
    expect(api.appeal).not.toHaveBeenCalled()
  })

  it('keeps the decide address behind sign-in too', async () => {
    const { router, calls } = guarded({ token: null })

    await router.push(`/appeals/${ID}/decide`)

    expect(calls.signIn).toEqual([`/appeals/${ID}/decide`])
  })

  it('shows the no-access page, not the appeal, to a signed-in caller without a role', async () => {
    const { router, api } = guarded({ token: 'token', roles: [] })

    await router.push(`/appeals/${ID}`)

    expect(router.currentRoute.value.name).toBe('no-access')
    expect(api.appeal).not.toHaveBeenCalled()
  })

  it('opens the appeal page for a viewer', async () => {
    const { router, api } = guarded({ token: 'token', roles: ['VIEWER'] })

    await router.push(`/appeals/${ID}`)

    expect(router.currentRoute.value.name).toBe('appeal')
    expect(router.currentRoute.value.params.id).toBe(ID)
    expect(api.me).not.toHaveBeenCalled()
  })
})

describe('AppealView: routes', () => {
  it('names the appeal page and its decision child route', () => {
    const router = createAppRouter(createMemoryHistory())

    expect(router.resolve(`/appeals/${ID}`).name).toBe('appeal')
    expect(router.resolve(`/appeals/${ID}/decide`).name).toBe('appeal-decide')
    expect(router.resolve(`/appeals/${ID}/decide`).params.id).toBe(ID)
  })
})
