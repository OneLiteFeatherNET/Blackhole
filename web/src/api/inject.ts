import { inject, type InjectionKey } from 'vue'
import type { BlackholeApi } from './blackhole'
import { DEFAULT_EVIDENCE_LIMITS, type EvidenceLimits } from './config'

export const API_KEY: InjectionKey<BlackholeApi> = Symbol('blackhole-api')

/** The API of the running app; tests provide a mock under {@link API_KEY} instead. */
export function useApi(): BlackholeApi {
  const api = inject(API_KEY)
  if (!api) throw new Error('No BlackholeApi provided; install it with app.provide(API_KEY, api)')
  return api
}

export const EVIDENCE_LIMITS_KEY: InjectionKey<EvidenceLimits> = Symbol('evidence-limits')

/** The upload pre-check limits of /config.json; the backend defaults when the app provides none. */
export function useEvidenceLimits(): EvidenceLimits {
  return inject(EVIDENCE_LIMITS_KEY, DEFAULT_EVIDENCE_LIMITS)
}
