// Container smoke check: runs the built portal image against a throwaway backend and asserts the
// behaviour the BFF promises (allow-list, cookies, CSRF, uniform login error, headers, fail-fast config).
//   node scripts/smoke.mjs <image>        (docker must be available)
import { spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import assert from 'node:assert/strict'

const image = process.argv[2]
if (!image) {
  console.error('usage: node scripts/smoke.mjs <image>')
  process.exit(2)
}
const name = `blackhole-portal-smoke-${process.pid}`
const docker = (...args) => spawnSync('docker', args, { encoding: 'utf8' })
const BAN_ID = 'K7M29QX4'
const TOKEN = 'smoke-token-0123456789abcdef'

const seen = []
const upstream = createServer((req, res) => {
  let body = ''
  req.on('data', (chunk) => (body += chunk))
  req.on('end', () => {
    seen.push({ method: req.method, url: req.url, authorization: req.headers.authorization, body })
    res.setHeader('content-type', 'application/json')
    if (req.method === 'POST' && req.url === '/public/session') {
      if (JSON.parse(body).banId === BAN_ID) {
        res.end(JSON.stringify({ token: TOKEN, csrf: 'smoke-csrf-0123', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), scope: 'FULL' }))
      } else {
        res.statusCode = 401
        res.end('{"detail":"nope"}')
      }
    } else if (req.url === '/public/punishment') {
      res.end('{"reference":"Case #1"}')
    } else {
      res.statusCode = 404
      res.end('{}')
    }
  })
})
await new Promise((resolve) => upstream.listen(0, '0.0.0.0', resolve))
const upstreamPort = upstream.address().port
const ORIGIN = 'https://appeal.example.org'

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

const post = (base, path, body, headers = {}) =>
  fetch(`${base}${path}`, { method: 'POST', body: JSON.stringify(body), headers: { 'content-type': 'application/json', ...headers } })

try {
  const run = docker(
    'run', '-d', '--rm', '--name', name, '-p', '127.0.0.1::8080',
    '--add-host', 'host.docker.internal:host-gateway',
    '-e', `BLACKHOLE_BACKEND_URL=http://host.docker.internal:${upstreamPort}`,
    '-e', `BLACKHOLE_PORTAL_ORIGIN=${ORIGIN}`,
    image,
  )
  assert.equal(run.status, 0, run.stderr)
  const port = docker('port', name, '8080/tcp').stdout.trim().split('\n')[0].split(':').pop()
  const base = `http://127.0.0.1:${port}`
  await waitReady(base)

  const health = await fetch(`${base}/healthz`)
  assert.equal(await health.text(), 'ok')

  const index = await fetch(`${base}/`)
  const html = await index.text()
  assert.equal(index.status, 200)
  assert.match(index.headers.get('cache-control') ?? '', /no-cache/)
  const asset = html.match(/\/assets\/[^"']+\.js/)?.[0]
  assert.ok(asset, 'index.html references a hashed script')

  for (const path of ['/', asset, '/api/nothing']) {
    const { headers } = await fetch(`${base}${path}`)
    assert.equal(headers.get('x-content-type-options'), 'nosniff', path)
    assert.equal(headers.get('x-frame-options'), 'DENY', path)
    assert.equal(headers.get('referrer-policy'), 'no-referrer', path)
    assert.match(headers.get('content-security-policy') ?? '', /default-src 'self'.*frame-ancestors 'none'/, path)
    assert.equal(headers.get('server'), null, path)
    assert.equal(headers.get('x-powered-by'), null, path)
  }

  const before = seen.length
  assert.equal((await fetch(`${base}/api/case`)).status, 404, 'unknown path')
  assert.equal(seen.length, before, 'nothing was forwarded')

  const noOrigin = await post(base, '/api/session', { banId: BAN_ID })
  assert.equal(noOrigin.status, 403, 'state-changing call without Origin')
  const foreign = await post(base, '/api/session', { banId: BAN_ID }, { origin: 'https://evil.example' })
  assert.equal(foreign.status, 403, 'foreign Origin')
  assert.equal(seen.length, before, 'still nothing forwarded')

  const wrong = await post(base, '/api/session', { banId: 'ZZZZZZZZ' }, { origin: ORIGIN })
  const unknown = await post(base, '/api/session', { banId: '00000000' }, { origin: ORIGIN })
  assert.equal(wrong.status, 401)
  assert.equal(await wrong.text(), await unknown.text(), 'uniform login error')

  const login = await post(base, '/api/session', { banId: BAN_ID }, { origin: ORIGIN })
  assert.equal(login.status, 200)
  const cookies = login.headers.getSetCookie()
  const session = cookies.find((c) => c.startsWith('__Host-bh_portal='))
  assert.ok(session, 'session cookie set')
  for (const flag of ['HttpOnly', 'Secure', 'SameSite=Lax', 'Path=/']) assert.ok(session.includes(flag), flag)
  assert.ok(!session.toLowerCase().includes('domain='), 'no Domain')
  assert.ok(!(await login.text()).includes(TOKEN), 'token not in the body')
  assert.ok(!seen.some((s) => s.url.includes(BAN_ID)), 'Ban ID in no URL')

  const cookieHeader = cookies.map((c) => c.split(';')[0]).join('; ')
  const facts = await fetch(`${base}/api/punishment`, { headers: { cookie: cookieHeader } })
  assert.equal(facts.status, 200)
  assert.equal(facts.headers.get('cache-control'), 'no-store')
  assert.equal(seen.at(-1)?.authorization, `Bearer ${TOKEN}`, 'token attached server-side')

  const logs = docker('logs', name)
  assert.ok(!`${logs.stdout}${logs.stderr}`.includes(BAN_ID), 'Ban ID in no log')

  const uid = docker('exec', name, 'id', '-u').stdout.trim()
  assert.notEqual(uid, '0', 'runs as non-root')

  // Missing or insecure configuration stops the container with a message instead of starting it.
  const bad = docker('run', '--rm', '-e', 'BLACKHOLE_PORTAL_ORIGIN=http://appeal.example.org', image)
  assert.notEqual(bad.status, 0)
  assert.match(bad.stderr, /BLACKHOLE_PORTAL_ORIGIN/)

  console.log('portal image smoke check passed')
} finally {
  docker('rm', '-f', name)
  upstream.close()
}
