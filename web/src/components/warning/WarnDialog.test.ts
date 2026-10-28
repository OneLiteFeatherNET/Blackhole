import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApplyResult, Template } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import WarnDialog from './WarnDialog.vue'

const OWNER = 'ab'.repeat(64)

const templates = [
  { identifier: 'id-1', key: 'spam', reason: 'Spam', section: 'chat', steps: [] },
  { identifier: 'id-2', key: 'hacking', reason: 'Hacking', section: 'network', steps: [] },
] as unknown as Template[]

function applied(kind: 'PUNISHMENT' | 'KICK' | 'WARN', enforcement: 'MUTE' | 'BAN' | 'SERVER' = 'MUTE'): ApplyResult {
  return {
    section: 'chat',
    enforcement,
    step: 1,
    stepCount: 2,
    scoreBefore: 900,
    scoreAfter: 895,
    punishment: { identifier: 'p-1', shortId: 'ABC123', source: 'src', verified: false, section: 'chat', permanent: false, lifted: false, metaData: {}, kind },
  } as unknown as ApplyResult
}

function setup(overrides: Record<string, unknown> = {}) {
  const api = {
    templates: vi.fn(async () => ok(templates)),
    applyTemplate: vi.fn(async () => ok(applied('WARN'))),
    ...overrides,
  }
  return { api, mount: () => mountWithApp(WarnDialog, { api: mockApi(api), props: { open: true, owner: OWNER } }) }
}

type Wrapper = Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']

async function choose(wrapper: Wrapper, key: string) {
  await chooseOption(wrapper.get('[role="combobox"]'), key)
  await flushPromises()
}

async function submit(wrapper: Wrapper) {
  await wrapper.get('[data-testid="confirm"]').trigger('click')
  await flushPromises()
}

describe('WarnDialog template', () => {
  it('disables submit until a template is chosen and sends nothing', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled'), 'submit disabled without a template').toBeDefined()
    await submit(wrapper)
    expect(api.applyTemplate, 'no request without a template').not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('WarnDialog submit', () => {
  it('applies the chosen template through the template apply route', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount()

    await choose(wrapper, 'spam')
    await submit(wrapper)

    expect(api.applyTemplate, 'the apply route with the owner and template').toHaveBeenCalledWith(OWNER, 'spam')
    wrapper.unmount()
  })

  it('shows the Warning chip when the server applied a warning step', async () => {
    const { mount } = setup()
    const { wrapper } = await mount()

    await choose(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.get('[data-testid="applied-step"]').text(), 'chip for the applied step').toBe('Warning')
    wrapper.unmount()
  })

  it('shows the Kick chip when the server applied a kick step', async () => {
    const { mount } = setup({ applyTemplate: vi.fn(async () => ok(applied('KICK'))) })
    const { wrapper } = await mount()

    await choose(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.get('[data-testid="applied-step"]').text()).toBe('Kick')
    wrapper.unmount()
  })

  it('shows the ban label when the server applied a punishment step', async () => {
    const { mount } = setup({ applyTemplate: vi.fn(async () => ok(applied('PUNISHMENT', 'BAN'))) })
    const { wrapper } = await mount()

    await choose(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.get('[data-testid="applied-step"]').text()).toBe('Ban')
    wrapper.unmount()
  })

  it('shows the server error and no chip when the template cannot be applied', async () => {
    const { mount } = setup({ applyTemplate: vi.fn(async () => fail('The section is disabled.', 409, 'CONFLICT')) })
    const { wrapper } = await mount()

    await choose(wrapper, 'spam')
    await submit(wrapper)

    expect(wrapper.get('[data-testid="error"]').text()).toContain('The section is disabled.')
    expect(wrapper.find('[data-testid="applied-step"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
