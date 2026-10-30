# The admin web UI: a Nitro (Node) server that serves the built SPA and forwards /api/ to the
# backend, so the browser talks to one origin and the backend needs no CORS configuration.
#
# Build from the repository root (the API spec lives in client/specs):
#   docker build -f web/Dockerfile -t blackhole-web .
#
# Run (staff sign in with Microsoft Entra ID in the browser; the three BLACKHOLE_UI_* values are required):
#   docker run -p 8081:8080 \
#     -e BLACKHOLE_BACKEND_URL=http://backend:8080 \
#     -e BLACKHOLE_UI_OIDC_AUTHORITY=https://login.microsoftonline.com/<tenant-id>/v2.0 \
#     -e BLACKHOLE_UI_OIDC_CLIENT_ID=<spa application (client) id> \
#     -e BLACKHOLE_UI_API_SCOPE=api://<api-client-id>/access_as_user blackhole-web

FROM node:26-alpine AS build
WORKDIR /app
# package.json points openapi-typescript at ../client/specs/<pinned spec>.
COPY client/specs ./client/specs
# The token source (design/tokens) is read by the prebuild step that generates web/src/styles/md3-tokens.generated.css.
COPY design/tokens ./design/tokens
COPY web/package.json web/package-lock.json ./web/
RUN cd web && npm ci
COPY web ./web
# vite build writes dist/, nitro build bundles the server and its assets into .output/.
RUN cd web && npm run build

FROM node:26-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NITRO_HOST=0.0.0.0 \
    NITRO_PORT=8080
# .output is self-contained (the server bundle with its dependencies); nothing else is needed.
# Owned by root and only readable for the node user, so the server cannot rewrite its own code.
COPY --from=build /app/web/.output ./.output
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
CMD ["node", ".output/server/index.mjs"]
