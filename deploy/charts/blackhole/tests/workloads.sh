#!/usr/bin/env bash
# The workloads: env and Secret wiring, probes, strategies, PDBs, ClamAV, observability labels and monitors.
# Run: deploy/charts/blackhole/tests/workloads.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
# The release name in these renders is "t", so the objects are t-backend, t-web, t-portal, t-discord, t-clamav.

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prod="${WORK_DIR}/prod.yaml"
dev="${WORK_DIR}/dev.yaml"
render_to prod-values.yaml "${prod}"
render_to dev-values.yaml "${dev}"

dep() { echo "select(.kind == \"Deployment\" and .metadata.name == \"$1\")"; }

# --- backend: secrets only through secretKeyRef ---------------------------------------------------------
secret_ref() { # <env name> <expected secret> <expected key>
  local entry
  entry="$(pod_env "${prod}" t-backend "$1")"
  assert_eq "backend $1 comes from Secret $2" "$2" "$(yq eval '.valueFrom.secretKeyRef.name' - <<<"${entry}")"
  assert_eq "backend $1 reads key $3" "$3" "$(yq eval '.valueFrom.secretKeyRef.key' - <<<"${entry}")"
  assert_eq "backend $1 has no literal value" "null" "$(yq eval '.value' - <<<"${entry}")"
}
secret_ref JDBC_URL blackhole-db JDBC_URL
secret_ref JDBC_USER blackhole-db JDBC_USER
secret_ref JDBC_PASSWORD blackhole-db JDBC_PASSWORD
secret_ref REDIS_URI blackhole-redis REDIS_URI
secret_ref BLACKHOLE_BANID_KEY blackhole-keys BLACKHOLE_BANID_KEY
secret_ref BLACKHOLE_EVASION_IP_SALT blackhole-keys BLACKHOLE_EVASION_IP_SALT
secret_ref BLACKHOLE_EVIDENCE_S3_ACCESS_KEY blackhole-s3 BLACKHOLE_EVIDENCE_S3_ACCESS_KEY
secret_ref BLACKHOLE_EVIDENCE_S3_SECRET_KEY blackhole-s3 BLACKHOLE_EVIDENCE_S3_SECRET_KEY

for file in "${prod}" "${dev}"; do
  literal_secrets="$(y "${file}" '[select(.kind == "Deployment") | .spec.template.spec.containers[].env[]? | select(.name | test("(PASSWORD|SECRET|TOKEN|SALT|_KEY|REDIS_URI)$")) | select(.value != null)] | length')"
  assert_eq "no secret-looking env variable carries a literal value ($(basename "${file}"))" "0" "${literal_secrets}"
  secret_data="$(y "${file}" '[select(.kind == "ConfigMap") | .data | keys | .[] | select(test("(PASSWORD|SECRET|TOKEN|SALT|_KEY|REDIS_URI)$"))] | length')"
  assert_eq "no ConfigMap holds a secret-looking key ($(basename "${file}"))" "0" "${secret_data}"
  assert_eq "the chart renders no Secret object ($(basename "${file}"))" "0" "$(y "${file}" '[select(.kind == "Secret")] | length')"
  assert_eq "no RabbitMQ setting is rendered ($(basename "${file}"))" "0" "$(grep -ci 'rabbit' "${file}" || true)"
done
assert_eq "no RabbitMQ setting in the prod render" "0" "$(grep -ci 'rabbit' "${prod}" || true)"
assert_eq "no Dragonfly host in the prod render" "0" "$(grep -ci 'dragonfly' "${prod}" || true)"

