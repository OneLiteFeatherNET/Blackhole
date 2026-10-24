# The public appeal portal: a Nitro (Node) server that serves the built SPA and a backend-for-frontend
# with an allow-list of the player routes of the backend (/public/**).
#
# Build from the repository root (same convention as web/Dockerfile):
#   docker build -f portal/Dockerfile -t blackhole-portal .
#
# Run (both values are validated at start; the portal origin must be https in production):
#   docker run -p 8082:8080 \
#     -e BLACKHOLE_BACKEND_URL=http://backend:8080 \
#     -e BLACKHOLE_PORTAL_ORIGIN=https://appeal.onelitefeather.net blackhole-portal

FROM node:24-alpine AS build
WORKDIR /app
COPY portal/package.json portal/package-lock.json ./portal/
RUN cd portal && npm ci
COPY portal ./portal
# vite build writes dist/, nitro build bundles the server and its assets into .output/.
RUN cd portal && npm run build

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production \
    NITRO_HOST=0.0.0.0 \
    NITRO_PORT=8080
# .output is self-contained (the server bundle with its dependencies); nothing else is needed.
# Owned by root and only readable for the node user, so the server cannot rewrite its own code.
COPY --from=build /app/portal/.output ./.output
USER node
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -q --spider http://127.0.0.1:8080/healthz || exit 1
CMD ["node", ".output/server/index.mjs"]
