#!/usr/bin/env bash
# Validates every rendered fixture against the Kubernetes JSON schemas with kubeconform (-strict: unknown
# fields are errors, which is where the typos live). It never contacts a cluster.
# Run: deploy/charts/blackhole/tests/kubeconform.sh   (needs helm v4.3.0 and kubeconform v0.8.0 on PATH)
#
# This is the one test that needs the network: kubeconform downloads the schemas. The built-in resources
# come from its default location; the three CRDs the chart renders (ServiceMonitor, PodMonitor, HTTPRoute) come
# from the Datree CRDs catalog, pinned to a commit so a later schema change cannot turn a green run red.
# -ignore-missing-schemas is deliberately NOT used: a kind without a schema must fail the run.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

command -v kubeconform >/dev/null 2>&1 || {
  echo "missing tool: kubeconform (v0.8.0)" >&2
  exit 2
}

CRD_CATALOG_COMMIT="d373c2da9702bc9509a004db83e57263fe3bdfc1"
CRD_SCHEMAS="https://raw.githubusercontent.com/datreeio/CRDs-catalog/${CRD_CATALOG_COMMIT}/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json"

for fixture in "${CI_DIR}"/*-values.yaml; do
  name="$(basename "${fixture}")"
  render_to "${name}" "${WORK_DIR}/${name}"
  if out=$(kubeconform -strict -summary -schema-location default -schema-location "${CRD_SCHEMAS}" "${WORK_DIR}/${name}" 2>&1); then
    pass
  else
    fail "kubeconform rejected ${name}: ${out}"
  fi
done

# The fixtures must actually contain the CRD kinds, otherwise the catalog location is never exercised.
for kind in ServiceMonitor PodMonitor HTTPRoute; do
  if grep -lq "^kind: ${kind}\$" "${WORK_DIR}"/*-values.yaml; then pass; else fail "no fixture renders a ${kind}"; fi
done

finish