# --- backend: config, NATS, scanner, tracing -----------------------------------------------------------------
cm() { configmap_value "${prod}" t-backend "$1"; }
assert_eq "NATS url" "nats://nats.nats.svc:4222" "$(cm NATS_URL)"
assert_eq "NATS credentials file is the mounted key" "/etc/blackhole/nats/nats.creds" "$(cm NATS_CREDS_FILE)"
assert_eq "NATS stream replicas" "3" "$(cm NATS_STREAM_REPLICAS)"
assert_eq "video limit is an integer, not 9.4e+07" "94371840" "$(cm BLACKHOLE_EVIDENCE_FILES_MAX_VIDEO_BYTES)"
assert_eq "evidence storage is s3" "s3" "$(cm BLACKHOLE_EVIDENCE_FILES_STORAGE)"
assert_eq "scan is on" "true" "$(cm BLACKHOLE_EVIDENCE_SCAN_ENABLED)"
assert_eq "scan host resolves to the ClamAV Service of the release" "t-clamav" "$(cm BLACKHOLE_EVIDENCE_SCAN_CLAMD_HOST)"
assert_eq "scan port" "3310" "$(cm BLACKHOLE_EVIDENCE_SCAN_CLAMD_PORT)"
assert_eq "issuer is derived from the tenant" "https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/v2.0" "$(cm BLACKHOLE_AUTH_ISSUER)"
assert_eq "insecure issuers stay off" "false" "$(cm BLACKHOLE_AUTH_ALLOW_INSECURE_ISSUER)"
assert_eq "traces go to the collector" "otlp" "$(cm OTEL_TRACES_EXPORTER)"
assert_eq "service name" "blackhole" "$(cm OTEL_SERVICE_NAME)"
assert_eq "dev scan host is the external scanner" "blackhole-clamav.blackhole.svc" "$(configmap_value "${dev}" t-backend BLACKHOLE_EVIDENCE_SCAN_CLAMD_HOST)"
assert_eq "dev overlay config reaches the ConfigMap" "true" "$(configmap_value "${dev}" t-backend BLACKHOLE_DOCS_ENABLED)"

tracing_off="${WORK_DIR}/tracing-off.yaml"
render_to prod-values.yaml "${tracing_off}" --set backend.tracing.enabled=false
assert_eq "no tracing exporter when tracing is off" "null" "$(configmap_value "${tracing_off}" t-backend OTEL_TRACES_EXPORTER)"

# --- backend: env maps merge ------------------------------------------------------------------------------------
overlay="${WORK_DIR}/overlay.yaml"
render_to prod-values.yaml "${overlay}" --set backend.env.EXTRA_FLAG=on
assert_match "the JVM flags survive an overlay that adds one variable" <(pod_env "${overlay}" t-backend JAVA_TOOL_OPTIONS) 'MaxRAMPercentage=70'
assert_match "the added variable is rendered" <(pod_env "${overlay}" t-backend EXTRA_FLAG) '"value":"on"'
removed="${WORK_DIR}/removed.yaml"
render_to prod-values.yaml "${removed}" --set backend.env.LOG_JSON=null
assert_eq "null removes a default variable" "" "$(pod_env "${removed}" t-backend LOG_JSON)"
assert_match "LOG_JSON is on by default" <(pod_env "${prod}" t-backend LOG_JSON) '"value":"true"'

# --- secretRevision and config checksum ---------------------------------------------------------------------------
rev() { y "$1" "$(dep "$2") | .spec.template.metadata.annotations.\"blackhole.onelitefeather.net/secret-revision\""; }
assert_eq "backend secretRevision" "3" "$(rev "${prod}" t-backend)"
assert_eq "web secretRevision default" "1" "$(rev "${prod}" t-web)"
assert_eq "discord secretRevision default" "1" "$(rev "${prod}" t-discord)"
bumped="${WORK_DIR}/bumped.yaml"
render_to prod-values.yaml "${bumped}" --set backend.secretRevision=4
assert_eq "raising secretRevision changes the pod template" "4" "$(rev "${bumped}" t-backend)"
sum() { y "$1" "$(dep t-backend) | .spec.template.metadata.annotations.\"checksum/config\""; }
changed="${WORK_DIR}/changed.yaml"
render_to prod-values.yaml "${changed}" --set backend.config.BLACKHOLE_DOCS_ENABLED=true
if [[ "$(sum "${prod}")" != "$(sum "${changed}")" && "$(sum "${prod}")" != "null" ]]; then pass; else fail "a config change must change checksum/config"; fi

