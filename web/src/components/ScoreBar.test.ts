import { describe, expect, it } from 'vitest'
import { scoreZone, scaleOf } from './scoreScale'
import ScoreBar from './ScoreBar.vue'
import { mountWithApp } from '../test/mount'

describe('scoreZone', () => {
  it('is permanent below the permanent threshold', () => {
    expect(scoreZone(299, 700, 300)).toBe('permanent')
  })

  it('is soft below the soft threshold but not below the permanent one', () => {
    expect(scoreZone(699, 700, 300)).toBe('soft')
    expect(scoreZone(300, 700, 300), 'the permanent threshold itself is still soft zone').toBe('soft')
  })

  it('is healthy at or above the soft threshold, and without a soft threshold above the permanent one', () => {
    expect(scoreZone(700, 700, 300)).toBe('healthy')
    expect(scoreZone(500, null, 300)).toBe('healthy')
  })
})

describe('scaleOf', () => {
  it('spans zero to the base score and positions values as percentages', () => {
    const scale = scaleOf(1000, 300, 1000)

    expect(scale.percent(0)).toBe(0)
    expect(scale.percent(300)).toBe(30)
    expect(scale.percent(1000)).toBe(100)
  })

  it('widens to include scores above the base and below zero', () => {
    const scale = scaleOf(1000, -200, 1200)

    expect(scale.min).toBe(-200)
    expect(scale.max).toBe(1200)
    expect(scale.percent(1200)).toBe(100)
  })
})

describe('ScoreBar', () => {
  async function bar(props: Record<string, unknown>) {
    return mountWithApp(ScoreBar, {
      props: { label: 'Network', score: 850, baseScore: 1000, softThreshold: 700, permanentThreshold: 300, ...props },
    })
  }

  it('exposes the score as a labelled meter with a readable value text', async () => {
    const { wrapper } = await bar({})

    const meter = wrapper.get('[role="meter"]')
    expect(meter.attributes('aria-label')).toBe('Network')
    expect(meter.attributes('aria-valuenow')).toBe('850')
    expect(meter.attributes('aria-valuetext')).toBe('850 - healthy, soft threshold 700, permanent threshold 300')

    wrapper.unmount()
  })

  it('names the zone in text and not only by color', async () => {
    const { wrapper } = await bar({ score: 250 })

    expect(wrapper.get('[data-testid="zone"]').text()).toBe('permanent zone')
    expect(wrapper.get('[role="meter"]').attributes('data-zone')).toBe('permanent')

    wrapper.unmount()
  })

  it('omits the soft marker when the section has no soft threshold', async () => {
    const { wrapper } = await bar({ softThreshold: null })

    expect(wrapper.find('[data-testid="marker-soft"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="marker-permanent"]').exists()).toBe(true)

    wrapper.unmount()
  })
})
