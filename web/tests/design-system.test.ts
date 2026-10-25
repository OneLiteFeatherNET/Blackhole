import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'

// Design section 2 of openspec/changes/redesign-ux: retired classes must not come back in web/src.
const WEB = join(import.meta.dirname, '..')

// Baseline keys are posix paths ("src/..."), on every platform.
const posix = (file: string) => relative(WEB, file).split(sep).join('/')
const SRC = join(WEB, 'src')
const BASELINE = join(WEB, 'tests/design-baseline.json')
const MAIN_CSS = join(SRC, 'styles/main.css')

const SPACING_PREFIX = 'p|px|py|pt|pb|pl|pr|m|mx|my|mt|mb|ml|mr|gap|gap-x|gap-y|space-x|space-y'

const RETIRED_PATTERNS: RegExp[] = [
  // p-5, gap-5, mt-10 ... and the half steps on any utility (`*-0.5`, `*-1.5`).
  new RegExp(`(?<!\\w)(?:${SPACING_PREFIX})-(?:5|10)(?![\\w.-])`),
  new RegExp(`(?<!\\w)[a-z][a-z0-9-]*-(?:0\\.5|1\\.5)(?![\\w.])`),
  // Arbitrary spacing values, e.g. p-[13px].
  new RegExp(`(?<!\\w)(?:${SPACING_PREFIX})-\\[`),
  // Type roles that were retired from the ramp.
  /(?<![\w-])text-(?:title-large|headline-medium|headline-large|label-small)(?![\w-])/,
]

/** Lines of a .vue file that use a retired class. */
function violationsIn(file: string, source: string): string[] {
  const lines = source.split('\n')
  const hits: string[] = []
  lines.forEach((line, index) => {
    const retired =
      RETIRED_PATTERNS.some((pattern) => pattern.test(line)) ||
      // rounded-md-sm is for nothing but buttons and chips' former shape; both now use rounded-full.
      (/rounded-md-sm/.test(line) && /button|chip/i.test(`${file} ${line}`))
    if (retired) hits.push(`${posix(file)}:${index + 1}`)
  })
  return hits
}

function vueFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return vueFiles(path)
    return name.endsWith('.vue') ? [path] : []
  })
}

function filesUsingRetiredClasses(): Map<string, string[]> {
  const result = new Map<string, string[]>()
  for (const file of vueFiles(SRC)) {
    const hits = violationsIn(file, readFileSync(file, 'utf8'))
    if (hits.length > 0) result.set(posix(file), hits)
  }
  return result
}

function baseline(): string[] {
  return (JSON.parse(readFileSync(BASELINE, 'utf8')) as { files: string[] }).files
}

describe('design system contract', () => {
  it('no file outside the baseline uses a retired class', () => {
    const allowed = new Set(baseline())
    const offenders = [...filesUsingRetiredClasses()].filter(([file]) => !allowed.has(file))

    expect(offenders, 'retired classes in files that are not in tests/design-baseline.json').toEqual([])
  })

  it('the baseline names only files that still use a retired class', () => {
    const current = new Set(filesUsingRetiredClasses().keys())
    const stale = baseline().filter((file) => !current.has(file))

    expect(stale, 'files migrated: remove them from tests/design-baseline.json').toEqual([])
  })

  it('defines the spacing role tokens of design section 2 on a 4 px base', () => {
    const css = readFileSync(MAIN_CSS, 'utf8')
    const expected: Record<string, string> = {
      'space-1': '4px',
      'space-2': '8px',
      'space-3': '12px',
      'space-4': '16px',
      'space-6': '24px',
      'space-8': '32px',
    }

    for (const [name, value] of Object.entries(expected)) {
      expect(css, `--${name} in main.css`).toMatch(new RegExp(`--${name}:\\s*${value};`))
    }
  })
})
