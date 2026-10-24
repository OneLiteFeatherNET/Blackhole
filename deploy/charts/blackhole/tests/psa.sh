#!/usr/bin/env bash
# Every pod the chart renders passes the Pod Security "restricted" profile and sets resources.
# kubeconform cannot check this (it validates schemas, not policy) and `kubectl --dry-run=server` needs a
# cluster, so the fields of the profile are asserted with yq on the rendered manifests.
# Run: deploy/charts/blackhole/tests/psa.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

check_restricted() { # <label> <manifests>
  local label="$1" file="$2" pods='select(.kind == "Deployment") | .spec.template.spec'
  local containers='(.containers[], .initContainers[]?)'
  assert_eq "${label}: at least one Deployment" "true" "$([[ "$(y "${file}" '[select(.kind == "Deployment")] | length')" -gt 0 ]] && echo true || echo false)"
  assert_eq "${label}: every pod runs as non-root" "0" "$(y "${file}" "[${pods} | select(.securityContext.runAsNonRoot != true)] | length")"
  assert_eq "${label}: every pod has an explicit non-root user" "0" "$(y "${file}" "[${pods} | select((.securityContext.runAsUser // 0) == 0)] | length")"
  assert_eq "${label}: every pod uses the RuntimeDefault seccomp profile" "0" "$(y "${file}" "[${pods} | select(.securityContext.seccompProfile.type != \"RuntimeDefault\")] | length")"
  assert_eq "${label}: no privilege escalation" "0" "$(y "${file}" "[${pods} | ${containers} | select(.securityContext.allowPrivilegeEscalation != false)] | length")"
  assert_eq "${label}: all capabilities dropped" "0" "$(y "${file}" "[${pods} | ${containers} | select(.securityContext.capabilities.drop[0] != \"ALL\")] | length")"
  assert_eq "${label}: no capability added" "0" "$(y "${file}" "[${pods} | ${containers} | select(.securityContext.capabilities.add != null)] | length")"
  assert_eq "${label}: read-only root file system" "0" "$(y "${file}" "[${pods} | ${containers} | select(.securityContext.readOnlyRootFilesystem != true)] | length")"
  assert_eq "${label}: no privileged container" "0" "$(y "${file}" "[${pods} | ${containers} | select(.securityContext.privileged == true)] | length")"
  assert_eq "${label}: no host namespaces" "0" "$(y "${file}" "[${pods} | select(.hostNetwork == true or .hostPID == true or .hostIPC == true)] | length")"
  assert_eq "${label}: no hostPath volumes" "0" "$(y "${file}" "[${pods} | .volumes[]? | select(.hostPath != null)] | length")"
  assert_eq "${label}: no service account token is mounted" "0" "$(y "${file}" "[${pods} | select(.automountServiceAccountToken != false)] | length")"
  assert_eq "${label}: CPU request on every container" "0" "$(y "${file}" "[${pods} | ${containers} | select(.resources.requests.cpu == null)] | length")"
  assert_eq "${label}: memory request on every container" "0" "$(y "${file}" "[${pods} | ${containers} | select(.resources.requests.memory == null)] | length")"
  assert_eq "${label}: memory limit on every container" "0" "$(y "${file}" "[${pods} | ${containers} | select(.resources.limits.memory == null)] | length")"
  assert_eq "${label}: no image uses the latest tag or none" "0" "$(y "${file}" "[${pods} | ${containers} | select((.image | test(\":latest\$\")) or ((.image | test(\":\")) | not))] | length")"
  assert_eq "${label}: the JVM workloads have a startupProbe" "0" "$(y "${file}" "[select(.kind == \"Deployment\" and (.metadata.name | test(\"-(backend|discord)\$\"))) | .spec.template.spec.containers[0] | select(.startupProbe == null)] | length")"
  assert_eq "${label}: /tmp is writable through an emptyDir" "0" "$(y "${file}" "[select(.kind == \"Deployment\") | .spec.template.spec | select([.volumes[]? | select(.emptyDir != null)] | length == 0)] | length")"
}

for fixture in prod-values.yaml dev-values.yaml nginx-values.yaml local-values.yaml; do
  render_to "${fixture}" "${WORK_DIR}/${fixture}"
  check_restricted "${fixture}" "${WORK_DIR}/${fixture}"
done

# The documented ClamAV exception: only with clamav.unprivileged=false (the image's root entrypoint) does a
# pod leave the restricted profile. Everything else stays restricted.
root="${WORK_DIR}/clamav-root.yaml"
render_to prod-values.yaml "${root}" --set clamav.unprivileged=false
clam='select(.kind == "Deployment" and .metadata.name == "t-clamav") | .spec.template.spec'
assert_eq "root-entrypoint ClamAV drops the non-root requirement" "null" "$(y "${root}" "${clam} | .securityContext.runAsNonRoot")"
assert_eq "root-entrypoint ClamAV still uses seccomp" "RuntimeDefault" "$(y "${root}" "${clam} | .securityContext.seccompProfile.type")"
assert_eq "root-entrypoint ClamAV uses the image entrypoint" "null" "$(y "${root}" "${clam} | .containers[0].command")"
assert_eq "root-entrypoint ClamAV mounts no clamd.conf" "0" "$(y "${root}" "[${clam} | .volumes[] | select(.name == \"config\")] | length")"
assert_eq "the other pods stay restricted when ClamAV is the exception" "0" "$(y "${root}" '[select(.kind == "Deployment" and .metadata.name != "t-clamav") | .spec.template.spec | select(.securityContext.runAsNonRoot != true)] | length')"

finish
