import { describe, expect, it, vi } from 'vitest'
import type { ApiResult, KickResult, KickStatus } from '../../api/types'
import { pollKick, type KickPollOutcome, type KickTimer } from './KickStatusPoller'

const DEADLINE = 10_000

function result(status: KickStatus): ApiResult<KickResult> {
  return { ok: true, data: { id: 'k', kind: 'PLAYER', scope: 'NETWORK', reason: 'r', status, deliveredCount: 0, createdAt: 0, deadlineAt: DEADLINE } }
}

/** A timer the test drives; the clock moves with it. */
function manual() {
  const waiting: Array<{ at: number; run: () => void; cancelled: boolean }> = []
  const clock = { now: 0 }
  const timer: KickTimer = {
    after(ms, run) {
      const entry = { at: clock.now + ms, run, cancelled: false }
      waiting.push(entry)
      return () => {
        entry.cancelled = true
      }
    },
  }
  return {
    timer,
    clock,
    /** Moves one second ahead and runs what is due, then lets the pending promises settle. */
    async second() {
      clock.now += 1000
      for (const entry of waiting.splice(0)) if (!entry.cancelled && entry.at <= clock.now) entry.run()
      await new Promise((resolve) => setImmediate(resolve))
    },
    get pending() {
      return waiting.filter((entry) => !entry.cancelled).length
    },
  }
}

function start(statuses: KickStatus[]) {
  const time = manual()
  const queue = [...statuses]
  const fetch = vi.fn(async () => result(queue.shift() ?? 'PENDING'))
  const done = vi.fn<(outcome: KickPollOutcome) => void>()
  const stop = pollKick({ fetch, timer: time.timer, now: () => time.clock.now, deadlineAt: DEADLINE, onDone: done })
  return { time, fetch, done, stop }
}

describe('pollKick', () => {
  it('asks after one second, not at once', () => {
    const { fetch } = start(['DELIVERED'])

    expect(fetch).not.toHaveBeenCalled()
  })

  it('settles with the first answer that is not PENDING and schedules nothing more', async () => {
    const { time, fetch, done } = start(['PENDING', 'DELIVERED'])

    await time.second()
    await time.second()

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(done).toHaveBeenCalledWith(expect.objectContaining({ kind: 'settled' }))
    expect(time.pending, 'no timer left').toBe(0)
  })

  it('times out one second after the deadline', async () => {
    const { time, done } = start([])

    for (let second = 0; second < 10; second++) await time.second()
    expect(done, 'still inside the grace second').not.toHaveBeenCalled()
    await time.second()

    expect(done).toHaveBeenCalledWith({ kind: 'timeout' })
  })

  it('reports a failed request once and stops', async () => {
    const time = manual()
    const done = vi.fn()
    pollKick({
      fetch: async () => ({ ok: false, error: { status: 0, message: 'offline', kind: 'NETWORK' } }),
      timer: time.timer,
      now: () => time.clock.now,
      deadlineAt: DEADLINE,
      onDone: done,
    })

    await time.second()

    expect(done).toHaveBeenCalledWith({ kind: 'error', message: 'offline' })
    expect(time.pending).toBe(0)
  })

  it('does not report after it was stopped', async () => {
    const { time, done, stop } = start(['DELIVERED'])

    stop()
    await time.second()

    expect(done).not.toHaveBeenCalled()
  })
})
