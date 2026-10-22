import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'

const SCRIPT = join(import.meta.dirname, '40-config-json.sh')
const SOURCE = '3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b'
const REVIEWER = '0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d'

const dirs: string[] = []

/** Runs the container start script against a throwaway directory. */
function run(env: Record<string, string>) {
  const dir = mkdtempSync(join(tmpdir(), 'blackhole-config-'))
  dirs.push(dir)
  const result = spawnSync('sh', [SCRIPT], { env: { PATH: process.env.PATH ?? '', CONFIG_DIR: dir, ...env }, encoding: 'utf8' })
  return { ...result, file: join(dir, 'config.json') }
}

afterEach(() => {
  dirs.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true }))
})

// The script is POSIX sh for the nginx container; the Windows CI runner has no `sh` guarantee.
describe.skipIf(process.platform === 'win32')('40-config-json.sh', () => {
  it('writes the operator UUID and uses it as the reviewer by default', () => {
    const result = run({ BLACKHOLE_UI_SOURCE: SOURCE })

    expect(result.status, result.stderr).toBe(0)
    expect(JSON.parse(readFileSync(result.file, 'utf8'))).toEqual({ source: SOURCE, reviewer: SOURCE })
  })

  it('writes a separate reviewer when one is given', () => {
    const result = run({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_REVIEWER: REVIEWER })

    expect(result.status, result.stderr).toBe(0)
    expect(JSON.parse(readFileSync(result.file, 'utf8'))).toEqual({ source: SOURCE, reviewer: REVIEWER })
  })

  it('refuses to start without an operator UUID', () => {
    const result = run({})

    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('BLACKHOLE_UI_SOURCE must be a UUID')
  })

  it('refuses a value that is not a UUID, so nothing can be injected into the JSON', () => {
    const result = run({ BLACKHOLE_UI_SOURCE: `${SOURCE}","x":"y` })

    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('must be a UUID')
  })

  it('refuses an invalid reviewer', () => {
    const result = run({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_REVIEWER: 'nobody' })

    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('BLACKHOLE_UI_REVIEWER must be a UUID')
  })

  it('writes the evidence upload limits when they are set', () => {
    const result = run({
      BLACKHOLE_UI_SOURCE: SOURCE,
      BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: '2048',
      BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES: '4096',
      BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png,video/mp4',
    })

    expect(result.status, result.stderr).toBe(0)
    expect(JSON.parse(readFileSync(result.file, 'utf8')).evidence).toEqual({
      maxImageBytes: 2048,
      maxVideoBytes: 4096,
      allowedTypes: ['image/png', 'video/mp4'],
    })
  })

  it('refuses an evidence limit that is not a number, so nothing can be injected into the JSON', () => {
    const result = run({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES: '1,"x":"y' })

    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES must be a positive number')
  })

  it('refuses allowed types that are not MIME types', () => {
    const result = run({ BLACKHOLE_UI_SOURCE: SOURCE, BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES: 'image/png","x":"y' })

    expect(result.status).not.toBe(0)
    expect(result.stderr).toContain('BLACKHOLE_UI_EVIDENCE_ALLOWED_TYPES must be comma separated MIME types')
  })
})