# --- probes -------------------------------------------------------------------------------------------------------------
b="$(dep t-backend) | .spec.template.spec.containers[0]"
assert_eq "backend startup probe path" "/health/liveness" "$(y "${prod}" "${b} | .startupProbe.httpGet.path")"
assert_eq "backend readiness probe path" "/health/readiness" "$(y "${prod}" "${b} | .readinessProbe.httpGet.path")"
assert_eq "backend liveness probe path" "/health/liveness" "$(y "${prod}" "${b} | .livenessProbe.httpGet.path")"
budget=$(( $(y "${prod}" "${b} | .startupProbe.periodSeconds") * $(y "${prod}" "${b} | .startupProbe.failureThreshold") ))
assert_eq "backend startup probe allows 10 minutes" "600" "${budget}"
assert_eq "web probe path" "/healthz" "$(y "${prod}" "$(dep t-web) | .spec.template.spec.containers[0].readinessProbe.httpGet.path")"
assert_eq "portal probe path" "/healthz" "$(y "${prod}" "$(dep t-portal) | .spec.template.spec.containers[0].livenessProbe.httpGet.path")"
d="$(dep t-discord) | .spec.template.spec.containers[0]"
assert_eq "discord health port" "8082" "$(y "${prod}" "${d} | .ports[] | select(.name == \"health\") | .containerPort")"
assert_eq "discord startup probe path" "/healthz" "$(y "${prod}" "${d} | .startupProbe.httpGet.path")"
assert_eq "discord has no readiness probe (it gates no traffic)" "null" "$(y "${prod}" "${d} | .readinessProbe")"
override="${WORK_DIR}/probe.yaml"
render_to prod-values.yaml "${override}" --set backend.startupProbe.failureThreshold=90
assert_eq "one probe field can be overridden without restating the rest" "/health/liveness" "$(y "${override}" "$(dep t-backend) | .spec.template.spec.containers[0].startupProbe.httpGet.path")"

# --- strategies, replicas, PDBs -----------------------------------------------------------------------------------------
assert_eq "backend rolls with maxUnavailable 0" "0" "$(y "${prod}" "$(dep t-backend) | .spec.strategy.rollingUpdate.maxUnavailable")"
assert_eq "backend replicas in prod" "2" "$(y "${prod}" "$(dep t-backend) | .spec.replicas")"
assert_eq "backend replicas in dev" "1" "$(y "${dev}" "$(dep t-backend) | .spec.replicas")"
assert_eq "discord strategy" "Recreate" "$(y "${prod}" "$(dep t-discord) | .spec.strategy.type")"
assert_eq "discord runs exactly one pod" "1" "$(y "${prod}" "$(dep t-discord) | .spec.replicas")"
assert_eq "clamav strategy" "Recreate" "$(y "${prod}" "$(dep t-clamav) | .spec.strategy.type")"
pdbs() { y "$1" '[select(.kind == "PodDisruptionBudget") | .metadata.name] | sort | join(",")'; }
assert_eq "PDBs for backend, web and portal with two replicas" "t-backend,t-portal,t-web" "$(pdbs "${prod}")"
assert_eq "no PDB with one replica" "" "$(pdbs "${dev}")"
assert_eq "PDB allows one disruption" "1" "$(y "${prod}" 'select(.kind == "PodDisruptionBudget" and .metadata.name == "t-backend") | .spec.maxUnavailable')"
assert_eq "backend pods spread over nodes with two replicas" "kubernetes.io/hostname" "$(y "${prod}" "$(dep t-backend) | .spec.template.spec.topologySpreadConstraints[0].topologyKey")"
assert_eq "no spread constraint with one replica" "null" "$(y "${dev}" "$(dep t-backend) | .spec.template.spec.topologySpreadConstraints")"
assert_eq "priority class reaches the pods" "feather-standard" "$(y "${prod}" "$(dep t-backend) | .spec.template.spec.priorityClassName")"
assert_eq "dev has no priority class" "null" "$(y "${dev}" "$(dep t-backend) | .spec.template.spec.priorityClassName")"
assert_eq "pull secrets reach the pods" "harbor-pull" "$(y "${prod}" "$(dep t-web) | .spec.template.spec.imagePullSecrets[0].name")"

