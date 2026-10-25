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

  it('puts the test id on the value it is given', async () => {
    const { wrapper } = await mountWithApp(DefinitionList, {
      props: { items: [{ term: 'Player', value: 'abcdef12…', testid: 'player-prefix' }] },
    })

    expect(wrapper.get('[data-testid="player-prefix"]').text()).toBe('abcdef12…')

    wrapper.unmount()
  })
})
