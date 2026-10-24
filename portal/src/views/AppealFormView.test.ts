import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PortalApi } from '../api/portal'
import { APPEAL_OPEN, CONDITIONS, entry, fail, facts, mountPortal, ok, page, signedInApi } from '../test/helpers'

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

/** Confirms the conditions, then sends the form. */
async function send(wrapper: Awaited<ReturnType<typeof openForm>>) {
  await wrapper.get('#conditions-confirm').setValue(true)
  await wrapper.get('form').trigger('submit')
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
    await send(wrapper)

    expect(fileAppeal).not.toHaveBeenCalled()
    expect(wrapper.get('#statement-count').classes()).toContain('text-error')
  })

  it('files the trimmed statement, then shows the conversation and drops the draft', async () => {
    const fileAppeal = vi.fn(async () => ok(APPEAL_OPEN))
    const wrapper = await openForm({ fileAppeal, punishment: vi.fn().mockResolvedValueOnce(ok(facts())).mockResolvedValue(ok(facts({ appealState: 'OPEN' }))) })

    await wrapper.get('textarea').setValue('  please review  ')
    await send(wrapper)
    await flushPromises()

    expect(fileAppeal).toHaveBeenCalledExactlyOnceWith('please review', CONDITIONS.version)
    expect(wrapper.get('h1').text()).toBe('Conversation with the team')
    expect(mounted!.session.state.drafts.statement).toBe('')
  })

  it('shows the date when it is too early and keeps the text', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('NOT_ELIGIBLE', 409, '2026-11-15T00:00:00Z') })

    await wrapper.get('textarea').setValue('early')
    await send(wrapper)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toMatch(/You can file from .*2026/)
    expect(mounted!.session.state.drafts.statement).toBe('early')
  })

  it('says there is nothing to wait for when no date is given', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('NOT_ELIGIBLE', 409) })

    await wrapper.get('textarea').setValue('again')
    await send(wrapper)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('You cannot file an appeal for this punishment right now.')
  })

  it('explains a text the guard rejected', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('PII_REJECTED', 422) })

    await wrapper.get('textarea').setValue('my ip is 1.2.3.4')
    await send(wrapper)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('remove IP addresses')
  })

  it('shows the expiry page and keeps the statement when the session ended', async () => {
    const wrapper = await openForm({ fileAppeal: async () => fail('SESSION_EXPIRED', 401) })

    await wrapper.get('textarea').setValue('a long statement')
    await send(wrapper)
    await flushPromises()

    expect(wrapper.get('h1').text()).toBe('Your session has ended')
    expect(mounted!.session.state.drafts.statement).toBe('a long statement')
  })

  it('goes back to the punishment page', async () => {
    const wrapper = await openForm()

    await wrapper.findAll('button').find((b) => b.text() === 'Back')!.trigger('click')

    expect(wrapper.get('h1').text()).toBe('Your punishment')
  })

  describe('appeal conditions', () => {
    it('shows the conditions with a checkbox that is not ticked', async () => {
      const wrapper = await openForm()

      expect(wrapper.get('[data-testid="conditions"]').text()).toContain('Be respectful.')
      expect(wrapper.findAll('[data-testid="conditions"] li').map((li) => li.text())).toEqual(['Be respectful.', 'One appeal per Ban ID.'])
      expect((wrapper.get('#conditions-confirm').element as HTMLInputElement).checked).toBe(false)
      expect(wrapper.get('label[for="conditions-confirm"]').text()).toContain('I have read the appeal conditions')
    })

    it('cannot send a good statement until the conditions are confirmed', async () => {
      const fileAppeal = vi.fn(async () => ok(APPEAL_OPEN))
      const wrapper = await openForm({ fileAppeal })

      await wrapper.get('textarea').setValue('a good statement')
      await wrapper.get('form').trigger('submit')

      expect(wrapper.get('button[type="submit"]').attributes('disabled'), 'send is disabled').toBeDefined()
      expect(fileAppeal, 'nothing was sent').not.toHaveBeenCalled()
    })

    it('enables sending once the conditions are confirmed', async () => {
      const wrapper = await openForm()

      await wrapper.get('textarea').setValue('a good statement')
      await wrapper.get('#conditions-confirm').setValue(true)

      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
    })

    it('sends the version it showed, not a newer one', async () => {
      const fileAppeal = vi.fn(async () => ok(APPEAL_OPEN))
      const wrapper = await openForm({ fileAppeal, appealConditions: async () => ok({ version: 7, text: 'Seven.' }) })

      await wrapper.get('textarea').setValue('statement')
      await send(wrapper)
      await flushPromises()

      expect(fileAppeal).toHaveBeenCalledExactlyOnceWith('statement', 7)
    })

    it('shows the new text, clears the confirmation and says so when the conditions changed', async () => {
      const appealConditions = vi.fn().mockResolvedValueOnce(ok(CONDITIONS)).mockResolvedValue(ok({ version: 4, text: 'Brand new rules.' }))
      const wrapper = await openForm({ appealConditions, fileAppeal: async () => fail('CONDITIONS_CHANGED', 409) })

      await wrapper.get('textarea').setValue('my statement')
      await send(wrapper)
      await flushPromises()

      expect(wrapper.get('[data-testid="conditions"]').text()).toContain('Brand new rules.')
      expect((wrapper.get('#conditions-confirm').element as HTMLInputElement).checked, 'confirmation cleared').toBe(false)
      expect(wrapper.get('[role="alert"]').text()).toContain('conditions changed')
      expect(mounted!.session.state.drafts.statement, 'the statement is kept').toBe('my statement')
      expect(wrapper.get('button[type="submit"]').attributes('disabled'), 'must confirm again').toBeDefined()
    })

    it('files the new version after the player confirms again', async () => {
      const fileAppeal = vi.fn().mockResolvedValueOnce(fail('CONDITIONS_CHANGED', 409)).mockResolvedValue(ok(APPEAL_OPEN))
      const appealConditions = vi.fn().mockResolvedValueOnce(ok(CONDITIONS)).mockResolvedValue(ok({ version: 4, text: 'Brand new rules.' }))
      const wrapper = await openForm({ appealConditions, fileAppeal })

      await wrapper.get('textarea').setValue('my statement')
      await send(wrapper)
      await flushPromises()
      await send(wrapper)
      await flushPromises()

      expect(fileAppeal).toHaveBeenLastCalledWith('my statement', 4)
    })

    it('cannot be confirmed or sent when the conditions could not be loaded', async () => {
      const fileAppeal = vi.fn()
      const wrapper = await openForm({ appealConditions: async () => fail('UNAVAILABLE', 503), fileAppeal })

      await wrapper.get('textarea').setValue('statement')

      expect(wrapper.get('[data-testid="conditions"]').text()).toContain('could not be loaded')
      expect(wrapper.get('#conditions-confirm').attributes('disabled')).toBeDefined()
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
    })

    it('shows markup in the conditions as text', async () => {
      const wrapper = await openForm({ appealConditions: async () => ok({ version: 5, text: '<script>alert(1)</script> <b>x</b>' }) })

      expect(wrapper.get('[data-testid="conditions"]').text()).toContain('<script>alert(1)</script> <b>x</b>')
      expect(wrapper.find('[data-testid="conditions"] script').exists()).toBe(false)
      expect(wrapper.find('[data-testid="conditions"] b').exists()).toBe(false)
    })
  })
})
