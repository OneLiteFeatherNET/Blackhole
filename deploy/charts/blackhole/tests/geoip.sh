#!/usr/bin/env bash
# The offline GeoIP databases: the read-only volume of the backend and the monthly refresh CronJob.
# Run: deploy/charts/blackhole/tests/geoip.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The release name is "t": the backend is t-backend, the refresh job t-geoip-refresh.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prod="${WORK_DIR}/prod.yaml"
render_to prod-values.yaml "${prod}"

# --- off by default: nothing of the feature is rendered -----------------------------------------------------
assert_eq "no GeoIP setting in the default render" "0" "$(grep -ci 'geoip' "${prod}" || true)"
assert_eq "no refresh CronJob in the default render" "0" "$(y "${prod}" '[select(.kind == "CronJob")] | length')"

# --- the backend mounts the claim read-only and is pointed at the files ---------------------------------------
on="${WORK_DIR}/on.yaml"
render_to prod-values.yaml "${on}" --set backend.geoip.enabled=true --set backend.geoip.existingClaim=blackhole-geoip
backend='select(.kind == "Deployment" and .metadata.name == "t-backend") | .spec.template.spec'
assert_eq "the backend pod has a volume for the claim" "blackhole-geoip" \
  "$(y "${on}" "${backend} | .volumes[] | select(.name == \"geoip\") | .persistentVolumeClaim.claimName")"
assert_eq "the backend mounts the volume read-only" "true" \
  "$(y "${on}" "${backend} | .containers[0].volumeMounts[] | select(.name == \"geoip\") | .readOnly")"
assert_eq "the backend mounts the volume at the default path" "/data/geoip" \
  "$(y "${on}" "${backend} | .containers[0].volumeMounts[] | select(.name == \"geoip\") | .mountPath")"
assert_eq "the backend reads the ASN file from the volume" "/data/geoip/asn.mmdb" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_INTEL_GEOIP_ASN_PATH)"
assert_eq "the backend reads the country file through the city setting" "/data/geoip/country.mmdb" \
  "$(configmap_value "${on}" t-backend BLACKHOLE_INTEL_GEOIP_CITY_PATH)"
assert_eq "the backend pod group owns the volume" "10001" "$(y "${on}" "${backend} | .securityContext.fsGroup")"
assert_eq "the backend has no refresh job without the refresh switch" "0" "$(y "${on}" '[select(.kind == "CronJob")] | length')"
assert_eq "the backend keeps its own security context otherwise" "10001" "$(y "${on}" "${backend} | .securityContext.runAsUser")"

custom="${WORK_DIR}/custom.yaml"
render_to prod-values.yaml "${custom}" --set backend.geoip.enabled=true --set backend.geoip.existingClaim=c \
  --set backend.geoip.mountPath=/mnt/geo/
assert_eq "a custom mount path reaches the mount and the paths" "/mnt/geo/asn.mmdb" \
  "$(configmap_value "${custom}" t-backend BLACKHOLE_INTEL_GEOIP_ASN_PATH)"
assert_eq "a trailing slash in the mount path is not doubled" "/mnt/geo/country.mmdb" \
  "$(configmap_value "${custom}" t-backend BLACKHOLE_INTEL_GEOIP_CITY_PATH)"

# --- the refresh job: monthly, pinned, no secrets, the same claim, restricted ---------------------------------
refreshed="${WORK_DIR}/refresh.yaml"
render_to prod-values.yaml "${refreshed}" --set backend.geoip.enabled=true --set backend.geoip.existingClaim=blackhole-geoip \
  --set backend.geoip.refresh.enabled=true
job='select(.kind == "CronJob" and .metadata.name == "t-geoip-refresh")'
pod="${job} | .spec.jobTemplate.spec.template.spec"
container="${pod} | .containers[0]"
assert_eq "the refresh job exists once" "1" "$(y "${refreshed}" '[select(.kind == "CronJob")] | length')"
assert_eq "the refresh runs monthly on the 2nd at 04:00" "0 4 2 * *" "$(y "${refreshed}" "${job} | .spec.schedule")"
assert_eq "the refresh schedule is in UTC" "UTC" "$(y "${refreshed}" "${job} | .spec.timeZone")"
assert_eq "a run that overlaps the previous one is skipped" "Forbid" "$(y "${refreshed}" "${job} | .spec.concurrencyPolicy")"
assert_eq "the refresh mounts the same claim as the backend" "blackhole-geoip" \
  "$(y "${refreshed}" "${pod} | .volumes[] | select(.name == \"geoip\") | .persistentVolumeClaim.claimName")"
