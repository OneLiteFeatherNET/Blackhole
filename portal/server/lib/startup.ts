import { ConfigError, parsePortalConfig, type PortalConfig } from './config'

export interface StartupDeps {
  exit: (code: number) => never
  error: (message: string) => void
}

/** Parses the environment; on invalid input prints every problem and exits non-zero before listening. */
export function loadPortalConfigOrExit(env: Record<string, string | undefined>, deps: StartupDeps): PortalConfig {
  try {
    return parsePortalConfig(env)
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error
    deps.error(`blackhole-portal: invalid configuration, refusing to start:\n${error.problems.map((p) => `  - ${p}`).join('\n')}`)
    return deps.exit(1)
  }
}
