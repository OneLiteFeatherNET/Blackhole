import type { ApiResult, KickResult } from '../../api/types'

/** How often the kick is read while it is PENDING. */
export const KICK_POLL_INTERVAL_MS = 1000
/** How long past the backend's deadline the poller keeps asking, so the derived final status can arrive. */
export const KICK_POLL_GRACE_MS = 1000

/** The only source of waiting: injected, so a test drives time itself and the poller never touches a global timer. */
export interface KickTimer {
  /** Runs `run` once after `ms`; the returned function cancels it. */
  after(ms: number, run: () => void): () => void
}

/** The timer of the running app. */
export const realKickTimer: KickTimer = {
  after(ms, run) {
    const id = setTimeout(run, ms)
    return () => clearTimeout(id)
  },
}

export type KickPollOutcome =
  | { kind: 'settled'; kick: KickResult }
  | { kind: 'timeout' }
  | { kind: 'error'; message: string }

export interface KickPollOptions {
  fetch: () => Promise<ApiResult<KickResult>>
  timer: KickTimer
  now: () => number
  /** `deadlineAt` of the accepted kick, epoch milliseconds. */
  deadlineAt: number
  /** Called with every answer that is still PENDING. */
  onPending?: (kick: KickResult) => void
  /** Called exactly once, unless the poll was stopped first. */
  onDone: (outcome: KickPollOutcome) => void
}

/**
 * Reads a kick every second until its status is no longer PENDING, the deadline plus one second has passed, or a
 * request fails. Returns the function that stops it (and is safe to call again).
 */
export function pollKick(options: KickPollOptions): () => void {
  let stopped = false
  let cancel: () => void = () => {}

  const finish = (outcome: KickPollOutcome): void => {
    if (stopped) return
    stopped = true
    options.onDone(outcome)
  }

  const schedule = (): void => {
    cancel = options.timer.after(KICK_POLL_INTERVAL_MS, () => void read())
  }

  async function read(): Promise<void> {
    const result = await options.fetch()
    if (stopped) return
    if (!result.ok) return finish({ kind: 'error', message: result.error.message })
    if (result.data.status !== 'PENDING') return finish({ kind: 'settled', kick: result.data })
    options.onPending?.(result.data)
    if (options.now() >= options.deadlineAt + KICK_POLL_GRACE_MS) return finish({ kind: 'timeout' })
    schedule()
  }

  schedule()
  return () => {
    stopped = true
    cancel()
  }
}
