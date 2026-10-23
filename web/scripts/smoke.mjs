// Container smoke check: runs the built web image against a throwaway upstream and asserts the
// behaviour the nginx image had (see openspec/changes/serve-web-with-nitro/design.md).
//   node scripts/smoke.mjs <image>        (docker must be available)
import { spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import assert from 'node:assert/strict'

const image = process.argv[2]
if (!image) {
  console.error('usage: node scripts/smoke.mjs <image>')
  process.exit(2)
}
const SOURCE = '3f2b8c1e-5d4a-4e7b-9a60-1c2d3e4f5a6b'
const name = `blackhole-web-smoke-${process.pid}`
const docker = (...args) => spawnSync('docker', args, { encoding: 'utf8' })

const seen = []
const upstream = createServer((req, res) => {
  seen.push({ url: req.url, forwardedFor: req.headers['x-forwarded-for'], forwardedProto: req.headers['x-forwarded-proto'] })
  res.setHeader('content-type', 'application/json')
  res.end('{"up":true}')
})
await new Promise((resolve) => upstream.listen(0, '0.0.0.0', resolve))
const upstreamPort = upstream.address().port

async function waitReady(base) {
  for (let i = 0; i < 60; i++) {
    try {
      if ((await fetch(`${base}/healthz`)).ok) return
    } catch {
      // not listening yet
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  const logs = docker('logs', name)
  throw new Error(`container never became healthy:\n${logs.stdout}${logs.stderr}`)
}

try {
  const run = docker(
    'run', '-d', '--rm', '--name', name, '-p', '127.0.0.1::8080',
    '--add-host', 'host.docker.internal:host-gateway',
    '-e', `BLACKHOLE_BACKEND_URL=http://host.docker.internal:${upstreamPort}`,
    '-e', `BLACKHOLE_UI_SOURCE=${SOURCE}`,
    image,
  )
  assert.equal(run.status, 0, run.stderr)
  const port = docker('port', name, '8080/tcp').stdout.trim().split('\n')[0].split(':').pop()
  const base = `http://127.0.0.1:${port}`
  await waitReady(base)

  const health = await fetch(`${base}/healthz`)
  assert.equal(health.status, 200)
  assert.equal(await health.text(), 'ok')

  const config = await fetch(`${base}/config.json`)
  assert.equal(config.headers.get('cache-control'), 'no-store')
  assert.deepEqual(await config.json(), { source: SOURCE, reviewer: SOURCE })

  const index = await fetch(`${base}/`)
  const html = await index.text()
  assert.equal(index.status, 200)
  assert.match(index.headers.get('cache-control') ?? '', /no-cache/)
  const deep = await fetch(`${base}/players/abc`)
  assert.equal(deep.status, 200)
  assert.equal(await deep.text(), html, 'SPA route serves index.html')

  const asset = html.match(/\/assets\/[^"']+\.js/)?.[0]
  assert.ok(asset, 'index.html references a hashed script')
  const cached = await fetch(`${base}${asset}`)
  assert.equal(cached.status, 200)
  assert.equal(cached.headers.get('cache-control'), 'public, max-age=31536000, immutable')
  assert.equal((await fetch(`${base}/assets/missing-0000.js`)).status, 404)

  for (const path of ['/', '/players/abc', asset]) {
    const { headers } = await fetch(`${base}${path}`)
    assert.equal(headers.get('x-content-type-options'), 'nosniff', path)
    assert.equal(headers.get('x-frame-options'), 'DENY', path)
    assert.equal(headers.get('referrer-policy'), 'no-referrer', path)
    assert.match(headers.get('content-security-policy') ?? '', /default-src 'self'/, path)
    assert.equal(headers.get('server'), null, path)
    assert.equal(headers.get('x-powered-by'), null, path)
  }

  const api = await fetch(`${base}/api/ping?x=1`)
  assert.equal(api.status, 200)
  assert.equal(api.headers.get('cache-control'), 'no-store')
  assert.deepEqual(await api.json(), { up: true })
  assert.equal(seen.at(-1)?.url, '/ping?x=1', 'prefix /api stripped, query kept')
  assert.equal(seen.at(-1)?.forwardedProto, 'http')
  assert.ok(seen.at(-1)?.forwardedFor, 'X-Forwarded-For set')

  const uid = docker('exec', name, 'id', '-u').stdout.trim()
  assert.notEqual(uid, '0', 'runs as non-root')

  // A missing operator UUID stops the container with a message instead of starting it.
  const bad = docker('run', '--rm', '-e', 'BLACKHOLE_BACKEND_URL=http://backend:8080', image)
  assert.notEqual(bad.status, 0)
  assert.match(bad.stderr, /BLACKHOLE_UI_SOURCE/)

  console.log('web image smoke check passed')
} finally {
  docker('rm', '-f', name)
  upstream.close()
}
