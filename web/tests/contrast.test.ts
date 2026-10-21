import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

// The generated tokens (npm run generate, run before every test) are the colors the UI really uses.
const css = readFileSync(join(import.meta.dirname, '../src/styles/md3-tokens.generated.css'), 'utf8')

type Palette = Record<string, string>

function palette(block: string): Palette {
  return Object.fromEntries([...block.matchAll(/--md-sys-color-([a-z-]+):\s*(#[0-9a-f]{6})/gi)].map((m) => [m[1]!, m[2]!]))
}

// The file holds three blocks in this order: light, dark by system preference, dark by override.
const media = css.indexOf('@media (prefers-color-scheme: dark)')
const override = css.indexOf(':root[data-theme="dark"]')
const schemes: Record<string, Palette> = {
  light: palette(css.slice(0, media)),
  dark: palette(css.slice(media, override)),
  'dark (override)': palette(css.slice(override)),
}

/** WCAG 2.x relative luminance contrast ratio of two #rrggbb colors. */
function contrast(a: string, b: string): number {
  const luminance = (hex: string) => {
    const [r, g, bl] = [1, 3, 5].map((i) => {
      const channel = parseInt(hex.slice(i, i + 2), 16) / 255
      return channel <= 0.03928 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
    }) as [number, number, number]
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl
  }
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number]
  return (hi + 0.05) / (lo + 0.05)
}

// [foreground, background, minimum ratio, where the pair is used]
const TEXT = 4.5
const UI = 3
const PAIRS: Array<[string, string, number, string]> = [
  ['on-surface', 'surface', TEXT, 'body text'],
  ['on-surface-variant', 'surface', TEXT, 'secondary text, labels'],
  ['on-surface-variant', 'surface-container-highest', TEXT, 'secondary text on cards and pills'],
  ['on-surface', 'surface-container-highest', TEXT, 'text on filled cards'],
  ['primary', 'surface', TEXT, 'links and text buttons'],
  ['on-primary', 'primary', TEXT, 'filled buttons'],
  ['on-primary-container', 'primary-container', TEXT, 'primary pills'],
  ['on-secondary-container', 'secondary-container', TEXT, 'tonal buttons, selected chips'],
  ['on-tertiary-container', 'tertiary-container', TEXT, 'warning notes, temporary pills'],
  ['on-error-container', 'error-container', TEXT, 'errors and warnings'],
  ['error', 'surface', TEXT, 'field error text'],
  ['inverse-on-surface', 'inverse-surface', TEXT, 'snackbar'],
  ['inverse-primary', 'inverse-surface', TEXT, 'snackbar action'],
  ['outline', 'surface', UI, 'text field and select borders'],
  ['primary', 'surface', UI, 'focus ring'],
]

describe.each(Object.entries(schemes))('%s scheme contrast', (_name, colors) => {
  it('defines every color the checks use', () => {
    for (const [foreground, background] of PAIRS) {
      expect(colors[foreground], foreground).toBeDefined()
      expect(colors[background], background).toBeDefined()
    }
  })

  it.each(PAIRS)('%s on %s reaches %s:1 (%s)', (foreground, background, minimum) => {
    expect(contrast(colors[foreground]!, colors[background]!)).toBeGreaterThanOrEqual(minimum)
  })
})
