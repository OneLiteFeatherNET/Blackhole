import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { RouterView, type Router } from 'vue-router'
import type { Report } from '../api/types'
import { mockApi, mountWithApp, ok } from '../test/mount'
import { fakeMatchMedia } from '../test/media'

/** The router view of the app: the reports list, and the report of `/reports/:id` in the same route. */
const Shell = defineComponent({ setup: () => () => h(RouterView) })

const FIRST = '3f2b8c1e-0000-4000-8000-000000000001'
const SECOND = '3f2b8c1e-0000-4000-8000-000000000002'

function report(identifier: string): Report {
  return {
    identifier,
    reporterHash: 'ef'.repeat(64),
    reportedHash: 'cd'.repeat(64),
    category: 'CHEATING',
    description: 'Flying around the spawn',
    status: 'OPEN',
    evidenceReferences: [],
    createdAt: Date.UTC(2030, 0, 2, 10, 0),
    updatedAt: Date.UTC(2030, 0, 2, 10, 0),
    verified: false,
    metaData: {},
  } as Report
}

let mounted: VueWrapper[] = []

afterEach(() => {
  mounted.forEach((wrapper) => wrapper.unmount())
  mounted = []
  vi.unstubAllGlobals()
  document.body.innerHTML = ''
})

async function setup(options: { path?: string; width?: number } = {}) {
  vi.stubGlobal('matchMedia', fakeMatchMedia({ value: options.width ?? 1000 }).matchMedia)
  const api = {
    reports: vi.fn(async () => ok([report(FIRST), report(SECOND)])),
    report: vi.fn(async (id: string) => ok(report(id))),
    reportsAgainst: vi.fn(async () => ok([])),
    profile: vi.fn(async () => ok({ active: {}, history: [] })),
    evidenceItem: vi.fn(),
    templates: vi.fn(async () => ok([])),
    sections: vi.fn(async () => ok([])),
  }
  const { wrapper, router } = await mountWithApp(Shell, { api: mockApi(api), path: options.path ?? '/reports?show=all' })
  mounted.push(wrapper)
  return { api, wrapper, router: router as Router }
}

async function press(key: string): Promise<void> {
  document.body.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
  await flushPromises()
}

describe('ReportsView: the URL names the open report', () => {
  it('opens a report at its own address and keeps the list query', async () => {
    const { wrapper, router } = await setup()

    await wrapper.get(`[data-testid="details-${SECOND}"]`).trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe(`/reports/${SECOND}?show=all`)
    expect(wrapper.get(`#report-row-${SECOND}`).attributes('aria-current')).toBe('true')
  })

  it('does not request the list again when another report is opened', async () => {
    const { api, wrapper } = await setup({ path: `/reports/${FIRST}?show=all` })
    expect(api.reports).toHaveBeenCalledTimes(1)

    await wrapper.get(`[data-testid="details-${SECOND}"]`).trigger('click')
    await flushPromises()

    expect(api.report).toHaveBeenLastCalledWith(SECOND)
    expect(api.reports).toHaveBeenCalledTimes(1)
  })
})

describe('ReportsView: widths and keys', () => {
  it('shows the list beside the report at expanded width', async () => {
    const { wrapper } = await setup({ path: `/reports/${FIRST}`, width: 1000 })

    expect(wrapper.get('[data-testid="list-pane"]').isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="detail-pane"]').isVisible()).toBe(true)
  })

  it('shows the report in place of the list at medium width', async () => {
    const { wrapper } = await setup({ path: `/reports/${FIRST}`, width: 700 })

    expect(wrapper.get('[data-testid="list-pane"]').isVisible()).toBe(false)
  })

  it('moves to the next row with j and opens it with Enter', async () => {
    const { wrapper, router } = await setup({ path: '/reports', width: 1000 })

    await press('j')
    await press('Enter')

    expect(router.currentRoute.value.fullPath).toBe(`/reports/${SECOND}`)
    expect(wrapper.get(`#report-row-${SECOND}`).attributes('aria-current')).toBe('true')
  })

  it('closes the report with Escape and returns to the list address', async () => {
    const { router } = await setup({ path: `/reports/${FIRST}?show=all`, width: 1000 })

    await press('Escape')

    expect(router.currentRoute.value.fullPath).toBe('/reports?show=all')
  })
})