# --- service accounts and Services ---------------------------------------------------------------------------------------
assert_eq "no service account token is mounted into any pod" "0" "$(y "${prod}" '[select(.kind == "Deployment") | select(.spec.template.spec.automountServiceAccountToken != false)] | length')"
assert_eq "every service account refuses token automount" "0" "$(y "${prod}" '[select(.kind == "ServiceAccount") | select(.automountServiceAccountToken != false)] | length')"
assert_eq "all Services are plain ClusterIP" "0" "$(y "${prod}" '[select(.kind == "Service") | select(.spec.clusterIP != null or (.spec.type != null and .spec.type != "ClusterIP"))] | length')"
assert_eq "backend Service port" "8080" "$(y "${prod}" 'select(.kind == "Service" and .metadata.name == "t-backend") | .spec.ports[0].port')"
assert_eq "discord has no Service" "0" "$(y "${prod}" '[select(.kind == "Service" and .metadata.name == "t-discord")] | length')"

# --- images ----------------------------------------------------------------------------------------------------------------
img() { y "$1" "$(dep "$2") | .spec.template.spec.containers[0].image"; }
assert_eq "backend image falls back to the appVersion" "harbor.onelitefeather.dev/onelitefeather/blackhole:1.0.0" "$(img "${prod}" t-backend)"
assert_eq "web image" "harbor.onelitefeather.dev/onelitefeather/blackhole-web:1.0.0" "$(img "${prod}" t-web)"
assert_eq "portal image" "harbor.onelitefeather.dev/onelitefeather/blackhole-portal:1.0.0" "$(img "${prod}" t-portal)"
assert_eq "discord image" "harbor.onelitefeather.dev/onelitefeather/blackhole-discord:1.0.0" "$(img "${prod}" t-discord)"
assert_eq "the global tag moves all four Blackhole images (dev backend)" "harbor.onelitefeather.dev/onelitefeather/blackhole:main-1790000000-abcdef1" "$(img "${dev}" t-backend)"
assert_eq "the global tag moves all four Blackhole images (dev discord)" "harbor.onelitefeather.dev/onelitefeather/blackhole-discord:main-1790000000-abcdef1" "$(img "${dev}" t-discord)"
assert_eq "ClamAV is pinned and ignores the global tag" "docker.io/clamav/clamav:1.5.4" "$(img "${prod}" t-clamav)"
pinned="${WORK_DIR}/pinned.yaml"
render_to prod-values.yaml "${pinned}" --set global.image.tag=2.0.0 --set discord.image.tag=2.0.1
assert_eq "a component tag wins over the global tag" "harbor.onelitefeather.dev/onelitefeather/blackhole-discord:2.0.1" "$(img "${pinned}" t-discord)"
assert_eq "the global tag applies to the others" "harbor.onelitefeather.dev/onelitefeather/blackhole-web:2.0.0" "$(img "${pinned}" t-web)"

# --- web and portal: the environment of the design table ---------------------------------------------------------------------
we() { pod_env "${prod}" t-web "$1" | yq eval '.value' -; }
assert_eq "web backend url" "http://t-backend:8080" "$(we BLACKHOLE_BACKEND_URL)"
assert_eq "web oidc authority is derived from the tenant" "https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/v2.0" "$(we BLACKHOLE_UI_OIDC_AUTHORITY)"
assert_eq "web oidc client id" "33333333-3333-3333-3333-333333333333" "$(we BLACKHOLE_UI_OIDC_CLIENT_ID)"
assert_eq "web api scope" "api://11111111-1111-1111-1111-111111111111/access_as_user" "$(we BLACKHOLE_UI_API_SCOPE)"
assert_eq "web video limit equals the backend limit" "94371840" "$(we BLACKHOLE_UI_EVIDENCE_MAX_VIDEO_BYTES)"
assert_eq "web image limit is only set when asked for" "" "$(pod_env "${prod}" t-web BLACKHOLE_UI_EVIDENCE_MAX_IMAGE_BYTES)"
assert_eq "web has no secret env at all" "0" "$(y "${prod}" "$(dep t-web) | [.spec.template.spec.containers[0].env[] | select(.valueFrom != null)] | length")"
pe() { pod_env "${prod}" t-portal "$1" | yq eval '.value' -; }
assert_eq "portal backend url" "http://t-backend:8080" "$(pe BLACKHOLE_BACKEND_URL)"
assert_eq "portal origin" "https://appeal.onelitefeather.net" "$(pe BLACKHOLE_PORTAL_ORIGIN)"
assert_eq "portal has no secret env at all" "0" "$(y "${prod}" "$(dep t-portal) | [.spec.template.spec.containers[0].env[] | select(.valueFrom != null)] | length")"

