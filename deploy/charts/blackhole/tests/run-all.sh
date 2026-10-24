#!/usr/bin/env bash
# Runs every test script of the chart (all tests/*.sh except the helpers) and fails if any of them does.
# Pinned tools: helm v4.3.0, yq v4.53.6. CI also runs kubeconform v0.8.0 (see .github/workflows/build-pr.yml).
set -uo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
status=0
for script in "${dir}"/*.sh; do
  case "$(basename "${script}")" in
    lib.sh | run-all.sh) continue ;;
  esac
  echo "== $(basename "${script}")"
  bash "${script}" || status=1
done
exit "${status}"
