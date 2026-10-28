import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { usePanelStack } from './usePanelStack'
import type { PanelToken } from './panelState'

const Page = defineComponent({ render: () => h('div') })

/** A fresh in-memory router per test, so no history or state is shared between tests. */
async function routerAt(path: string): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/cases', component: Page }],
  })
  await router.push(path)
  await router.isReady()
  return router
}

const rule: PanelToken = { kind: 'client-rule', target: 'new' }
const evidence: PanelToken = { kind: 'evidence', target: 'CASE-12' }
const link: PanelToken = { kind: 'link', target: 'new' }

describe('usePanelStack', () => {
  it('opening a sheet pushes a history entry, so the browser back button closes it', async () => {
    const router = await routerAt('/cases')
    const push = vi.spyOn(router, 'push')
    const stack = usePanelStack({ router })

    await stack.open(rule)

    expect(push, 'a history entry is pushed').toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.query.panel).toBe('client-rule:new')
  })

  it('closing the top sheet replaces the history entry instead of pushing one', async () => {
    const router = await routerAt('/cases')
    const stack = usePanelStack({ router })
    await stack.open(rule)
    const push = vi.spyOn(router, 'push')
    const replace = vi.spyOn(router, 'replace')

    await stack.close()

    expect(replace, 'the entry is replaced').toHaveBeenCalledTimes(1)
    expect(push, 'nothing is pushed').not.toHaveBeenCalled()
    expect(router.currentRoute.value.query.panel).toBeUndefined()
  })

  it('back closes only the child sheet and keeps the parent open', async () => {
    const router = await routerAt('/cases')
    const stack = usePanelStack({ router })
    await stack.open(evidence)
    await stack.open(link)

    await stack.close()

    expect(stack.tokens.value).toEqual([evidence])
    expect(router.currentRoute.value.query.panel).toBe('evidence:CASE-12')
  })

  it('refuses a third sheet so the depth stays at two', async () => {
    const router = await routerAt('/cases')
    const stack = usePanelStack({ router })
    await stack.open(evidence)
    await stack.open(link)

    await stack.open(rule)

    expect(stack.tokens.value).toEqual([evidence, link])
  })

  it('keeps other query parameters such as filters when a sheet opens', async () => {
    const router = await routerAt('/cases?status=open')
    const stack = usePanelStack({ router })

    await stack.open(rule)

    expect(router.currentRoute.value.query.status).toBe('open')
    expect(router.currentRoute.value.query.panel).toBe('client-rule:new')
  })

  it('removes a sheet the role may not use from the URL, without any request', async () => {
    const api = { loadRule: vi.fn() }
    const router = await routerAt('/cases?panel=evidence:CASE-12')
    usePanelStack({ router, allowed: () => false })

    await flushPromises()

    expect(router.currentRoute.value.query.panel, 'the forbidden token is gone from the URL').toBeUndefined()
    expect(api.loadRule, 'no request was made for it').not.toHaveBeenCalled()
  })

  it('keeps the sheets the role may use when it removes a forbidden one', async () => {
    const router = await routerAt('/cases?panel=evidence:CASE-12,link:new')
    usePanelStack({ router, allowed: (token) => token.kind !== 'link' })

    await flushPromises()

    expect(router.currentRoute.value.query.panel).toBe('evidence:CASE-12')
  })
})
