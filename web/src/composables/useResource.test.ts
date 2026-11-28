import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it } from 'vitest'
import type { ApiResult } from '../api/types'
import { useResource, type Resource } from './useResource'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

/** Mounts a component that owns one resource, so the test controls when the component unmounts. */
function mountOwning(fetcher: () => Promise<ApiResult<number>>) {
  let resource!: Resource<number>
  const wrapper = mount(
    defineComponent({
      setup() {
        resource = useResource(fetcher)
        return () => h('div')
      },
    }),
  )
  return { wrapper, resource: () => resource }
}

describe('useResource', () => {
  it('loads the data once the owning component is mounted', async () => {
    const { resource } = mountOwning(async () => ({ ok: true, data: 7 }))

    await flushPromises()

    expect(resource().data.value, 'the data of the first read').toBe(7)
  })

  it('drops a read that finishes after its component was unmounted', async () => {
    const read = deferred<ApiResult<number>>()
    const { wrapper, resource } = mountOwning(() => read.promise)

    wrapper.unmount()
    read.resolve({ ok: true, data: 7 })
    await flushPromises()

    expect(resource().data.value, 'no screen is left to show a read that ended after unmount').toBeNull()
  })
})
