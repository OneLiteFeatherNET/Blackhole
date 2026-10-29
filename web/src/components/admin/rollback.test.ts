import { describe, expect, it } from 'vitest'
import { i18n } from '../../i18n'
import type { ApiError, ApiResult, RollbackProgress } from '../../api/types'
import { isFinished, ROLLBACK_POLL_INTERVAL_MS, rollbackErrorKey, windowMillis, isMinecraftUuid, pollRollback, type RollbackTimer } from './rollback'

const JOB = '6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f'

/** A timer that records what was scheduled; the test decides when it fires. */
interface Scheduled {
  ms: number
  run: () => void
  cancelled: boolean
  fired: boolean
}

function manualTimer() {
  const scheduled: Scheduled[] = []
  const timer: RollbackTimer = {
    after(ms, run) {
      const entry: Scheduled = { ms, run, cancelled: false, fired: false }
      scheduled.push(entry)
      return () => {
        entry.cancelled = true
      }
    },
  }
  const fire = () => {
    const next = scheduled.find((entry) => !entry.cancelled && !entry.fired)
    if (!next) throw new Error('nothing scheduled')
    next.fired = true
    next.run()
  }
  return { timer, scheduled, fire }
}

const progress = (state: string, items: Record<string, number> = {}): RollbackProgress => ({ jobId: JOB, state, items })

/** Lets the poller's pending reads settle. */
async function settle() {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

describe('rollbackErrorKey', () => {
  it.each([
    ['ROLLBACK_SELECTOR_INVALID', 'rollback.error.selectorInvalid'],
    ['ROLLBACK_TOO_LARGE', 'rollback.error.tooLarge'],
  ] as const)('maps %s to its message', (kind, key) => {
    const error: ApiError = { status: 400, message: 'backend text', kind }

    expect(rollbackErrorKey(error)).toBe(key)
  })

  it('gives every mapped message an English text', () => {
    for (const kind of ['ROLLBACK_SELECTOR_INVALID', 'ROLLBACK_TOO_LARGE'] as const) {
      const key = rollbackErrorKey({ status: 400, message: '', kind })!
      expect(i18n.global.t(key, 'en'), kind).not.toBe(key)
    }
  })

  it('returns null for any other kind, so the screen shows the backend text', () => {
    expect(rollbackErrorKey({ status: 409, message: 'already started', kind: 'CONFLICT' })).toBeNull()
  })
})

describe('windowMillis', () => {
  it('reads both ends of a datetime-local window as epoch milliseconds', () => {
    expect(windowMillis('2026-10-01T10:00', '2026-10-02T10:00')).toEqual({
      from: new Date('2026-10-01T10:00').getTime(),
      to: new Date('2026-10-02T10:00').getTime(),
    })
  })

  it('is null while either end is empty', () => {
    expect(windowMillis('', '2026-10-02T10:00')).toBeNull()
    expect(windowMillis('2026-10-01T10:00', '')).toBeNull()
  })

  it('is null when the window ends before it starts', () => {
    expect(windowMillis('2026-10-02T10:00', '2026-10-01T10:00')).toBeNull()
  })
})

describe('isMinecraftUuid', () => {
  it('accepts the dashed form in any letter case', () => {
    expect(isMinecraftUuid('069A79F4-44E9-4726-A5BE-FCA90E38AAF5')).toBe(true)
  })

  it('refuses a name and a UUID without dashes', () => {
    expect(isMinecraftUuid('Notch')).toBe(false)
    expect(isMinecraftUuid('069a79f444e94726a5befca90e38aaf5')).toBe(false)
  })
})

describe('isFinished', () => {
  it('is true for COMPLETED and CANCELLED only', () => {
    expect(isFinished('COMPLETED')).toBe(true)
    expect(isFinished('CANCELLED')).toBe(true)
    expect(isFinished('RUNNING')).toBe(false)
    expect(isFinished('PREVIEW')).toBe(false)
  })
})

describe('pollRollback', () => {
  it('reads at once and then every interval while the job runs', async () => {
    const { timer, scheduled, fire } = manualTimer()
    const reads: string[] = []
    const fetch = async (): Promise<ApiResult<RollbackProgress>> => ({ ok: true, data: progress('RUNNING') })

    pollRollback({ fetch, timer, onProgress: (p) => reads.push(p.state), onError: () => {} })
    await settle()
    fire()
    await settle()

    expect(reads).toEqual(['RUNNING', 'RUNNING'])
    expect(scheduled[0]!.ms).toBe(ROLLBACK_POLL_INTERVAL_MS)
  })

  it.each(['COMPLETED', 'CANCELLED'])('stops when the job is %s', async (state) => {
    const { timer, scheduled } = manualTimer()
    const fetch = async (): Promise<ApiResult<RollbackProgress>> => ({ ok: true, data: progress(state) })
    const reads: string[] = []

    pollRollback({ fetch, timer, onProgress: (p) => reads.push(p.state), onError: () => {} })
    await settle()

    expect(reads, 'the final state is reported').toEqual([state])
    expect(scheduled, 'nothing is scheduled after the end').toHaveLength(0)
  })

  it('stops and reports the error when a read fails', async () => {
    const { timer, scheduled } = manualTimer()
    const errors: ApiError[] = []
    const fetch = async (): Promise<ApiResult<RollbackProgress>> => ({ ok: false, error: { status: 0, message: 'offline', kind: 'NETWORK' } })

    pollRollback({ fetch, timer, onProgress: () => {}, onError: (error) => errors.push(error) })
    await settle()

    expect(errors.map((error) => error.message)).toEqual(['offline'])
    expect(scheduled).toHaveLength(0)
  })

  it('does nothing more after it was stopped', async () => {
    const { timer, scheduled } = manualTimer()
    const fetch = async (): Promise<ApiResult<RollbackProgress>> => ({ ok: true, data: progress('RUNNING') })

    const stop = pollRollback({ fetch, timer, onProgress: () => {}, onError: () => {} })
    await settle()
    stop()

    expect(scheduled[0]!.cancelled).toBe(true)
  })
})
