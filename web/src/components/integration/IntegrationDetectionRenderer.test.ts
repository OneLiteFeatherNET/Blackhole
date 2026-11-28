import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { mountWithApp } from '../../test/mount'
import IntegrationDetectionRenderer from './IntegrationDetectionRenderer.vue'
import type { RendererLoader } from './registry'
import type { IntegrationDetection } from './types'

function detection(overrides: Partial<IntegrationDetection> = {}): IntegrationDetection {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    sourceSystem: 'vulcan',
    kind: 'DETECTION',
    schemaVersion: 1,
    occurredAt: '2026-11-02T14:32:00Z',
    server: 'survival-2',
    severity: 'HIGH',
    summary: 'Attack angle delta above threshold',
    payload: { check: 'KillAura', violationLevel: 14, ping: 48, tps: 19.8 },
    ...overrides,
  }
}

// A reviewed component stands in for a registered renderer; it is a render function, so no template compiler is needed.
const StubRenderer = defineComponent({
  props: { detection: { type: Object, required: true } },
  setup: () => () => h('p', { 'data-testid': 'stub-renderer' }, 'registered renderer'),
})

describe('IntegrationDetectionRenderer', () => {
  it('names the reporting integration and states that it is a signal, not a verdict', async () => {
    const { wrapper } = await mountWithApp(IntegrationDetectionRenderer, { props: { detection: detection() } })

    expect(wrapper.text()).toContain('Reported by vulcan')
    expect(wrapper.text()).toContain('A signal, not a verdict: staff decide.')

    wrapper.unmount()
  })

  it('renders an unregistered kind through the generic table with every payload field', async () => {
    const { wrapper } = await mountWithApp(IntegrationDetectionRenderer, { props: { detection: detection() } })

    const rows = wrapper.findAll('tbody tr').map((row) => row.findAll('th, td').map((cell) => cell.text()))
    expect(rows).toEqual([
      ['check', 'KillAura'],
      ['violationLevel', '14'],
      ['ping', '48'],
      ['tps', '19.8'],
    ])

    wrapper.unmount()
  })

  it('renders a registered kind with its component and not the generic table', async () => {
    const table: Record<string, RendererLoader> = {
      // A real dynamic import is a module namespace; Vue unwraps `default` only for module objects.
      'vulcan/DETECTION@1': () => Promise.resolve({ __esModule: true, default: StubRenderer }),
    }

    const { wrapper } = await mountWithApp(IntegrationDetectionRenderer, { props: { detection: detection(), table } })
    await flushPromises()

    expect(wrapper.find('[data-testid="stub-renderer"]').exists()).toBe(true)
    expect(wrapper.find('table').exists()).toBe(false)

    wrapper.unmount()
  })

  it('shows markup in a value as characters and creates no element from it', async () => {
    const hostile = detection({ payload: { check: 'Speed', detail: '<img src=x onerror=alert(1)>' } })

    const { wrapper } = await mountWithApp(IntegrationDetectionRenderer, { props: { detection: hostile } })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')

    wrapper.unmount()
  })

  it('shows a URL in the summary as text and not as a link', async () => {
    const withUrl = detection({ summary: 'see https://evil.example/payload' })

    const { wrapper } = await mountWithApp(IntegrationDetectionRenderer, { props: { detection: withUrl } })

    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.text()).toContain('see https://evil.example/payload')

    wrapper.unmount()
  })

  it('makes no request for a component, script or stylesheet while it renders', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    const createSpy = vi.spyOn(document, 'createElement')

    const { wrapper } = await mountWithApp(IntegrationDetectionRenderer, { props: { detection: detection() } })
    await flushPromises()

    expect(fetchSpy).not.toHaveBeenCalled()
    const created = createSpy.mock.calls.map(([tag]) => String(tag).toLowerCase())
    expect(created).not.toContain('script')
    expect(created).not.toContain('link')

    wrapper.unmount()
    fetchSpy.mockRestore()
    createSpy.mockRestore()
  })
})
