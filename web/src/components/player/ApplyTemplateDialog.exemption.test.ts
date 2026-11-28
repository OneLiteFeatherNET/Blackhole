import { flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import type { ApplyPreview, ApplyResult, Template } from '../../api/types'
import { fail, mockApi, mountWithApp, ok } from '../../test/mount'
import { chooseOption } from '../../test/select'
import ApplyTemplateDialog from './ApplyTemplateDialog.vue'

// The refusal of a protected target and the ADMIN's override, in the punish dialog.

const OWNER = 'ab'.repeat(64)
const REFUSAL = 'The player is exempt from punishment.'

const templates = [{ identifier: 'id-1', key: 'hacking', reason: 'Hacking', section: 'network', steps: [] }] as unknown as Template[]

const preview: ApplyPreview = {
  section: 'network',
  enforcement: 'BAN',
  step: 2,
  stepCount: 3,
  duration: 'P7D',
  eloCost: 150,
  scoreBefore: 900,
  scoreAfter: 750,
  permanentThreshold: 300,
  escalatesToPermanent: false,
}

const applied = { step: 2, stepCount: 3, scoreAfter: 750, section: 'network' } as unknown as ApplyResult

function setup() {
  const refused = fail(REFUSAL, 409, 'TARGET_EXEMPT')
  const api = {
    templates: vi.fn(async () => ok(templates)),
    previewApply: vi.fn(async () => ok(preview)),
    // Refused without an override; applied with one.
    applyTemplate: vi.fn(async (_owner: string, _key: string, _uuid: string | null, override?: { justification: string }) =>
      override ? ok(applied) : refused,
    ),
  }
  return { api, mount: (roles: string[]) => mountWithApp(ApplyTemplateDialog, { api: mockApi(api), props: { open: true, owner: OWNER }, roles }) }
}

async function applyHacking(wrapper: Awaited<ReturnType<ReturnType<typeof setup>['mount']>>['wrapper']) {
  await chooseOption(wrapper.get('[role="combobox"]'), 'hacking')
  await flushPromises()
  await wrapper.get('[data-testid="confirm"]').trigger('click')
  await flushPromises()
}

describe('ApplyTemplateDialog with a protected target for a moderator', () => {
  it('shows why the application was refused and that only an administrator can decide', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    await applyHacking(wrapper)

    expect(wrapper.get('[data-testid="exemption-refusal"]').text()).toContain(REFUSAL)
    expect(wrapper.text()).toContain('Only an administrator')
    expect(api.applyTemplate, 'one attempt, no override').toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('offers no override field and keeps the apply action disabled', async () => {
    const { mount } = setup()
    const { wrapper } = await mount(['MODERATOR', 'VIEWER'])

    await applyHacking(wrapper)

    expect(wrapper.find('[data-testid="exemption-justification"]').exists(), 'no field').toBe(false)
    expect(wrapper.get('[data-testid="confirm"]').attributes('disabled'), 'apply stays disabled').toBeDefined()
    wrapper.unmount()
  })
})

describe('ApplyTemplateDialog with a protected target for an administrator', () => {
  it('offers the override with a justification and applies with it', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    await applyHacking(wrapper)

    await wrapper.get('[data-testid="exemption-justification"] input').setValue('Confirmed with the owner')
    await wrapper.get('[data-testid="exemption-override"]').trigger('click')
    await flushPromises()

    expect(api.applyTemplate).toHaveBeenLastCalledWith(OWNER, 'hacking', null, { justification: 'Confirmed with the owner' })
    expect(wrapper.emitted('done'), 'the dialog reports the application').toEqual([['Applied step 2 of 3; the score is now 750.']])
    wrapper.unmount()
  })

  it('sends no override while the justification is empty', async () => {
    const { api, mount } = setup()
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])
    await applyHacking(wrapper)

    await wrapper.get('[data-testid="exemption-override"]').trigger('click')
    await flushPromises()

    expect(api.applyTemplate, 'only the first, refused attempt').toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Enter a justification.')
    wrapper.unmount()
  })
})

describe('ApplyTemplateDialog errors that are not a refusal', () => {
  it('shows an ordinary conflict as an error, with no exemption panel', async () => {
    const { api, mount } = setup()
    api.applyTemplate.mockResolvedValueOnce(fail('already permanently punished', 409, 'CONFLICT'))
    const { wrapper } = await mount(['ADMIN', 'MODERATOR', 'VIEWER'])

    await applyHacking(wrapper)

    expect(wrapper.get('[data-testid="error"]').text()).toBe('already permanently punished')
    expect(wrapper.find('[data-testid="exemption-refusal"]').exists(), 'no refusal panel').toBe(false)
    wrapper.unmount()
  })
})
