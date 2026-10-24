import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PortalApi } from '../api/portal'
import { APPEAL_OPEN, entry, fail, facts, mountPortal, ok, page, signedInApi } from '../test/helpers'

let mounted: Awaited<ReturnType<typeof mountPortal>> | undefined
afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
})

async function openForm(methods: Partial<PortalApi> = {}) {
  mounted = await mountPortal(signedInApi({ conversation: async () => ok(page([entry(1)])), appeal: async () => ok(APPEAL_OPEN), ...methods }))
  await mounted.wrapper.findAll('button').find((b) => b.text() === 'File an appeal')!.trigger('click')
  return mounted.wrapper
}

describe('appeal form', () => {
  it('has a labelled statement field, the one-appeal notice and a character counter', async () => {
    const wrapper = await openForm()

    expect(wrapper.get('label[for="statement"]').text()).toBe('Your statement')
    expect(wrapper.text()).toContain('one appeal open per punishment')
    expect(wrapper.get('#statement-count').text()).toBe('0 of 2000 characters')
    await wrapper.get('textarea').setValue('abc')
    expect(wrapper.get('#statement-count').text()).toBe('3 of 2000 characters')
  })

  it('cannot be sent empty or too long', async () => {
    const fileAppeal = vi.fn()
    const wrapper = await openForm({ fileAppeal })

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    await wrapper.get('textarea').setValue('x'.repeat(2001))
    await wrapper.get('form').trigger('submit')

    expect(fileAppeal).not.toHaveBeenCalled()
    expect(wrapper.get('#statement-count').classes()).toContain('text-error')
  })

  it('files the trimmed statement, then shows the conversation and drops the draft', async () => {
    const fileAppeal = vi.fn(async () => ok(APPEAL_OPEN))
    const wrapper = await openForm({ fileAppeal, punishment: vi.fn().mockResolvedValueOnce(ok(facts())).mockResolvedValue(ok(facts({ appealState: 'OPEN' }))) })

    await wrapper.get('textarea').setValue('  please review  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(fileAppeal).toHaveBeenCalledExactlyOnceWith('please review')
    expect(wrapper.get('h1').text()).toBe('Conversation with the team')
    expect(mounted!.session.state.drafts.statement).toBe('')
  })

  it('shows the date when it is too early and keeps the text', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('NOT_ELIGIBLE', 409, '2026-11-15T00:00:00Z') })

    await wrapper.get('textarea').setValue('early')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toMatch(/You can file from .*2026/)
    expect(mounted!.session.state.drafts.statement).toBe('early')
  })

  it('explains a text the guard rejected', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('PII_REJECTED', 422) })

    await wrapper.get('textarea').setValue('my ip is 1.2.3.4')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('remove IP addresses')
  })

  it('shows the expiry page and keeps the statement when the session ended', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('SESSION_EXPIRED', 401) })

    await wrapper.get('textarea').setValue('a long statement')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Your session has ended')
    expect(mounted!.session.state.drafts.statement).toBe('a long statement')
  })

  it('goes back to the punishment page', async () => {
    const wrapper = await openForm()

    await wrapper.findAll('button').find((b) => b.text() === 'Back')!.trigger('click')

    expect(wrapper.get('h1').text()).toBe('Your punishment')
  })
})
