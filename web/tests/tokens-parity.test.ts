import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { renderMd3Css } from '../scripts/generate-md3-tokens.mjs'

// The committed-by-build file must be exactly what the generator writes from design/tokens/ today.
// A stale file (someone edited the seed or the generator and did not run `npm run generate`) fails here.
const GENERATED = join(import.meta.dirname, '../src/styles/md3-tokens.generated.css')

describe('design tokens parity', () => {
  // It runs the generator (which starts a Vite server), slower than the 5s default on windows-latest.
  it('the generated MD3 stylesheet matches the generator output for design/tokens/', async () => {
    const onDisk = readFileSync(GENERATED, 'utf8')

    expect(onDisk).toBe(await renderMd3Css())
  }, 30_000)
})
