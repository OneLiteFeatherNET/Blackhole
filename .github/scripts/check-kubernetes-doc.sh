#!/usr/bin/env bash
# Consistency checks for docs/kubernetes.md, so the runbook cannot drift from the code unnoticed:
#   1. every environment variable named in backticks exists in the code, a Dockerfile, the chart or the compose
#      file (the NATS names come from the change that introduces them: openspec/changes/switch-event-bus-to-nats);
#   2. every relative link points at a file that exists;
#   3. no key material, JWT or private key is pasted into the page;
#   4. only the decided hostnames appear (plus the public services the runbook names).
# Plain grep; no network. Run: .github/scripts/check-kubernetes-doc.sh   (from any directory)

set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
doc="${root}/docs/kubernetes.md"
failures=0

fail() {
  failures=$((failures + 1))
  echo "FAIL: $*" >&2
}

if [[ ! -f "${doc}" ]]; then
  echo "FAIL: ${doc} does not exist" >&2
  exit 1
fi

# --- 1. environment variables exist ---------------------------------------------------------------------------
search_roots=(
  "${root}/backend/src/main"
  "${root}/discord/src/main"
  "${root}/discord/Dockerfile"
  "${root}/web/server"
  "${root}/web/src"
  "${root}/web/Dockerfile"
  "${root}/portal/server"
  "${root}/portal/src"
  "${root}/portal/Dockerfile"
  "${root}/velocity/src/main"
  "${root}/docker/docker-compose.yml"
  "${root}/deploy/charts/blackhole"
  "${root}/openspec/changes/switch-event-bus-to-nats"
)
existing_roots=()
for path in "${search_roots[@]}"; do
  [[ -e "${path}" ]] && existing_roots+=("${path}")
done

env_prefix='^(BLACKHOLE_|DISCORD_|JDBC_|REDIS_|NATS_|OTEL_|NITRO_|CLAMD_CONF_|LOG_JSON$|JAVA_TOOL_OPTIONS$|PORT$)'
checked=0
while IFS= read -r token; do
  [[ "${token}" =~ ${env_prefix} ]] || continue
  [[ "${token}" == *_ ]] && continue # a family prefix such as BLACKHOLE_UI_
  checked=$((checked + 1))
  if ! grep -rqF -- "${token}" "${existing_roots[@]}"; then
    fail "environment variable ${token} is named in docs/kubernetes.md but exists nowhere in the code, the chart or the compose file"
  fi
done < <(grep -oE '`[A-Za-z][A-Za-z0-9_]*`' "${doc}" | tr -d '`' | grep -E '^[A-Z][A-Z0-9_]+$' | sort -u)
if [[ "${checked}" -lt 20 ]]; then
  fail "only ${checked} environment variables were checked; the runbook should name many more (is the pattern broken?)"
fi

# --- 2. relative links resolve --------------------------------------------------------------------------------------
doc_dir="$(dirname "${doc}")"
while IFS= read -r link; do
  target="${link%%#*}"
  [[ -z "${target}" ]] && continue
  case "${target}" in
    http://* | https://* | mailto:*) continue ;;
  esac
  [[ -e "${doc_dir}/${target}" ]] || fail "broken relative link: ${link}"
done < <(grep -oE '\]\([^)]+\)' "${doc}" | sed -E 's/^\]\(//; s/\)$//')

# --- 3. no secret material --------------------------------------------------------------------------------------------
if grep -nE -- '-----BEGIN|eyJ[A-Za-z0-9_-]{10,}\.|AKIA[0-9A-Z]{16}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "${doc}"; then
  fail "docs/kubernetes.md contains something that looks like key material, a token or a tenant/client id"
fi

# --- 4. hostnames ------------------------------------------------------------------------------------------------------
allowed_hosts='^(blackhole|appeal|blackhole-api)\.onelitefeather\.net$|^(harbor|nats)\.onelitefeather\.dev$|^(blackhole-dev|appeal-dev)\.apps\.onelite\.feather$|^login\.microsoftonline\.com$|^database\.clamav\.net$|^(github|raw\.githubusercontent|get\.helm)\.(com|sh)$|^example\.(com|net|org|invalid)$|^[a-z0-9-]+\.(md|sh|yml|yaml|json|txt|conf|java|kts|tpl)$'
while IFS= read -r host; do
  [[ "${host}" =~ ${allowed_hosts} ]] || fail "hostname ${host} is not one of the decided hosts"
done < <(grep -oE '[a-z0-9][a-z0-9.-]*\.(net|dev|feather|com|org|io|sh)\b' "${doc}" | sort -u)

echo "check-kubernetes-doc.sh: ${checked} environment variables checked, ${failures} problems"
[[ "${failures}" -eq 0 ]]
