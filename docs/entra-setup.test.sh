#!/bin/bash
# Tests for write_env in docs/entra-setup.sh. Sources the script (no az, no network) and rewrites
# a fake env file in a temp dir. Run: bash docs/entra-setup.test.sh
set -uo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=entra-setup.sh
source "$DIR/entra-setup.sh"

FAILS=0
fail() { echo "FAIL: $1" >&2; FAILS=$((FAILS + 1)); }
pass() { echo "ok: $1"; }

# Fixed fixture values; each test gets its own temp dir.
set_fixture() {
  ENVNAME=dev; TENANT=new-tenant; API=api-id; SPA=spa-id; VEL=vel-id; BOT=bot-id
  G_ADMIN=g-admin; G_EDIT=g-edit; G_VIEW=g-view; G_OPS=g-ops
  VEL_SECRET=new-velocity-secret; BOT_SECRET=new-bot-secret # as if az created them
}

mode_of() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }

count_key() { grep -c "^$2=" "$1" || true; }

test_unmanaged_keys_survive_rewrite() {
  local t out; t=$(mktemp -d); out="$t/entra-dev.env"; set_fixture
  printf '%s\n' '# old header' '' 'BLACKHOLE_AUTH_TENANT_ID=old-tenant' \
    'BLACKHOLE_BANID_KEY=s3cret-banid' 'DISCORD_TOKEN=discord-token' 'DISCORD_GUILD_ID=12345' \
    'BLACKHOLE_CLIENT_SECRET=kept-velocity-secret' > "$out"
  write_env "$out"
  if [ "$(grep '^BLACKHOLE_BANID_KEY=' "$out")" = "BLACKHOLE_BANID_KEY=s3cret-banid" ] \
     && [ "$(grep '^DISCORD_TOKEN=' "$out")" = "DISCORD_TOKEN=discord-token" ] \
     && [ "$(grep '^DISCORD_GUILD_ID=' "$out")" = "DISCORD_GUILD_ID=12345" ]; then
    pass "unmanaged keys survive a rewrite"
  else
    fail "unmanaged keys survive a rewrite: $(cat "$out")"
  fi
  rm -rf "$t"
}

test_managed_key_is_updated() {
  local t out; t=$(mktemp -d); out="$t/entra-dev.env"; set_fixture
  printf '%s\n' 'BLACKHOLE_AUTH_TENANT_ID=old-tenant' > "$out"
  write_env "$out"
  if [ "$(grep '^BLACKHOLE_AUTH_TENANT_ID=' "$out")" = "BLACKHOLE_AUTH_TENANT_ID=new-tenant" ] \
     && [ "$(count_key "$out" BLACKHOLE_AUTH_TENANT_ID)" = 1 ]; then
    pass "managed key is updated and not duplicated"
  else
    fail "managed key is updated and not duplicated: $(cat "$out")"
  fi
  rm -rf "$t"
}

test_existing_secret_is_reused() {
  local t out; t=$(mktemp -d); out="$t/entra-dev.env"; set_fixture
  printf '%s\n' 'BLACKHOLE_CLIENT_SECRET=kept-velocity-secret' > "$out"
  VEL_SECRET="" # the script only creates a secret when the env file has none
  write_env "$out"
  if [ "$(grep '^BLACKHOLE_CLIENT_SECRET=' "$out")" = "BLACKHOLE_CLIENT_SECRET=kept-velocity-secret" ] \
     && [ "$(count_key "$out" BLACKHOLE_CLIENT_SECRET)" = 1 ]; then
    pass "existing Velocity secret is reused once"
  else
    fail "existing Velocity secret is reused once: $(cat "$out")"
  fi
  rm -rf "$t"
}

test_file_mode_is_600() {
  local t out; t=$(mktemp -d); out="$t/entra-dev.env"; set_fixture
  printf '%s\n' 'DISCORD_TOKEN=x' > "$out"
  chmod 644 "$out"
  write_env "$out"
  if [ "$(mode_of "$out")" = 600 ]; then
    pass "file mode is 600"
  else
    fail "file mode is 600, got $(mode_of "$out")"
  fi
  rm -rf "$t"
}

test_fresh_file_is_created() {
  local t out; t=$(mktemp -d); out="$t/sub/entra-dev.env"; set_fixture
  mkdir -p "$t/sub"
  write_env "$out"
  if [ -f "$out" ] && [ "$(count_key "$out" BLACKHOLE_AUTH_ROLE_ADMIN)" = 1 ] && [ "$(mode_of "$out")" = 600 ]; then
    pass "fresh file is created with managed keys and mode 600"
  else
    fail "fresh file is created with managed keys and mode 600"
  fi
  rm -rf "$t"
}

test_unmanaged_keys_survive_rewrite
test_managed_key_is_updated
test_existing_secret_is_reused
test_file_mode_is_600
test_fresh_file_is_created

if [ "$FAILS" -ne 0 ]; then echo "$FAILS test(s) failed" >&2; exit 1; fi
echo "all tests passed"
