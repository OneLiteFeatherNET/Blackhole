import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../../test/mount'
import ExemptionOverride from './ExemptionOverride.vue'

const MESSAGE = 'The player is exempt from punishment.'

function mountNotice(admin: boolean, props: Record<string, unknown> = {}) {
  return mountWithApp(ExemptionOverride, { props: { admin, message: MESSAGE, ...props } })
}

async function typeJustification(wrapper: Awaited<ReturnType<typeof mountNotice>>['wrapper'], text: string) {
  await wrapper.get('[data-testid="exemption-justification"] input').setValue(text)
}

async function clickOverride(wrapper: Awaited<ReturnType<typeof mountNotice>>['wrapper']) {
  await wrapper.get('[data-testid="exemption-override"]').trigger('click')
  await flushPromises()
}

describe('ExemptionOverride for a moderator', () => {
  it('shows the refusal and why an administrator must decide', async () => {
    const { wrapper } = await mountNotice(false)

    expect(wrapper.get('[data-testid="exemption-refusal"]').text(), 'the backend message').toContain(MESSAGE)
    expect(wrapper.text(), 'the reason').toContain('Only an administrator')
    wrapper.unmount()
  })

  it('offers no justification field and no override action', async () => {
    const { wrapper } = await mountNotice(false)

    expect(wrapper.find('[data-testid="exemption-justification"]').exists(), 'no override field').toBe(false)
    expect(wrapper.find('[data-testid="exemption-override"]').exists(), 'no override action').toBe(false)
    wrapper.unmount()
  })
})

describe('ExemptionOverride for an administrator', () => {
  it('shows the justification field and the override action', async () => {
    const { wrapper } = await mountNotice(true)

    expect(wrapper.find('[data-testid="exemption-justification"]').exists(), 'the field').toBe(true)
    expect(wrapper.get('[data-testid="exemption-override"]').text()).toBe('Apply with override')
    wrapper.unmount()
  })

  it('requires a justification and emits nothing while it is empty', async () => {
    const { wrapper } = await mountNotice(true)

    await clickOverride(wrapper)

    expect(wrapper.emitted('override'), 'no override sent').toBeUndefined()
    expect(wrapper.text()).toContain('Enter a justification.')
    wrapper.unmount()
  })

  it('treats a justification of only spaces as empty', async () => {
    const { wrapper } = await mountNotice(true)
    await typeJustification(wrapper, '   ')

    await clickOverride(wrapper)

    expect(wrapper.emitted('override'), 'no override sent').toBeUndefined()
    wrapper.unmount()
  })

  it('refuses a justification longer than 500 characters', async () => {
    const { wrapper } = await mountNotice(true)
    await typeJustification(wrapper, 'x'.repeat(501))

    await clickOverride(wrapper)

    expect(wrapper.emitted('override'), 'no override sent').toBeUndefined()
    expect(wrapper.text()).toContain('At most 500 characters.')
    wrapper.unmount()
  })

  it('emits the trimmed justification once it is filled in', async () => {
    const { wrapper } = await mountNotice(true)
    await typeJustification(wrapper, '  Verified with the owner in the forum  ')

    await clickOverride(wrapper)

    expect(wrapper.emitted('override')).toEqual([['Verified with the owner in the forum']])
    wrapper.unmount()
  })

  it('disables the override action while the override is being sent', async () => {
    const { wrapper } = await mountNotice(true, { busy: true })

    expect(wrapper.get('[data-testid="exemption-override"]').attributes('disabled'), 'busy').toBeDefined()
    wrapper.unmount()
  })
})
