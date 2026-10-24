import { beforeAll } from 'vitest'

// Runs once per test file, before its first test. Importing the helpers pulls in App.vue and the whole view
// graph (module transform), and one throwaway mount pays for jsdom and the first component render. Without it
// that cost lands inside the first test and can exceed the timeout on a loaded CI runner.
// hookTimeout covers this hook, so the cost no longer counts against any test. The mount is discarded; every
// test still builds its own fixture.
// The server tests run in the node environment (no DOM) and need none of this.
beforeAll(async () => {
  if (typeof document === 'undefined') return
  const { fakeApi, mountPortal, ok, SESSION } = await import('./helpers')
  const { wrapper } = await mountPortal(fakeApi({ session: async () => ok(SESSION) }), { signedIn: false })
  wrapper.unmount()
}, 60_000)
