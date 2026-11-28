import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { RouterView, type Router } from 'vue-router'
import type { CaseListItem } from '../api/types'
import { caseResponse, timelineItem, timelinePage } from '../test/cases'
import { mockApi, mountWithApp, ok } from '../test/mount'
import { fakeMatchMedia } from '../test/media'

/** The router view of the app: it renders the cases list, and the case of `/cases/:reference` in the same route. */
const Shell = defineComponent({ setup: () => () => h(RouterView) })

function item(id: string, overrides: Partial<CaseListItem> = {}): CaseListItem {
  return {
    punishmentIdentifier: id,
    shortId: `#${id.toUpperCase()}`,
    section: 'network',
    punishmentState: 'ACTIVE',
    status: 'OPEN',
    priority: 'NORMAL',
    tags: [],
    punishmentCreatedAt: Date.UTC(2026, 6, 1),
    lastActivityAt: Date.UTC(2026, 6, 2),
    playerName: null,
    ownerShort: 'abababababab',
    version: 1,
    ...overrides,
  }
}

const ROWS = [item('p-1'), item('p-2'), item('p-3')]

let mounted: VueWrapper[] = []

afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount())
  mounted = []
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

/** Mounts the app shell at `path` with the given window width (in dp). Nothing waits on a timer. */
async function setup(options: { path?: string; width?: number } = {}) {
  vi.stubGlobal('matchMedia', fakeMatchMedia({ value: options.width ?? 1000 }).matchMedia)
  const api = {
    cases: vi.fn(async () => ok({ items: ROWS, page: 0, size: 25, totalSize: ROWS.length, totalPages: 1 })),
    caseDetail: vi.fn(async (reference: string) => ok(caseResponse({ punishmentIdentifier: reference }))),
    caseTimeline: vi.fn(async () => ok(timelinePage([timelineItem()]))),
    caseConversation: vi.fn(async () => ok([])),
    caseProtocols: vi.fn(async () => ok([])),
    caseDecidedAppeals: vi.fn(async () => ok([])),
    openCaseEvents: () => ({ close() {} }),
  }
  const { wrapper, router } = await mountWithApp(Shell, { api: mockApi(api), path: options.path ?? '/cases?status=OPEN' })
  mounted.push(wrapper)
  return { api, wrapper, router: router as Router }
}

/** Presses a key; the list listens on the document, as in the app. */
async function press(key: string, target: EventTarget = document.body): Promise<void> {
  target.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  await flushPromises()
}

const focusedRowId = () => document.activeElement?.id ?? ''

describe('CasesView: the URL names the open case', () => {
  it('opens the case of a clicked row at its own address and keeps the list query', async () => {
    const { wrapper, router } = await setup()

    await wrapper.get('#case-row-p-2 a').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/cases/p-2?status=OPEN')
  })

  it('returns to the list without the item when Back is pressed', async () => {
    const { wrapper, router } = await setup()
    await wrapper.get('#case-row-p-2 a').trigger('click')
    await flushPromises()

    router.back()
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/cases?status=OPEN')
    expect(wrapper.find('[aria-current="true"]').exists()).toBe(false)
  })

  it('opens the case named by a shared address, with the list behind it', async () => {
    const { api, wrapper } = await setup({ path: '/cases/p-3?status=OPEN', width: 1000 })

    expect(api.caseDetail).toHaveBeenCalledWith('p-3')
    expect(wrapper.get('#case-row-p-3').attributes('aria-current')).toBe('true')
  })
})

describe('CasesView: changing the open case', () => {
  it('loads only the new case when another row is opened, not the list again', async () => {
    const { api, wrapper, router } = await setup({ path: '/cases/p-1?status=OPEN' })
    expect(api.cases).toHaveBeenCalledTimes(1)

    await wrapper.get('#case-row-p-2 a').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/cases/p-2?status=OPEN')
    expect(api.caseDetail).toHaveBeenLastCalledWith('p-2')
    expect(api.cases).toHaveBeenCalledTimes(1)
  })

  it('marks only the open row with aria-current', async () => {
    const { wrapper } = await setup({ path: '/cases/p-2?status=OPEN' })

    expect(wrapper.get('#case-row-p-2').attributes('aria-current')).toBe('true')
    expect(wrapper.get('#case-row-p-1').attributes('aria-current')).toBeUndefined()
    expect(wrapper.get('#case-row-p-3').attributes('aria-current')).toBeUndefined()
  })
})

