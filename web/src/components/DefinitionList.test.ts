import { h } from 'vue'
import { describe, expect, it } from 'vitest'
import { mountWithApp } from '../test/mount'
import DefinitionList from './DefinitionList.vue'

describe('DefinitionList', () => {
  it('renders every term with its value in the given order', async () => {
    const { wrapper } = await mountWithApp(DefinitionList, {
      props: {
        items: [
          { term: 'Player', value: 'abcdef12…' },
          { term: 'Section', value: 'network' },
        ],
      },
    })

    expect(wrapper.findAll('dt').map((term) => term.text())).toEqual(['Player', 'Section'])
    expect(wrapper.findAll('dd').map((value) => value.text())).toEqual(['abcdef12…', 'network'])

    wrapper.unmount()
  })

  it('renders a value slot for a value that is a link, with the item it belongs to', async () => {
    const { wrapper } = await mountWithApp(DefinitionList, {
      props: { items: [{ term: 'Player', value: 'abcdef12…' }] },
      slots: { value: ({ item }: { item: { term: string } }) => h('a', { href: '/players/abcdef12' }, `Open ${item.term}`) },
    })

    expect(wrapper.get('dd a').attributes('href')).toBe('/players/abcdef12')
    expect(wrapper.get('dd a').text()).toBe('Open Player')

    wrapper.unmount()
  })

  it('puts the test id on the value it is given', async () => {
    const { wrapper } = await mountWithApp(DefinitionList, {
      props: { items: [{ term: 'Player', value: 'abcdef12…', testid: 'player-prefix' }] },
    })

    expect(wrapper.get('[data-testid="player-prefix"]').text()).toBe('abcdef12…')

    wrapper.unmount()
  })
})
