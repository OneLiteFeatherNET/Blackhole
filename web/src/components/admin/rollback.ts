import type { ApiError, ApiResult, RollbackProgress } from '../../api/types'
import { realKickTimer, type KickTimer } from '../kick/KickStatusPoller'

// Helpers of the staff rollback screen: the window and selector checks, the refusal texts and the progress poll.
// The poll takes its timer from outside, so a test drives it and no real clock is read.

/** How often a running rollback is read. */
export const ROLLBACK_POLL_INTERVAL_MS = 2000

/** The same injected timer the kick poller uses: `after(ms, run)` returns the cancel function. */
export type RollbackTimer = KickTimer
export const realRollbackTimer: RollbackTimer = realKickTimer

const MINECRAFT_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** The Minecraft UUID of an unlinked in-game account, dashed as the game and the proxies write it. */
export function isMinecraftUuid(value: string): boolean {
  return MINECRAFT_UUID.test(value.trim())
}

/** Both ends of a `datetime-local` window as epoch milliseconds (local time); null while the window is not usable. */
export function windowMillis(from: string, to: string): { from: number; to: number } | null {
  if (!from || !to) return null
  const start = new Date(from).getTime()
  const end = new Date(to).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end) return null
  return { from: start, to: end }
}

/** A job that will change no more: nothing to poll and nothing to cancel. */
export function isFinished(state: string): boolean {
  return state === 'COMPLETED' || state === 'CANCELLED'
}

// The refusals of the rollback routes. Other kinds keep the backend's own text.
const ERROR_KEYS: Partial<Record<string, string>> = {
  ROLLBACK_SELECTOR_INVALID: 'rollback.error.selectorInvalid',
  ROLLBACK_TOO_LARGE: 'rollback.error.tooLarge',
}

/** The translation key of a rollback refusal, or null when the screen should show the backend's text. */
export function rollbackErrorKey(error: ApiError): string | null {
  return ERROR_KEYS[error.kind] ?? null
}

export interface RollbackPollOptions {
  fetch: () => Promise<ApiResult<RollbackProgress>>
  timer: RollbackTimer
  /** Every answer, the last one included. */
  onProgress: (progress: RollbackProgress) => void
  /** A failed read ends the poll; the screen offers to start it again. */
  onError: (error: ApiError) => void
}

/**
 * Reads the job at once and then every {@link ROLLBACK_POLL_INTERVAL_MS} until it is finished or a read fails.
 * Returns the function that stops it (safe to call again).
 */
export function pollRollback(options: RollbackPollOptions): () => void {
  let stopped = false
  let cancel: () => void = () => {}

  async function read(): Promise<void> {
    const result = await options.fetch()
    if (stopped) return
    if (!result.ok) {
      stopped = true
      options.onError(result.error)
      return
    }
    options.onProgress(result.data)
    if (isFinished(result.data.state)) {
      stopped = true
      return
    }
    cancel = options.timer.after(ROLLBACK_POLL_INTERVAL_MS, () => void read())
  }

  void read()
  return () => {
    stopped = true
    cancel()
  }
}
