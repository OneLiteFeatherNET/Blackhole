#!/usr/bin/env bash
# Tests for chart-version.sh: the chart versions and the dev image tag are computed, not guessed, and they
# sort the way the consumers need (Flux follows the highest semver chart and the highest numeric image tag).
# Plain shell and python3 -I (semver comparison); no network, no registry, no clock: the timestamp is an argument.
# Run: .github/scripts/chart-version.test.sh

set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${here}/chart-version.sh"
failures=0
checks=0

check() { # <message> <expected> <actual>
  checks=$((checks + 1))
  if [[ "$2" != "$3" ]]; then
    failures=$((failures + 1))
    echo "FAIL: $1 (expected '$2', got '$3')" >&2
  fi
}

value() { # <key> <args...> -> the value of one output line
  local key="$1"
  shift
  "${script}" "$@" | sed -n "s/^${key}=//p"
}

# semver_less <a> <b>: exit 0 when a < b by the semver 2.0 precedence rules
semver_less() {
  python3 -I - "$1" "$2" <<'PY'
import sys

def key(version):
    core, _, pre = version.partition("-")
    nums = tuple(int(p) for p in core.split("."))
    if not pre:
        return (nums, (1,))
    ids = tuple((0, int(i), "") if i.isdigit() else (1, 0, i) for i in pre.split("."))
    return (nums, (0, ids))

sys.exit(0 if key(sys.argv[1]) < key(sys.argv[2]) else 1)
PY
}

# --- a release version -----------------------------------------------------------------------------------------
check "release chart version of a release" "1.2.0" "$(value release_version 1.2.0 1790000000 abcdef1234567890)"
check "dev chart version after a release is the next patch" "1.2.1-main.1790000000" "$(value dev_version 1.2.0 1790000000 abcdef1234567890)"
check "dev image tag" "main-1790000000-abcdef1" "$(value image_tag 1.2.0 1790000000 abcdef1234567890)"

# --- a SNAPSHOT base version: the upcoming version, not the one after it -----------------------------------------
check "release chart version of a snapshot" "1.0.1" "$(value release_version 1.0.1-SNAPSHOT 1790000000 abcdef1234567890)"
check "dev chart version of a snapshot" "1.0.1-main.1790000000" "$(value dev_version 1.0.1-SNAPSHOT 1790000000 abcdef1234567890)"
check "dev chart version of a BETA base" "2.0.0-main.1790000000" "$(value dev_version 2.0.0-BETA 1790000000 abcdef1234567890)"
check "a longer sha is cut to seven characters" "main-1790000000-0123456" "$(value image_tag 1.0.1-SNAPSHOT 1790000000 0123456789abcdef0123456789abcdef01234567)"

# --- ordering ------------------------------------------------------------------------------------------------------
older="$(value dev_version 1.0.1-SNAPSHOT 999999999 aaaaaaa)"
newer="$(value dev_version 1.0.1-SNAPSHOT 1000000000 bbbbbbb)"
semver_less "${older}" "${newer}" && ok=yes || ok=no
check "main.999999999 sorts below main.1000000000 as semver (numeric, not text)" "yes" "${ok}"
semver_less "1.0.1-main.9" "1.0.1-main.10" && ok=yes || ok=no
check "main.9 sorts below main.10 as semver" "yes" "${ok}"
semver_less "1.0.0" "$(value dev_version 1.0.0 1790000000 abcdef1)" && ok=yes || ok=no
check "a dev chart is newer than the release it follows" "yes" "${ok}"
semver_less "$(value dev_version 1.0.1-SNAPSHOT 1790000000 abcdef1)" "1.0.1" && ok=yes || ok=no
check "a snapshot dev chart is older than the release it leads to" "yes" "${ok}"
semver_less "$(value dev_version 1.0.0 1790000000 abcdef1)" "1.0.1" && ok=yes || ok=no
check "a dev chart is older than the next release" "yes" "${ok}"

# The image tag must match the filter Flux's ImagePolicy uses, and the number must order like the timestamp.
tag="$(value image_tag 1.0.1-SNAPSHOT 1790000000 abcdef1234567890)"
[[ "${tag}" =~ ^main-([0-9]+)-[0-9a-f]{7}$ ]] && ok=yes || ok=no
check "the image tag matches ^main-(?P<ts>[0-9]+)-[0-9a-f]{7}\$" "yes" "${ok}"
check "the captured number is the commit time" "1790000000" "${BASH_REMATCH[1]:-}"

# --- bad input fails, it never yields a half-valid tag ----------------------------------------------------------------
for bad in "notaversion 1790000000 abcdef1" "1.0.0 notanumber abcdef1" "1.0.0 1790000000 xyz" "1.0.0 1790000000 abc" "1.0 1790000000 abcdef1" ""; do
  # shellcheck disable=SC2086
  if "${script}" ${bad} >/dev/null 2>&1; then
    failures=$((failures + 1))
    echo "FAIL: '${bad}' must be rejected" >&2
  fi
  checks=$((checks + 1))
done

echo "chart-version.test.sh: ${checks} checks, ${failures} failed"
[[ "${failures}" -eq 0 ]]