# --- discord -------------------------------------------------------------------------------------------------------------------
dc() { configmap_value "${prod}" t-discord "$1"; }
assert_eq "discord guild id" "100000000000000001" "$(dc DISCORD_GUILD_ID)"
assert_eq "discord ticket category" "100000000000000004" "$(dc DISCORD_TICKET_CATEGORY_ID)"
assert_eq "discord tenant is the shared tenant" "00000000-0000-0000-0000-000000000000" "$(dc BLACKHOLE_DISCORD_TENANT_ID)"
assert_eq "discord auth scope defaults to the API .default" "api://11111111-1111-1111-1111-111111111111/.default" "$(dc BLACKHOLE_DISCORD_AUTH_SCOPE)"
assert_eq "discord backend url" "http://t-backend:8080" "$(dc BLACKHOLE_BACKEND_URL)"
assert_eq "discord panel url" "https://blackhole.onelitefeather.net" "$(dc BLACKHOLE_PANEL_URL)"
assert_eq "discord NATS url" "nats://nats.nats.svc:4222" "$(dc NATS_URL)"
assert_eq "discord NATS credentials file" "/etc/blackhole/nats/nats.creds" "$(dc NATS_CREDS_FILE)"
assert_eq "discord appeal channel is optional" "null" "$(dc DISCORD_APPEAL_CHANNEL_ID)"
dtok="$(pod_env "${prod}" t-discord DISCORD_TOKEN)"
assert_eq "discord token comes from its Secret" "blackhole-discord" "$(yq eval '.valueFrom.secretKeyRef.name' - <<<"${dtok}")"
dcs="$(pod_env "${prod}" t-discord BLACKHOLE_DISCORD_CLIENT_SECRET)"
assert_eq "discord client secret comes from its Secret" "BLACKHOLE_DISCORD_CLIENT_SECRET" "$(yq eval '.valueFrom.secretKeyRef.key' - <<<"${dcs}")"
# The staff audit webhook is a URL that is a secret: it is read from the discord Secret, never the ConfigMap.
audit="$(pod_env "${prod}" t-discord DISCORD_AUDIT_WEBHOOK_URL)"
assert_eq "audit webhook comes from the discord Secret" "blackhole-discord" "$(yq eval '.valueFrom.secretKeyRef.name' - <<<"${audit}")"
assert_eq "audit webhook reads the default key" "DISCORD_AUDIT_WEBHOOK_URL" "$(yq eval '.valueFrom.secretKeyRef.key' - <<<"${audit}")"
assert_eq "audit webhook is not in the ConfigMap" "null" "$(dc DISCORD_AUDIT_WEBHOOK_URL)"
assert_eq "audit webhook reference is optional (an old Secret still starts the bot)" "true" "$(yq eval '.valueFrom.secretKeyRef.optional' - <<<"${audit}")"
audit_off="${WORK_DIR}/audit-off.yaml"
render_to prod-values.yaml "${audit_off}" --set discord.auditWebhookKey=
assert_eq "no audit webhook entry when no key is named" "" "$(pod_env "${audit_off}" t-discord DISCORD_AUDIT_WEBHOOK_URL)"
audit_key="${WORK_DIR}/audit-key.yaml"
render_to prod-values.yaml "${audit_key}" --set discord.auditWebhookKey=staff-audit-hook
assert_eq "audit webhook key is the one the values name" "staff-audit-hook" "$(yq eval '.valueFrom.secretKeyRef.key' - <<<"$(pod_env "${audit_key}" t-discord DISCORD_AUDIT_WEBHOOK_URL)")"
assert_eq "the NATS credentials are mounted from the Secret" "blackhole-nats" "$(y "${prod}" "$(dep t-discord) | .spec.template.spec.volumes[] | select(.name == \"nats\") | .secret.secretName")"
assert_eq "the backend mounts the NATS credentials too" "/etc/blackhole/nats" "$(y "${prod}" "$(dep t-backend) | .spec.template.spec.containers[0].volumeMounts[] | select(.name == \"nats\") | .mountPath")"

