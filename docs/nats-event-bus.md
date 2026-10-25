# NATS event bus

The backend publishes its domain events to NATS JetStream. The Discord bot and the backend itself
consume them. The bus is a best-effort side channel: every consumer treats an event as a hint and
reads the truth from the backend, so a lost, duplicated or late event delays but never corrupts
anything. Events carry hashed owners and ids only, never a raw UUID, a Ban ID or message text.

## Reference

### Subjects

An event goes to `blackhole.events.<event type>`, for example `blackhole.events.punishment.created`.
The event type is the only part of a subject that varies; no id, owner hash or other payload value is
ever placed in a subject, a header (other than the random `Nats-Msg-Id`) or a consumer name.

### Stream

| Setting | Value |
|---|---|
| Name | `BLACKHOLE_EVENTS`, owned and created or updated by the backend at start |
| Subjects | `blackhole.events.>` |
| Storage, retention | file, limits (three independent consumers read overlapping subjects) |
| Max age | `NATS_EVENTS_MAX_AGE`, default `P7D` |
| Max bytes | `NATS_EVENTS_MAX_BYTES`, default 2 GiB; the oldest events go first |
| Replicas | `NATS_STREAM_REPLICAS`, default 1 (3 in feather-core) |
| Duplicate window | 2 minutes: the publisher sets `Nats-Msg-Id` to the event id, so a publish retry is stored once |
| Delete, purge | denied |

### Durable consumers

All are pull consumers with explicit acknowledgement. Instances that bind the same name share the work.

