#!/usr/bin/env bash
# Shared helpers for the chart tests. Source it, do not run it.
#
# The tests need no cluster, no network and no registry: they only run `helm template` / `helm lint`
# over the fixed value files in ../ci and look at the output with grep and yq. They are deterministic
# and independent of each other (every test renders what it needs into its own temp directory).
#
# Tool versions the CI job (and the author) used; the CI job installs exactly these:
#   helm v4.3.0 (schema errors are JSON-Pointer paths, e.g. /database/existingSecret, since the
#   jsonschema/v6 validator), yq v4.53.6 (mikefarah), kubeconform v0.8.0.

set -euo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CHART_DIR="$(cd "${TESTS_DIR}/.." && pwd)"
CI_DIR="${CHART_DIR}/ci"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT

FAILURES=0
CHECKS=0

for tool in helm yq; do
  command -v "${tool}" >/dev/null 2>&1 || {
    echo "missing tool: ${tool} (see the version pins in tests/lib.sh)" >&2
    exit 2
  }
done

# render <values-file-name|-> [helm args...]  -> manifests on stdout.
# The first argument is a file in ci/ (without the directory) or "-" for no file.
render() {
  local values="$1"
  shift
  local args=()
  if [[ "${values}" != "-" ]]; then
    args+=(-f "${CI_DIR}/${values}")
  fi
  helm template t "${CHART_DIR}" "${args[@]}" "$@"
}

# render_to <values-file> <out-file> [helm args...]; aborts the test run when the render fails.
render_to() {
  local values="$1" out="$2"
  shift 2
  if ! render "${values}" "$@" >"${out}" 2>"${out}.err"; then
    echo "render of ${values} failed:" >&2
    cat "${out}.err" >&2
    exit 1
  fi
}

pass() {
  CHECKS=$((CHECKS + 1))
}

fail() {
  CHECKS=$((CHECKS + 1))
  FAILURES=$((FAILURES + 1))
  echo "FAIL: $*" >&2
}

# assert_eq <message> <expected> <actual>
assert_eq() {
  if [[ "$2" == "$3" ]]; then pass; else fail "$1 (expected '$2', got '$3')"; fi
}

# assert_contains <message> <file> <fixed string>
assert_contains() {
  if grep -qF -- "$3" "$2"; then pass; else fail "$1 (missing '$3')"; fi
}

# assert_not_contains <message> <file> <fixed string>
assert_not_contains() {
  if grep -qF -- "$3" "$2"; then fail "$1 (found '$3')"; else pass; fi
}

# assert_match <message> <file> <extended regex>
assert_match() {
  if grep -qE -- "$3" "$2"; then pass; else fail "$1 (no match for /$3/)"; fi
}

# assert_no_match <message> <file> <extended regex>
assert_no_match() {
  if grep -qE -- "$3" "$2"; then fail "$1 (matched /$3/)"; else pass; fi
}

# yq_count <file> <yq expression>  -> number of documents or nodes the expression returns
yq_count() {
  yq eval-all "$2" "$1" | grep -c . || true
}

# select_doc <file> <kind> <name>  -> that one document on stdout
select_doc() {
  yq eval "select(.kind == \"$2\" and .metadata.name == \"$3\")" "$1"
}

# expect_render_failure <message> <error pattern> <values-file|-> [helm args...]
# Passes only when `helm template` fails AND the error mentions the pattern. A render that fails for an
# unrelated reason must not pass as "the schema rejected it".
expect_render_failure() {
  local message="$1" pattern="$2" values="$3"
  shift 3
  local err
  if err=$(render "${values}" "$@" 2>&1 >/dev/null); then
    fail "${message} (the render succeeded)"
    return
  fi
  if grep -qE -- "${pattern}" <<<"${err}"; then
    pass
  else
    fail "${message} (it failed, but not for the expected reason /${pattern}/): ${err}"
  fi
}

finish() {
  echo "$(basename "$0"): ${CHECKS} checks, ${FAILURES} failed"
  [[ "${FAILURES}" -eq 0 ]]
}