# --- ClamAV ---------------------------------------------------------------------------------------------------------------------
c="$(dep t-clamav) | .spec.template.spec.containers[0]"
ce() { pod_env "${prod}" t-clamav "$1" | yq eval '.value' -; }
assert_eq "clamd stream limit equals compose" "110M" "$(ce CLAMD_CONF_StreamMaxLength)"
assert_eq "clamd file limit equals compose" "110M" "$(ce CLAMD_CONF_MaxFileSize)"
assert_eq "clamd scan limit equals compose" "220M" "$(ce CLAMD_CONF_MaxScanSize)"
assert_eq "clamd port" "3310" "$(y "${prod}" "${c} | .ports[0].containerPort")"
assert_eq "clamav Service port" "3310" "$(y "${prod}" 'select(.kind == "Service" and .metadata.name == "t-clamav") | .spec.ports[0].port')"
assert_eq "signatures live on a PVC" "t-clamav" "$(y "${prod}" "$(dep t-clamav) | .spec.template.spec.volumes[] | select(.name == \"signatures\") | .persistentVolumeClaim.claimName")"
assert_eq "the PVC is mounted at /var/lib/clamav" "/var/lib/clamav" "$(y "${prod}" "${c} | .volumeMounts[] | select(.name == \"signatures\") | .mountPath")"
assert_eq "PVC size" "2Gi" "$(y "${prod}" 'select(.kind == "PersistentVolumeClaim") | .spec.resources.requests.storage')"
assert_eq "PVC storage class" "ceph-rbd-fr01" "$(y "${prod}" 'select(.kind == "PersistentVolumeClaim") | .spec.storageClassName')"
assert_eq "clamav memory limit" "3Gi" "$(y "${prod}" "${c} | .resources.limits.memory")"
assert_eq "clamav memory request" "1536Mi" "$(y "${prod}" "${c} | .resources.requests.memory")"
assert_eq "clamav startup probe uses clamdcheck.sh" "clamdcheck.sh" "$(y "${prod}" "${c} | .startupProbe.exec.command[0]")"
cbudget=$(( $(y "${prod}" "${c} | .startupProbe.periodSeconds") * $(y "${prod}" "${c} | .startupProbe.failureThreshold") ))
assert_eq "clamav startup probe allows 10 minutes" "600" "${cbudget}"
assert_eq "the unprivileged entrypoint is used" "/init-unprivileged" "$(y "${prod}" "${c} | .command[0]")"
assert_match "clamd.conf carries the limits" <(configmap_value "${prod}" t-clamav clamd.conf) 'StreamMaxLength 110M'
assert_match "clamd.conf listens on the socket the entrypoint waits for" <(configmap_value "${prod}" t-clamav clamd.conf) 'LocalSocket /tmp/clamd.sock'
assert_eq "no ClamAV workload when an external scanner is named" "0" "$(y "${dev}" '[select(.metadata.name == "t-clamav")] | length')"

# --- disabled components render nothing --------------------------------------------------------------------------------------------
off="${WORK_DIR}/off.yaml"
render_to prod-values.yaml "${off}" --set web.enabled=false --set discord.enabled=false
assert_eq "no web resource when web is disabled" "0" "$(y "${off}" '[select(.metadata.name | test("^t-web"))] | length')"
assert_eq "no discord resource when discord is disabled" "0" "$(y "${off}" '[select(.metadata.name | test("^t-discord"))] | length')"

# --- observability --------------------------------------------------------------------------------------------------------------------
for name in t-backend t-web t-portal t-discord t-clamav; do
  assert_eq "${name} logs env label" "prod" "$(y "${prod}" "$(dep "${name}") | .spec.template.metadata.labels.\"logs.onelitefeather.net/env\"")"
  assert_eq "${name} has a logs format label" "1" "$(y "${prod}" "[$(dep "${name}") | .spec.template.metadata.labels.\"logs.onelitefeather.net/format\" | select(. != null)] | length")"
done
assert_eq "backend logs JSON" "json" "$(y "${prod}" "$(dep t-backend) | .spec.template.metadata.labels.\"logs.onelitefeather.net/format\"")"
assert_eq "dev logs env label" "dev" "$(y "${dev}" "$(dep t-backend) | .spec.template.metadata.labels.\"logs.onelitefeather.net/env\"")"
assert_eq "pod labels select the Service" "backend" "$(y "${prod}" "$(dep t-backend) | .spec.template.metadata.labels.\"app.kubernetes.io/component\"")"

