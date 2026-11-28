#!/usr/bin/env bash
# The chart lints and renders for every fixture in ci/, and values.schema.json rejects what it must.
# Run: deploy/charts/blackhole/tests/render.sh   (needs helm v4.3.0 and yq v4.53.6 on PATH)
#
# Every "must fail" check also greps the error for the value the schema guards, so a render that dies
# for an unrelated reason can never pass as "the schema rejected it".

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# --- every fixture lints and renders ---------------------------------------------------------------------
for fixture in "${CI_DIR}"/*-values.yaml; do
  name="$(basename "${fixture}")"
  if helm lint "${CHART_DIR}" -f "${fixture}" >"${WORK_DIR}/lint.out" 2>&1; then
    pass
  else
    fail "helm lint failed for ${name}: $(cat "${WORK_DIR}/lint.out")"
  fi
  if render "${name}" >"${WORK_DIR}/${name}.yaml" 2>"${WORK_DIR}/${name}.err"; then
    pass
  else
    fail "helm template failed for ${name}: $(cat "${WORK_DIR}/${name}.err")"
  fi
done

# --- the schema rejects unknown keys ---------------------------------------------------------------------
expect_render_failure "an unknown top-level key" "bogus" prod-values.yaml --set bogus=1
expect_render_failure "an unknown key inside a component" "typo" prod-values.yaml --set backend.typo=1
expect_render_failure "a second discord replica" "/discord/replicaCount" prod-values.yaml --set discord.replicaCount=2

# --- required values: without a values file the chart names what is missing ------------------------------
expect_render_failure "a missing database secret" "/database/existingSecret" -
expect_render_failure "a missing redis secret" "/redis/existingSecret" -
expect_render_failure "a missing keys secret" "/keys/existingSecret" -
expect_render_failure "a missing nats secret" "/nats/existingSecret" -
expect_render_failure "a missing evidence bucket" "/s3/bucket" -
expect_render_failure "a missing tenant id" "/auth/tenantId" -
expect_render_failure "a missing admin group id" "/auth/roles/admin" -
expect_render_failure "a missing oidc client id" "/web/oidc/clientId" prod-values.yaml --set web.oidc.clientId=
expect_render_failure "a missing oidc api scope" "/web/oidc/apiScope" prod-values.yaml --set web.oidc.apiScope=
expect_render_failure "a missing portal origin" "/portal/origin" prod-values.yaml --set portal.origin=
expect_render_failure "a missing discord secret" "/discord/existingSecret" prod-values.yaml --set discord.existingSecret=
expect_render_failure "a missing discord guild id" "/discord/ids/guildId" prod-values.yaml --set discord.ids.guildId=
expect_render_failure "an enabled ingress without a host" "/web/ingress/host" prod-values.yaml --set web.ingress.host=
expect_render_failure "an enabled service monitor without credentials" "/backend/metrics/serviceMonitor/oauth2/existingSecret" prod-values.yaml --set backend.metrics.serviceMonitor.oauth2.existingSecret=
# A disabled component does not need its values.
if render - --set backend.enabled=false --set web.enabled=false --set portal.enabled=false \
  --set discord.enabled=false --set clamav.enabled=false --set securityTxt.contact= >/dev/null 2>&1; then pass; else fail "everything disabled must render"; fi

# --- the evidence scan needs a scanner --------------------------------------------------------------------
expect_render_failure "scan enabled without a scanner" "/clamav" prod-values.yaml \
  --set clamav.enabled=false --set clamav.externalHost=
if render prod-values.yaml --set clamav.enabled=false --set clamav.externalHost=scanner.example.invalid >/dev/null 2>&1; then
  pass
else
  fail "an external scanner must satisfy the scan requirement"
fi
if render prod-values.yaml --set clamav.enabled=false --set backend.evidence.scan.enabled=false >/dev/null 2>&1; then
  pass
else
  fail "no scanner is needed when the scan is off"
fi

# --- secrets stay out of the values ------------------------------------------------------------------------
expect_render_failure "a literal Ban ID key in env" "BLACKHOLE_BANID_KEY" prod-values.yaml --set backend.env.BLACKHOLE_BANID_KEY=abc
expect_render_failure "a literal password in config" "JDBC_PASSWORD" prod-values.yaml --set backend.config.JDBC_PASSWORD=abc
expect_render_failure "a literal discord token in env" "DISCORD_TOKEN" prod-values.yaml --set discord.env.DISCORD_TOKEN=abc
expect_render_failure "a literal audit webhook in env" "DISCORD_AUDIT_WEBHOOK_URL" prod-values.yaml --set discord.env.DISCORD_AUDIT_WEBHOOK_URL=https://discord.com/api/webhooks/1/abc
expect_render_failure "a literal audit webhook in config" "DISCORD_AUDIT_WEBHOOK_URL" prod-values.yaml --set discord.config.DISCORD_AUDIT_WEBHOOK_URL=https://discord.com/api/webhooks/1/abc
# No key set: the audit is off in the bot, so the chart renders without the webhook reference.
if render prod-values.yaml --set discord.auditWebhookKey= >/dev/null 2>&1; then pass; else fail "a discord without an audit key must render"; fi
expect_render_failure "a RabbitMQ setting (the event bus is NATS)" "RABBITMQ_HOST" prod-values.yaml --set backend.env.RABBITMQ_HOST=mq
expect_render_failure "insecure issuers" "/auth/allowInsecureIssuer" prod-values.yaml --set auth.allowInsecureIssuer=true

# --- exposure and limits ------------------------------------------------------------------------------------
expect_render_failure "ingress and httpRoute together" "/web/httpRoute/enabled" prod-values.yaml \
  --set web.httpRoute.enabled=true --set 'web.httpRoute.hostnames={x.example.invalid}'
expect_render_failure "a plain http portal origin in prod" "/portal/origin" prod-values.yaml --set portal.origin=http://appeal.example.invalid
expect_render_failure "a video limit above the edge cap behind an ingress" "maxVideoBytes" prod-values.yaml \
  --set backend.evidence.maxVideoBytes=104857600 --set web.evidence.maxVideoBytes=104857600
expect_render_failure "a proxy prefix that would expose /public" "/backend/externalApi/prefixes" prod-values.yaml \
  --set 'backend.externalApi.prefixes={punishment,public}'
expect_render_failure "different video limits in backend and panel" "must be equal" prod-values.yaml --set web.evidence.maxVideoBytes=1000
expect_render_failure "a panel without a backend to talk to" "web.backendUrl is required" prod-values.yaml \
  --set backend.enabled=false --set portal.enabled=false --set discord.enabled=false --set clamav.enabled=false
if render local-values.yaml >/dev/null 2>&1; then pass; else fail "local values with an http portal origin must render"; fi

# --- NATS credentials: shared or per component --------------------------------------------------------------------------------
expect_render_failure "no NATS secret at all for the backend" "existingSecret|nats" prod-values.yaml \
  --set nats.existingSecret= --set discord.nats.existingSecret=blackhole-nats-bot
expect_render_failure "no NATS secret for the bot when the shared one is empty" "existingSecret|nats" prod-values.yaml \
  --set nats.existingSecret= --set backend.nats.existingSecret=blackhole-nats-backend
expect_render_failure "an unknown key in a component nats block" "typo" prod-values.yaml --set backend.nats.typo=1
if render prod-values.yaml --set nats.existingSecret= --set backend.nats.existingSecret=a --set discord.nats.existingSecret=b >/dev/null 2>&1; then
  pass
else
  fail "per-component NATS secrets must satisfy the requirement without a shared one"
fi

finish
