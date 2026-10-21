import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { h } from 'vue'
import MdTopAppBar from './MdTopAppBar.vue'

describe('MdTopAppBar', () => {
  it('shows the page title as the heading', () => {
    const wrapper = mount(MdTopAppBar, { props: { title: 'Spieler' } })

    expect(wrapper.get('h1').text()).toBe('Spieler')
  })

  it('renders the actions slot and forwards its clicks', async () => {
    const onRefresh = vi.fn()
    const wrapper = mount(MdTopAppBar, {
      props: { title: 'Spieler' },
      slots: { actions: () => h('button', { type: 'button', onClick: onRefresh }, 'Aktualisieren') },
    })

    await wrapper.get('button').trigger('click')

    expect(onRefresh).toHaveBeenCalledTimes(1)
  })
})