assert_eq "ServiceMonitor in prod" "1" "$(y "${prod}" '[select(.kind == "ServiceMonitor")] | length')"
assert_eq "no ServiceMonitor in dev" "0" "$(y "${dev}" '[select(.kind == "ServiceMonitor")] | length')"
sm='select(.kind == "ServiceMonitor") | .spec.endpoints[0]'
assert_eq "ServiceMonitor path" "/prometheus" "$(y "${prod}" "${sm} | .path")"
assert_eq "ServiceMonitor client secret comes from a Secret" "blackhole-metrics" "$(y "${prod}" "${sm} | .oauth2.clientSecret.name")"
assert_eq "ServiceMonitor client id comes from a Secret" "BLACKHOLE_METRICS_CLIENT_ID" "$(y "${prod}" "${sm} | .oauth2.clientId.secret.key")"
assert_eq "ServiceMonitor token url" "https://login.microsoftonline.com/00000000-0000-0000-0000-000000000000/oauth2/v2.0/token" "$(y "${prod}" "${sm} | .oauth2.tokenUrl")"
assert_eq "ServiceMonitor scope" "api://11111111-1111-1111-1111-111111111111/.default" "$(y "${prod}" "${sm} | .oauth2.scopes[0]")"
assert_eq "PodMonitor for the bot in prod" "health" "$(y "${prod}" 'select(.kind == "PodMonitor") | .spec.podMetricsEndpoints[0].port')"
assert_eq "no PodMonitor in dev" "0" "$(y "${dev}" '[select(.kind == "PodMonitor")] | length')"

# --- NATS credentials per component (backend and bot are different NATS users) ---------------------------------------------
nats_vol() { y "$1" "$(dep "$2") | .spec.template.spec.volumes[] | select(.name == \"nats\") | .secret.$3"; }
assert_eq "without overrides both use the shared secret (backend)" "blackhole-nats" "$(nats_vol "${prod}" t-backend secretName)"
assert_eq "without overrides both use the shared secret (bot)" "blackhole-nats" "$(nats_vol "${prod}" t-discord secretName)"
split="${WORK_DIR}/nats-split.yaml"
render_to prod-values.yaml "${split}" \
  --set backend.nats.existingSecret=blackhole-nats-backend --set discord.nats.existingSecret=blackhole-nats-bot \
  --set discord.nats.credsKey=bot.creds
assert_eq "backend mounts its own secret" "blackhole-nats-backend" "$(nats_vol "${split}" t-backend secretName)"
assert_eq "bot mounts its own secret" "blackhole-nats-bot" "$(nats_vol "${split}" t-discord secretName)"
assert_eq "backend keeps the shared key name" "nats.creds" "$(nats_vol "${split}" t-backend items[0].key)"
assert_eq "bot uses its key override" "bot.creds" "$(nats_vol "${split}" t-discord items[0].key)"
assert_eq "bot credentials file follows the key override" "/etc/blackhole/nats/bot.creds" "$(configmap_value "${split}" t-discord NATS_CREDS_FILE)"
assert_eq "backend credentials file is unchanged" "/etc/blackhole/nats/nats.creds" "$(configmap_value "${split}" t-backend NATS_CREDS_FILE)"
only_bot="${WORK_DIR}/nats-bot-only.yaml"
render_to prod-values.yaml "${only_bot}" --set discord.nats.existingSecret=blackhole-nats-bot
assert_eq "an override on one component leaves the other on the shared secret" "blackhole-nats" "$(nats_vol "${only_bot}" t-backend secretName)"
no_shared="${WORK_DIR}/nats-no-shared.yaml"
render_to prod-values.yaml "${no_shared}" --set nats.existingSecret= \
  --set backend.nats.existingSecret=blackhole-nats-backend --set discord.nats.existingSecret=blackhole-nats-bot
assert_eq "per-component secrets work without a shared one (backend)" "blackhole-nats-backend" "$(nats_vol "${no_shared}" t-backend secretName)"
assert_eq "per-component secrets work without a shared one (bot)" "blackhole-nats-bot" "$(nats_vol "${no_shared}" t-discord secretName)"

finish
