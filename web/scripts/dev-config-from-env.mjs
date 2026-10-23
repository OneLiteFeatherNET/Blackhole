// Writes public/config.local.json (git-ignored) for `npm run dev` from the same variables the
// container reads, so no tenant or client id ever lands in the repository:
//   set -a; . ~/.config/blackhole/entra-dev.env; set +a; npm run dev:config
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

const names = ['BLACKHOLE_UI_OIDC_AUTHORITY', 'BLACKHOLE_UI_OIDC_CLIENT_ID', 'BLACKHOLE_UI_API_SCOPE']
const missing = names.filter((name) => !process.env[name])
if (missing.length > 0) {
  console.error(`dev-config: missing ${missing.join(', ')}; load your env file first (see web/README.md)`)
  process.exit(1)
}

const target = join(import.meta.dirname, '../public/config.local.json')
const config = {
  oidc: {
    authority: process.env.BLACKHOLE_UI_OIDC_AUTHORITY.replace(/\/+$/, ''),
    clientId: process.env.BLACKHOLE_UI_OIDC_CLIENT_ID,
    apiScope: process.env.BLACKHOLE_UI_API_SCOPE,
  },
}
writeFileSync(target, `${JSON.stringify(config, null, 2)}\n`)
console.log('dev-config: wrote public/config.local.json (git-ignored)')
