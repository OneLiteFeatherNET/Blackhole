import { inject, type InjectionKey } from 'vue'
import type { BlackholeApi } from './blackhole'

export const API_KEY: InjectionKey<BlackholeApi> = Symbol('blackhole-api')

/** The API of the running app; tests provide a mock under {@link API_KEY} instead. */
export function useApi(): BlackholeApi {
  const api = inject(API_KEY)
  if (!api) throw new Error('No BlackholeApi provided; install it with app.provide(API_KEY, api)')
  return api
}
