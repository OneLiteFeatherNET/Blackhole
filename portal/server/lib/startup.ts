import { ConfigError, parsePortalConfig, type PortalConfig } from './config'

export interface StartupDeps {
  exit: (code: number) => never
  error: (message: string) => void
  /** Logs a warning that does not stop startup. Optional: a missing sink drops the warning. */
  warn?: (message: string) => void
  /** The clock the security.txt expiry is checked against. Defaults to the system clock. */
  now?: () => Date
}

/** Parses the environment; on invalid input prints every problem and exits non-zero before listening. */
export function loadPortalConfigOrExit(env: Record<string, string | undefined>, deps: StartupDeps): PortalConfig {
  try {
    const config = parsePortalConfig(env, deps.now?.() ?? new Date())
    if (config.securityTxt === undefined) {
      deps.warn?.('blackhole-portal: BLACKHOLE_SECURITY_CONTACT is not set, /.well-known/security.txt answers 404')
    }
    return config
  } catch (error) {
    if (!(error instanceof ConfigError)) throw error
    deps.error(`blackhole-portal: invalid configuration, refusing to start:\n${error.problems.map((p) => `  - ${p}`).join('\n')}`)
    return deps.exit(1)
  }
}