| Consumer | Owner | Event types | Settings |
|---|---|---|---|
| `blackhole-redis-sync` | backend | `punishment.created/.updated/.expired/.revoked`, `appeal.resolved` | `deliver_policy new` on first creation, `ack_wait` 30 s, `max_deliver` 1 |
| `blackhole-discord-notify` | Discord bot | `report.created/.resolved`, `appeal.submitted/.resolved` | `max_ack_pending` 5, `ack_wait` 30 s, `max_deliver` unlimited (bounded by the stream's max age), a retry is `nakWithDelay(10 s)` |
| `blackhole-discord-case` | Discord bot | the case message, Ban ID and status events, `appeal.resolved`, `staff-link.removed`, `discord-link.removed` | same as `blackhole-discord-notify` |

### Backend settings

All are environment variables with a local-development default (see `application.yml`).

| Variable | Default | Meaning |
|---|---|---|
| `NATS_URL` | `nats://localhost:4222` | Server URL(s), comma separated. In feather-core: `nats://nats.nats.svc:4222` |
| `NATS_CREDS_FILE` | empty | Path of the `.creds` file; empty means no authentication (local development only) |
| `NATS_CONNECTION_NAME` | `blackhole-backend` | The name shown on the server |
| `NATS_CONNECT_TIMEOUT` | `5s` | One connection attempt; the client reconnects forever and never blocks a request |
| `NATS_STREAM_REPLICAS` | `1` | Replicas of the stream |
| `NATS_EVENTS_MAX_AGE` | `P7D` | Retention of an event (ISO-8601) |
| `NATS_EVENTS_MAX_BYTES` | `2147483648` | Size bound of the stream |

An unreachable server or an unreadable credentials file is logged and never stops the backend. The
Discord bot reads `NATS_URL` (required) and `NATS_CREDS_FILE` (optional); see
[discord-bot.md](discord-bot.md).

## How to give each client its own NATS user

The `BLACKHOLE` account and its users are created with `nsc` on a workstation; the operator signing
key stays in the team password manager. Never paste a seed, a `.creds` file or a password into a chat,
a pull request or a plain file: only public keys, signed account JWTs and SOPS-encrypted files go
into git.

```shell
nsc add account BLACKHOLE
nsc edit account BLACKHOLE --js-mem-storage 0 --js-disk-storage 5G --js-streams 1 --js-consumer 8

# backend: the only publisher; manages the stream and the durable blackhole-redis-sync
nsc add user blackhole-backend --account BLACKHOLE \
  --allow-pub 'blackhole.events.>' \
  --allow-pub '$JS.API.STREAM.CREATE.BLACKHOLE_EVENTS' --allow-pub '$JS.API.STREAM.UPDATE.BLACKHOLE_EVENTS' \
  --allow-pub '$JS.API.STREAM.INFO.BLACKHOLE_EVENTS' \
  --allow-pub '$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-redis-sync' \
  --allow-pub '$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-redis-sync.>' \
  --allow-pub '$JS.API.CONSUMER.DURABLE.CREATE.BLACKHOLE_EVENTS.blackhole-redis-sync' \
  --allow-pub '$JS.API.CONSUMER.INFO.BLACKHOLE_EVENTS.blackhole-redis-sync' \
  --allow-pub '$JS.API.CONSUMER.MSG.NEXT.BLACKHOLE_EVENTS.blackhole-redis-sync' \
  --allow-pub '$JS.ACK.BLACKHOLE_EVENTS.blackhole-redis-sync.>' \
  --allow-sub '_INBOX.>'

# Discord bot: never publishes events, owns two durables (repeat the block for blackhole-discord-case)
nsc add user blackhole-discord --account BLACKHOLE \
  --allow-pub '$JS.API.STREAM.INFO.BLACKHOLE_EVENTS' \
  --allow-pub '$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-notify' \
  --allow-pub '$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-notify.>' \
  --allow-pub '$JS.API.CONSUMER.DURABLE.CREATE.BLACKHOLE_EVENTS.blackhole-discord-notify' \
  --allow-pub '$JS.API.CONSUMER.INFO.BLACKHOLE_EVENTS.blackhole-discord-notify' \
  --allow-pub '$JS.API.CONSUMER.MSG.NEXT.BLACKHOLE_EVENTS.blackhole-discord-notify' \
  --allow-pub '$JS.ACK.BLACKHOLE_EVENTS.blackhole-discord-notify.>' \
  --allow-pub '$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-case' \
  --allow-pub '$JS.API.CONSUMER.CREATE.BLACKHOLE_EVENTS.blackhole-discord-case.>' \
  --allow-pub '$JS.API.CONSUMER.DURABLE.CREATE.BLACKHOLE_EVENTS.blackhole-discord-case' \
  --allow-pub '$JS.API.CONSUMER.INFO.BLACKHOLE_EVENTS.blackhole-discord-case' \
  --allow-pub '$JS.API.CONSUMER.MSG.NEXT.BLACKHOLE_EVENTS.blackhole-discord-case' \
  --allow-pub '$JS.ACK.BLACKHOLE_EVENTS.blackhole-discord-case.>' \
  --allow-sub '_INBOX.>'

# velocity: subscribe-only, WebSocket-only, no publishing at all
nsc add user blackhole-velocity --account BLACKHOLE \
  --deny-pub '>' \
  --allow-sub 'blackhole.events.punishment.*' --allow-sub 'blackhole.events.appeal.resolved' \
  --allow-sub 'blackhole.events.section.changed' --allow-sub 'blackhole.events.report.created' \
  --allow-sub 'blackhole.events.report.feedback' --allow-sub 'blackhole.events.kick.mass'
nsc edit user blackhole-velocity --account BLACKHOLE --conn-type WEBSOCKET
nsc edit user blackhole-backend  --account BLACKHOLE --conn-type STANDARD
nsc edit user blackhole-discord  --account BLACKHOLE --conn-type STANDARD

# creds files stay on the workstation; the two in-cluster ones are SOPS-encrypted before they touch git
nsc generate creds --account BLACKHOLE --name blackhole-backend  > blackhole-backend.creds
nsc generate creds --account BLACKHOLE --name blackhole-discord  > blackhole-discord.creds
nsc generate creds --account BLACKHOLE --name blackhole-velocity > blackhole-velocity.creds
# for resolver_preload copy ONLY the account public key and JWT:
nsc describe account BLACKHOLE --raw
```

The backend and bot integration tests run exactly the backend and bot permission sets above against a
real server, so a missing subject fails in CI as a permissions violation. To rotate the Velocity
credential, create a new user, deploy it, then revoke the old one with `nsc revocations add-user`.

## Explanation

### Why NATS

The OLF Kubernetes cluster `feather-core` runs a 3-node NATS cluster with JetStream and no RabbitMQ.
Running a broker only for Blackhole would add one more thing to patch, back up and monitor.

### Reaching NATS from outside the cluster

Inside the cluster the client port 4222 is plain TCP: the traffic holds only hashed ids and event
types, the same trust boundary as the PostgreSQL and Dragonfly traffic. Anything that leaves the
cluster uses `wss://`:

1. `wss://nats.onelitefeather.dev` through the existing Cloudflare Tunnel: TLS at Cloudflare, JWT
   authentication in NATS, a WebSocket-only user. Only `wss://` works (no 4222), the payload limit is
   1 MiB, and the server pings every 30 s so Cloudflare keeps idle connections. This is the route for
   the Velocity plugin.
2. `wss://nats.apps.onelite.feather` through Envoy, for hosts inside the internal network (the step-ca
   root must be in the client trust store).

Raw TCP 4222 over a Cloudflare Tunnel is not usable by a plain NATS client (it needs
`cloudflared access tcp` beside the app), and a TCP load balancer needs a public address the cluster
does not have.

The stream is a second at-rest copy of the events; check that the storage class of the NATS volumes
is encrypted at rest.

### Moving from RabbitMQ

The cut is hard: there is no dual-run and no dual-publish. Deploy the new backend and bot together,
provide NATS and the `BLACKHOLE` account, and drop `RABBITMQ_*` (it is ignored). Queued RabbitMQ
messages are discarded; this is safe because the Redis sync heals on the next mutation and the bot
re-reads its cursors from the backend.
