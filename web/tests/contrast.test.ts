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
  // Dialogs sit on surface-container-high, menus and step rows on surface-container, cards on -low/-highest.
  ['on-surface', 'surface-container-high', TEXT, 'dialog text and data table'],
  ['on-surface-variant', 'surface-container-high', TEXT, 'dialog hints and outlined field labels'],
  ['primary', 'surface-container-high', TEXT, 'text buttons and links in dialogs'],
  ['error', 'surface-container-high', TEXT, 'field errors in dialogs'],
  ['outline', 'surface-container-high', UI, 'field borders in dialogs'],
  ['primary', 'surface-container-high', UI, 'focused field border in dialogs'],
  ['on-surface', 'surface-container', TEXT, 'menu items, step rows'],
  ['on-surface-variant', 'surface-container', TEXT, 'menu supporting text, field labels in step rows'],
  ['outline', 'surface-container', UI, 'field borders in step rows'],
  ['on-secondary-container', 'secondary-container', TEXT, 'selected menu item'],
  ['on-surface', 'surface-container-low', TEXT, 'elevated cards'],
  ['on-surface-variant', 'surface-container-low', TEXT, 'elevated card hints'],
  ['primary', 'surface', UI, 'focus ring'],
  // The evidence panel: upload entries sit on surface-container, lifted items on surface-container-low.
  ['error', 'surface-container', TEXT, 'upload error text'],
  ['primary', 'surface-container-low', TEXT, 'links in lifted evidence items'],
  ['primary', 'surface-container', UI, 'upload progress bar'],
  // The case list: the table and the player groups sit on surface-container-low; outlined pills and chips border it.
  ['outline', 'surface-container-low', UI, 'outlined pills, tag chips and the segmented button on lifted cards and tables'],
  // success and warning: text and filled buttons, and the status pills on their containers.
  ['success', 'surface', TEXT, 'success text, lifted punishments'],
  ['warning', 'surface', TEXT, 'warning text, waiting and high-priority labels'],
  ['on-success', 'success', TEXT, 'filled success buttons'],
  ['on-warning', 'warning', TEXT, 'filled warning buttons'],
  ['on-success-container', 'success-container', TEXT, 'success pills'],
  ['on-warning-container', 'warning-container', TEXT, 'warning pills'],
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

/** The hue in degrees of a #rrggbb colour (0 for greys). */
function hueOf(hex: string): number {
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255) as [number, number, number]
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  if (d === 0) return 0
  const hue = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4
  return (hue * 60 + 360) % 360
}

/** The gap between two hues on the colour wheel, 0 to 180 degrees. */
function hueGap(a: string, b: string): number {
  const gap = Math.abs(hueOf(a) - hueOf(b))
  return Math.min(gap, 360 - gap)
}

/** The spread between the largest and smallest channel: 0 for a grey, larger for a stronger tint. */
function channelSpread(hex: string): number {
  const channels = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16))
  return Math.max(...channels) - Math.min(...channels)
}

// The surfaces the app stacks; neutral means the seed's tint no longer shows on them.
const SURFACES = [
  'background',
  'surface',
  'surface-container-lowest',
  'surface-container-low',
  'surface-container',
  'surface-container-high',
  'surface-container-highest',
]

describe.each(Object.entries(schemes))('%s scheme palette', (_name, colors) => {
  it.each(SURFACES)('keeps %s near-neutral (channel spread at most 10)', (role) => {
    expect(channelSpread(colors[role]!), role).toBeLessThanOrEqual(10)
  })

  it('keeps primary in the OLF pink hues (300 to 360 degrees)', () => {
    expect(hueOf(colors.primary!)).toBeGreaterThanOrEqual(300)
    expect(hueOf(colors.primary!)).toBeLessThanOrEqual(360)
  })

  it('gives secondary a hue at least 30 degrees from primary', () => {
    expect(hueGap(colors.primary!, colors.secondary!)).toBeGreaterThanOrEqual(30)
  })

  it('gives tertiary a hue at least 30 degrees from primary and secondary', () => {
    expect(hueGap(colors.primary!, colors.tertiary!)).toBeGreaterThanOrEqual(30)
    expect(hueGap(colors.secondary!, colors.tertiary!)).toBeGreaterThanOrEqual(30)
  })
})