assert_eq "the refresh writes the volume (not read-only)" "null" \
  "$(y "${refreshed}" "${container} | .volumeMounts[] | select(.name == \"geoip\") | .readOnly")"
assert_eq "the refresh writes to the same path the backend reads" "/data/geoip" \
  "$(y "${refreshed}" "${container} | .volumeMounts[] | select(.name == \"geoip\") | .mountPath")"
assert_eq "the refresh has no secret in its environment" "0" \
  "$(y "${refreshed}" "[${container} | .env[]? | select(.valueFrom != null)] | length")"
assert_eq "the refresh has no envFrom" "null" "$(y "${refreshed}" "${container} | .envFrom")"
assert_eq "the refresh image is pinned by tag" "docker.io/curlimages/curl:8.11.1" "$(y "${refreshed}" "${container} | .image")"
assert_eq "the refresh downloads from the DB-IP free base" "https://download.db-ip.com/free" \
  "$(y "${refreshed}" "${container} | .env[] | select(.name == \"GEOIP_BASE_URL\") | .value")"
assert_eq "the refresh does not retry forever" "2" "$(y "${refreshed}" "${job} | .spec.jobTemplate.spec.backoffLimit")"
assert_eq "the refresh job runs as non-root" "true" "$(y "${refreshed}" "${pod} | .securityContext.runAsNonRoot")"
assert_eq "the refresh job has a read-only root file system" "true" "$(y "${refreshed}" "${container} | .securityContext.readOnlyRootFilesystem")"
assert_eq "the refresh job drops all capabilities" "ALL" "$(y "${refreshed}" "${container} | .securityContext.capabilities.drop[0]")"
assert_eq "the refresh job forbids privilege escalation" "false" "$(y "${refreshed}" "${container} | .securityContext.allowPrivilegeEscalation")"
assert_eq "the refresh job uses the RuntimeDefault seccomp profile" "RuntimeDefault" "$(y "${refreshed}" "${pod} | .securityContext.seccompProfile.type")"
assert_eq "the refresh job mounts no service account token" "false" "$(y "${refreshed}" "${pod} | .automountServiceAccountToken")"
assert_eq "the refresh job has a memory limit" "128Mi" "$(y "${refreshed}" "${container} | .resources.limits.memory")"
assert_match "the refresh job fetches both files" <(y "${refreshed}" "${container} | .command[2]") 'fetch asn asn.mmdb'
assert_match "the refresh job fetches the country file" <(y "${refreshed}" "${container} | .command[2]") 'fetch country country.mmdb'
assert_match "the refresh job writes through a partial name and renames it" <(y "${refreshed}" "${container} | .command[2]") '\.part'

# --- switches and required values -------------------------------------------------------------------------------
expect_render_failure "a claim is required with the feature on" "backend.geoip.existingClaim" prod-values.yaml \
  --set backend.geoip.enabled=true
expect_render_failure "the refresh needs the feature on" "backend.geoip.refresh.enabled needs backend.geoip.enabled" prod-values.yaml \
  --set backend.geoip.refresh.enabled=true
if render prod-values.yaml --set backend.geoip.enabled=true --set backend.geoip.existingClaim=c --set backend.enabled=false \
  --set web.backendUrl=http://x.example.invalid:8080 --set portal.backendUrl=http://x.example.invalid:8080 \
  --set discord.backendUrl=http://x.example.invalid:8080 >/dev/null 2>&1; then
  pass
else
  fail "the feature must render with the backend disabled"
fi
disabled_refresh="${WORK_DIR}/no-refresh.yaml"
render_to prod-values.yaml "${disabled_refresh}" --set backend.geoip.enabled=true --set backend.geoip.existingClaim=c
assert_eq "the refresh stays off by default" "0" "$(y "${disabled_refresh}" '[select(.kind == "CronJob")] | length')"

# --- the config can still override the derived paths ----------------------------------------------------------------
overridden="${WORK_DIR}/overridden.yaml"
render_to prod-values.yaml "${overridden}" --set backend.geoip.enabled=true --set backend.geoip.existingClaim=c \
  --set backend.config.BLACKHOLE_INTEL_GEOIP_ASN_PATH=/elsewhere/asn.mmdb
assert_eq "backend.config wins over the derived ASN path" "/elsewhere/asn.mmdb" \
  "$(configmap_value "${overridden}" t-backend BLACKHOLE_INTEL_GEOIP_ASN_PATH)"

finish
