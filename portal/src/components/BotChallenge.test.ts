import { flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { fakeApi, mountPortal, ok, fail, type Mounted } from '../test/helpers'
import BotChallenge from './BotChallenge.vue'

let mounted: Mounted | undefined
afterEach(() => {
  mounted?.wrapper.unmount()
  mounted = undefined
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})

const ISSUED = {
  parameters: { algorithm: 'PBKDF2/SHA-256', nonce: 'n', expiresAt: 2_000_000_000, data: { purpose: 'sign-in' } },
  signature: 'sig',
}

/** A host with a form around the widget, so the hidden field is read the way the views read it. */
function host(events: boolean[] = []) {
  return defineComponent({
    render: () =>
      h('form', [h(BotChallenge, { purpose: 'sign-in', onReady: (ready: boolean) => events.push(ready) }), h('span', { id: 'marker' })]),
  })
}

async function mountWith(challenge: () => Promise<unknown>, events: boolean[] = []) {
  mounted = await mountPortal(fakeApi({ challenge: challenge as never }), { component: host(events) })
  await flushPromises()
  return mounted.wrapper
}

describe('the challenge in front of sign-in and filing', () => {
  it('is absent when the check is off', async () => {
    const events: boolean[] = []
    const wrapper = await mountWith(async () => ok({ enabled: false }), events)

    expect(wrapper.find('altcha-widget').exists(), 'no widget').toBe(false)
    expect(wrapper.get('[data-testid="bot-challenge"]').attributes('data-phase')).toBe('off')
    expect(events, 'nothing is held back when the check is off').toEqual([true])
  })

  it('shows the widget with its challenge URL, a checkbox, an onload solve and no interaction signature', async () => {
    const wrapper = await mountWith(async () => ok(ISSUED))

    const widget = wrapper.get('altcha-widget')
    expect(widget.attributes('challenge')).toBe('/api/challenge?purpose=sign-in')
    expect(widget.attributes('type')).toBe('checkbox')
    expect(widget.attributes('auto')).toBe('onload')
    expect(widget.attributes('name')).toBe('altcha')
    expect(widget.attributes('configuration'), 'the collector is off').toContain('"humanInteractionSignature":false')
  })

  it('never sets the test or the mock-error option of the widget', async () => {
    const wrapper = await mountWith(async () => ok(ISSUED))

    const widget = wrapper.get('altcha-widget')
    expect(widget.attributes('test'), 'no test attribute').toBeUndefined()
    expect(widget.attributes('mockerror'), 'no mock error').toBeUndefined()
    expect(widget.attributes('configuration')).not.toContain('mockError')
  })

  it('holds the submit back until a solution is verified', async () => {
    const events: boolean[] = []
    const wrapper = await mountWith(async () => ok(ISSUED), events)

    wrapper.get('altcha-widget').element.dispatchEvent(new CustomEvent('statechange', { detail: { state: 'verified' } }))
    await flushPromises()

    expect(events.at(-1), 'ready after a verified solve').toBe(true)
  })

  it('shows a retry and the typed form stays for a failed solve', async () => {
    const events: boolean[] = []
    const wrapper = await mountWith(async () => ok(ISSUED), events)

    wrapper.get('altcha-widget').element.dispatchEvent(new CustomEvent('statechange', { detail: { state: 'error' } }))
    await flushPromises()

    expect(events.at(-1), 'not ready after a failed solve').toBe(false)
    expect(wrapper.get('[role="alert"]').text()).toContain('did not finish')
    expect(wrapper.findAll('button').map((button) => button.text())).toContain('Try the check again')
  })

  it('restarts the widget when the player asks for a retry', async () => {
    const wrapper = await mountWith(async () => ok(ISSUED))
    const element = wrapper.get('altcha-widget').element as HTMLElement & { reset: () => void; verify: () => Promise<null> }
    element.reset = vi.fn()
    element.verify = vi.fn(async () => null)
    element.dispatchEvent(new CustomEvent('statechange', { detail: { state: 'error' } }))
    await flushPromises()

    const retry = wrapper.findAll('button').find((button) => button.text() === 'Try the check again')!
    await retry.trigger('click')

    expect(element.reset, 'the widget starts again').toHaveBeenCalledOnce()
    expect(element.verify, 'a new solve runs').toHaveBeenCalledOnce()
  })

  it('shows the fallback message and keeps the submit held when the challenge cannot be fetched', async () => {
    const events: boolean[] = []
    const wrapper = await mountWith(async () => fail('UNAVAILABLE', 503), events)

    expect(wrapper.text()).toContain('could not run in this browser')
    expect(events.at(-1), 'not ready without a check').toBe(false)
  })

  it('reads the solved payload from the hidden field of its form', async () => {
    const wrapper = await mountWith(async () => ok(ISSUED))
    const field = document.createElement('input')
    field.name = 'altcha'
    field.value = 'SOLVED-PAYLOAD'
    wrapper.get('form').element.appendChild(field)

    const exposed = (wrapper.findComponent(BotChallenge).vm as unknown as { read: () => string | null }).read()

    expect(exposed).toBe('SOLVED-PAYLOAD')
  })
})
