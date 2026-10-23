import { ConfigError, parseUiConfig, type UiConfig } from './config'

export interface StartupDeps {
  exit: (code: number) => never
  error: (message: string) => void
}

/** Parses the environment; on invalid input prints every problem and exits non-zero before listening. */
export function loadUiConfigOrExit(env: Record<string, string | undefined>, deps: StartupDeps): UiConfig {
  try {
    return parseUiConfig(env)
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error
    deps.error(`blackhole-web: invalid configuration, refusing to start:\n${error.problems.map((p) => `  - ${p}`).join('\n')}`)
    return deps.exit(1)
  }
}
