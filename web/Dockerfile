# The admin web UI: static files plus a reverse proxy that forwards /api/ to the backend, so the
# browser talks to one origin and the backend needs no CORS configuration.
#
# Build from the repository root (the API spec lives in client/specs):
#   docker build -f web/Dockerfile -t blackhole-web .
#
# Run (the UI has no login of its own - put it behind a private network or an authenticating proxy):
#   docker run -p 8081:8080 \
#     -e BLACKHOLE_BACKEND_URL=http://backend:8080 \
#     -e BLACKHOLE_UI_SOURCE=<operator uuid> blackhole-web

FROM node:22-alpine AS build
WORKDIR /app
# package.json points openapi-typescript at ../client/specs/<pinned spec>.
COPY client/specs ./client/specs
COPY web/package.json web/package-lock.json ./web/
RUN cd web && npm ci
COPY web ./web
RUN cd web && npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine
# Only these variables are substituted into the nginx template; nginx's own $variables stay untouched.
ENV NGINX_ENVSUBST_FILTER=^BLACKHOLE_ \
    BLACKHOLE_BACKEND_URL=http://backend:8080
COPY web/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY web/nginx/40-config-json.sh /docker-entrypoint.d/40-config-json.sh
COPY --from=build /app/web/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
