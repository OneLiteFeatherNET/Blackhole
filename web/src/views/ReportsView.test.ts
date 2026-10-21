import { flushPromises } from '@vue/test-utils'
import { chooseOption } from '../test/select'
import { describe, expect, it, vi } from 'vitest'
import type { Report, Template } from '../api/types'
import { fail, mockApi, mountWithApp, ok } from '../test/mount'
import ReportsView from './ReportsView.vue'

const REPORTED = 'cd'.repeat(64)
const REPORTER = 'ef'.repeat(64)

function report(overrides: Partial<Report> = {}): Report {
  return {
    identifier: 'r-1',
    reporterHash: REPORTER,
    reportedHash: REPORTED,
    category: 'CHEATING',
    description: 'Flying around the spawn',
    status: 'OPEN',
    createdAt: Date.UTC(2030, 0, 2),
    updatedAt: Date.UTC(2030, 0, 2),
    metaData: {},
    ...overrides,
  } as Report
}

const templates = [
  { identifier: 'tpl-1', key: 'hacking', reason: 'Hacking', section: 'network', steps: [] },
] as unknown as Template[]

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    reports: vi.fn(async () => ok([report(), report({ identifier: 'r-2', status: 'DISMISSED', description: 'Old news' })])),
    templates: vi.fn(async () => ok(templates)),
    resolveReport: vi.fn(async () => ok(report({ status: 'DISMISSED' }))),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(ReportsView, { api: mockApi(api), path: '/reports' }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

const dialogField = (wrapper: Wrapper, label: string) => {
  const found = wrapper.findAll('[role="dialog"] label').find((l) => l.text().startsWith(label))
  if (!found) throw new Error(`no field labelled ${label}`)
  return wrapper.get(`[role="dialog"] [id="${found.attributes('for')}"]`)
}

describe('ReportsView', () => {
  it('lists the open reports and hides resolved ones by default', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.text()).toContain('Cheating')
    expect(rows[0]!.text()).toContain('Flying around the spawn')
    expect(rows[0]!.text()).toContain('Open')

    wrapper.unmount()
  })

  it('shows resolved reports as well when asked to', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="filter-all"]').trigger('click')

    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.findAll('tbody tr')[1]!.text()).toContain('Dismissed')
    expect(wrapper.find('[data-testid="resolve-r-2"]').exists(), 'a resolved report cannot be resolved again').toBe(false)

    wrapper.unmount()
  })

  it('links the reported player to the player detail by hash only', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const link = wrapper.get(`a[href="/players/${REPORTED}"]`)
    expect(link.text()).toBe(`${REPORTED.slice(0, 8)}…`)

    wrapper.unmount()
  })

  it('says so when nothing is open', async () => {
    const { mount } = setup({ reports: vi.fn(async () => ok([report({ status: 'ACTIONED' })])) })
    const { wrapper } = await mount()

    expect(wrapper.text()).toContain('No open reports.')

    wrapper.unmount()
  })

  it('shows the error with a retry when loading fails', async () => {
    const reports = vi.fn().mockResolvedValueOnce(fail('backend down', 0, 'NETWORK')).mockResolvedValueOnce(ok([report()]))
    const { mount } = setup({ reports })
    const { wrapper } = await mount()

    expect(wrapper.get('[role="alert"]').text()).toContain('backend down')
    await wrapper.get('[data-testid="retry"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('tbody tr')).toHaveLength(1)

    wrapper.unmount()
  })
})

describe('ReportsView: resolving', () => {
  it('dismisses a report with a note and reloads the queue', async () => {
    const reports = vi
      .fn()
      .mockResolvedValueOnce(ok([report()]))
      .mockResolvedValueOnce(ok([report({ status: 'DISMISSED' })]))
    const { api, mount } = setup({ reports })
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="resolve-r-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DISMISSED')
    await dialogField(wrapper, 'Note').setValue('no evidence')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledWith('r-1', { status: 'DISMISSED', note: 'no evidence', templateId: undefined })
    expect(wrapper.find('tbody tr').exists(), 'the report left the open queue').toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('Report resolved.')

    wrapper.unmount()
  })

  it('actions a report and applies the chosen template to the reported player', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="resolve-r-1"]').trigger('click')
    expect(wrapper.findAll('[role="dialog"] [role="combobox"]'), 'no template choice before a decision is made').toHaveLength(1)
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'ACTIONED')
    await chooseOption(wrapper.findAll('[role="dialog"] [role="combobox"]')[1]!, 'tpl-1')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledWith('r-1', { status: 'ACTIONED', note: undefined, templateId: 'tpl-1' })

    wrapper.unmount()
  })

  it('does not send a template for a dismissal even if one was chosen before', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="resolve-r-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'ACTIONED')
    await chooseOption(wrapper.findAll('[role="dialog"] [role="combobox"]')[1]!, 'tpl-1')

    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'DISMISSED')
    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(api.resolveReport).toHaveBeenCalledWith('r-1', expect.objectContaining({ status: 'DISMISSED', templateId: undefined }))

    wrapper.unmount()
  })

  it('keeps the dialog open with the backend error when resolving fails', async () => {
    const resolveReport = vi.fn(async () => fail('the player is already permanently punished in section network', 409, 'CONFLICT'))
    const { mount } = setup({ resolveReport })
    const { wrapper } = await mount()
    await wrapper.get('[data-testid="resolve-r-1"]').trigger('click')
    await chooseOption(wrapper.get('[role="dialog"] [role="combobox"]'), 'ACTIONED')
    await chooseOption(wrapper.findAll('[role="dialog"] [role="combobox"]')[1]!, 'tpl-1')

    await wrapper.get('[data-testid="confirm"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="error"]').text()).toContain('already permanently punished')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)

    wrapper.unmount()
  })
})