describe('CasesView: nothing open at expanded width', () => {
  it('says what to select and requests no case', async () => {
    const { api, wrapper } = await setup({ path: '/cases', width: 1000 })

    expect(wrapper.get('[data-testid="detail-placeholder"]').text()).toBe('Select a case to see it here.')
    expect(api.caseDetail).not.toHaveBeenCalled()
  })
})

describe('CasesView: widths', () => {
  it('shows the list and the case side by side at expanded width', async () => {
    const { wrapper } = await setup({ path: '/cases/p-1?status=OPEN', width: 1000 })

    expect(wrapper.get('[data-testid="list-pane"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="detail-pane"]').isVisible()).toBe(true)
  })

  it('shows the case in place of the list at medium width', async () => {
    const { wrapper } = await setup({ path: '/cases/p-1?status=OPEN', width: 700 })

    expect(wrapper.get('[data-testid="list-pane"]').isVisible()).toBe(false)
    expect(wrapper.get('[data-testid="detail-pane"]').isVisible()).toBe(true)
  })

  it('shows the case as a page of its own at compact width', async () => {
    const { api, wrapper } = await setup({ path: '/cases/p-1?status=OPEN', width: 400 })

    expect(wrapper.get('[data-testid="list-pane"]').isVisible()).toBe(false)
    expect(api.caseDetail).toHaveBeenCalledWith('p-1')
  })

  it('shows the list alone at compact width while no case is open', async () => {
    const { wrapper } = await setup({ path: '/cases', width: 400 })

    expect(wrapper.get('[data-testid="list-pane"]').isVisible()).toBe(true)
    expect(wrapper.find('[data-testid="detail-pane"]').exists()).toBe(false)
  })
})

describe('CasesView: keyboard', () => {
  it('moves the row with j and k, and opens the row with Enter', async () => {
    const { api, wrapper, router } = await setup({ path: '/cases?status=OPEN', width: 1000 })
    await flushPromises()

    await press('j')
    expect(focusedRowId()).toBe('case-row-p-2')
    await press('j')
    expect(focusedRowId()).toBe('case-row-p-3')
    await press('k')
    expect(focusedRowId()).toBe('case-row-p-2')

    await press('Enter')
    expect(router.currentRoute.value.fullPath).toBe('/cases/p-2?status=OPEN')
    expect(api.caseDetail).toHaveBeenLastCalledWith('p-2')
    expect(wrapper.get('#case-row-p-2').attributes('aria-current')).toBe('true')
  })

  it('closes the case with Escape and returns the focus to its row', async () => {
    const { router } = await setup({ path: '/cases/p-3?status=OPEN', width: 1000 })

    await press('Escape')

    expect(router.currentRoute.value.fullPath).toBe('/cases?status=OPEN')
    expect(focusedRowId()).toBe('case-row-p-3')
  })

  it('does not act on the list keys while a text field has the focus', async () => {
    const { router } = await setup({ path: '/cases/p-1?status=OPEN', width: 1000 })
    const input = document.createElement('input')
    input.type = 'text'
    document.body.append(input)

    await press('Escape', input)
    await press('j', input)

    expect(router.currentRoute.value.fullPath).toBe('/cases/p-1?status=OPEN')
    expect(focusedRowId()).not.toBe('case-row-p-2')
    input.remove()
  })

  it('does not act while a dialog is open', async () => {
    const { router } = await setup({ path: '/cases/p-1?status=OPEN', width: 1000 })
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    document.body.append(dialog)

    await press('Escape')

    expect(router.currentRoute.value.fullPath).toBe('/cases/p-1?status=OPEN')
    dialog.remove()
  })
})
