import { flushPromises } from '@vue/test-utils'
import { nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import type { CaseBulkItem, CaseBulkChange } from '../../api/types'
import { chooseOption, listOptions } from '../../test/select'
import { mockApi, mountWithApp, ok } from '../../test/mount'
import CaseBulkDialog from './CaseBulkDialog.vue'

const TARGETS = [
  { punishmentIdentifier: 'p-1', version: 2, shortId: '#AB12CD' },
  { punishmentIdentifier: 'p-2', version: 5, shortId: '#ZZ99XX' },
]

function item(overrides: Partial<CaseBulkItem> & { punishmentIdentifier: string }): CaseBulkItem {
  return { outcome: 'UPDATED', changed: true, version: 3, currentVersion: null, auditWritten: true, message: null, ...overrides }
}

function setup(bulk: (change: CaseBulkChange, options?: { onProgress?: (done: number, total: number) => void }) => Promise<unknown> = async (change) =>
  ok(change.cases.map((c) => item({ punishmentIdentifier: c.punishmentIdentifier }))),
) {
  const bulkEditCases = vi.fn(bulk)
  const mount = () =>
    mountWithApp(CaseBulkDialog, {
      api: mockApi({ bulkEditCases }),
      path: '/cases',
      props: { open: true, cases: TARGETS },
    })
  return { bulkEditCases, mount }
}

const applyButton = (wrapper: { get: (selector: string) => { attributes: (name: string) => string | undefined } }) =>
  wrapper.get('[data-testid="bulk-apply"]')

describe('CaseBulkDialog', () => {
  it('keeps Apply disabled until one change is set', async () => {
    const { mount, bulkEditCases } = setup()
    const { wrapper } = await mount()

    expect(applyButton(wrapper).attributes('disabled')).toBeDefined()

    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')

    expect(applyButton(wrapper).attributes('disabled')).toBeUndefined()
    expect(bulkEditCases).not.toHaveBeenCalled()

    wrapper.unmount()
  })

  it('sends the shared change with every case and the version it was read at', async () => {
    const { mount, bulkEditCases } = setup()
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    await chooseOption(wrapper.get('[data-testid="bulk-priority"] [role="combobox"]'), 'URGENT')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    expect(bulkEditCases).toHaveBeenCalledWith(
      {
        cases: [
          { punishmentIdentifier: 'p-1', expectedVersion: 2 },
          { punishmentIdentifier: 'p-2', expectedVersion: 5 },
        ],
        status: 'CLOSED',
        priority: 'URGENT',
      },
      expect.any(Object),
    )

    wrapper.unmount()
  })

  it('sends the assignee as cleared when unassigned is picked', async () => {
    const { mount, bulkEditCases } = setup()
    const { wrapper } = await mount()

    await chooseOption(wrapper.get('[data-testid="bulk-assignee"] [role="combobox"]'), '__none__')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    expect(bulkEditCases.mock.calls[0]![0]).toMatchObject({ assigneeOid: null })

    wrapper.unmount()
  })

  it('adds and removes tags as chips, and refuses a tag that is not valid or already listed', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await wrapper.get('[data-testid="bulk-add-tag"] input').setValue('spam')
    await wrapper.get('[data-testid="bulk-add-tag-form"]').trigger('submit')
    await wrapper.get('[data-testid="bulk-add-tag"] input').setValue('spam')
    await wrapper.get('[data-testid="bulk-add-tag-form"]').trigger('submit')
    await wrapper.get('[data-testid="bulk-add-tag"] input').setValue('Not Valid')
    await wrapper.get('[data-testid="bulk-add-tag-form"]').trigger('submit')

    expect(wrapper.findAll('[data-testid="bulk-added-tags"] li')).toHaveLength(1)
    expect(wrapper.get('[data-testid="bulk-add-tag"]').text()).toContain('A tag has 1 to 32 lower case letters')

    await wrapper.get('[aria-label="Remove tag spam"]').trigger('click')

    expect(wrapper.find('[data-testid="bulk-added-tags"] li').exists()).toBe(false)
    expect(applyButton(wrapper).attributes('disabled')).toBeDefined()

    wrapper.unmount()
  })

  it('shows determinate progress while the batch runs, and finishes when the server has answered', async () => {
    let answer!: (value: unknown) => void
    const pending = new Promise((resolve) => (answer = resolve))
    const { mount } = setup(async (_change, options) => {
      options?.onProgress?.(50, 100)
      return pending
    })
    const { wrapper } = await mount()
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')

    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await nextTick()

    expect(wrapper.get('[role="progressbar"]').attributes('aria-valuenow')).toBe('50')
    expect(wrapper.find('[data-testid="bulk-result"]').exists()).toBe(false)

    answer(ok([item({ punishmentIdentifier: 'p-1' }), item({ punishmentIdentifier: 'p-2' })]))
    await flushPromises()

    expect(wrapper.findAll('[data-testid="bulk-result"]')).toHaveLength(2)

    wrapper.unmount()
  })

  it('lists the outcome of each case, and the message of a failed one', async () => {
    const { mount } = setup(async () =>
      ok([item({ punishmentIdentifier: 'p-1', outcome: 'UPDATED' }), item({ punishmentIdentifier: 'p-2', outcome: 'FAILED', changed: false, message: 'the tags are full' })]),
    )
    const { wrapper } = await mount()
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    const rows = wrapper.findAll('[data-testid="bulk-result"]')
    expect(rows[0]!.text()).toContain('#AB12CD')
    expect(rows[0]!.text()).toContain('Updated')
    expect(rows[1]!.text()).toContain('#ZZ99XX')
    expect(rows[1]!.text()).toContain('Failed')
    expect(rows[1]!.text()).toContain('the tags are full')

    wrapper.unmount()
  })

  it('offers a reload when a case conflicted, and asks the page for it', async () => {
    const { mount } = setup(async () =>
      ok([item({ punishmentIdentifier: 'p-1', outcome: 'CONFLICT', changed: false, currentVersion: 9 }), item({ punishmentIdentifier: 'p-2' })]),
    )
    const { wrapper } = await mount()
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[data-testid="bulk-result"]').text()).toContain('Conflict')
    await wrapper.get('[data-testid="bulk-reload"]').trigger('click')

    expect(wrapper.emitted('reload')).toHaveLength(1)

    wrapper.unmount()
  })

  it('announces the counts of the batch in a live region', async () => {
    const { mount } = setup(async () => ok([item({ punishmentIdentifier: 'p-1' }), item({ punishmentIdentifier: 'p-2', outcome: 'UNCHANGED', changed: false })]))
    const { wrapper } = await mount()
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    const live = wrapper.get('[role="status"]')
    expect(live.attributes('aria-live')).toBe('polite')
    expect(live.text()).toContain('Updated 1')
    expect(live.text()).toContain('unchanged 1')

    wrapper.unmount()
  })

  it('reports the finished items once, so the page can reload and clear its selection', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()
    await chooseOption(wrapper.get('[data-testid="bulk-status"] [role="combobox"]'), 'CLOSED')
    await wrapper.get('[data-testid="bulk-apply"]').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('done')).toHaveLength(1)
    expect((wrapper.emitted('done')![0]![0] as CaseBulkItem[]).map((i) => i.punishmentIdentifier)).toEqual(['p-1', 'p-2'])

    wrapper.unmount()
  })

  it('offers keep, unassigned and the signed-in staff member as assignees', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    const options = await listOptions(wrapper.get('[data-testid="bulk-assignee"] [role="combobox"]'))

    expect(options.map((option) => option.value)).toEqual(['__keep__', '__none__', '00000000-0000-4000-8000-0000000000a1'])

    wrapper.unmount()
  })
})
