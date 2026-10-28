## Conventions for every PR

- Branch from the up-to-date `origin/main`. One PR for the code (this change), one follow-up for the
  ticked task list if the maintainer asks for it.
- Red, then green, then refactor: each group starts with the failing test named in it.
- F.I.R.S.T.: no `Thread.sleep`, no system time (inject `java.time.Clock`), no shared static state, fresh
  fixtures per test, one behaviour per test. Velocity tests use the existing `FakePlayer` helper.
- Commit per type. No Co-Authored-By trailer. The Flyway migration (if one is added) takes the next free
  version at implementation time; this change adds none.
- Never use `--force` or `--no-verify`.

## 1. Velocity: signed chat without SignedVelocity

- [ ] 1.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/MuteChatDenialTest.java`
  (update): a signed message from a muted player with the relay absent is not delivered and the
  disconnect is requested; the existing "never denied without SignedVelocity" assertion is replaced.
- [ ] 1.2 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/listener/PlayerChatListenerTest.java`
  (new): muted + signed + no relay -> disconnect with the mute screen, message not delivered, no backend
  chat signal; muted + unsigned + no relay -> denied, notice only; not muted -> delivered and scored.
- [ ] 1.3 Implement the disconnect path in `MuteChatDenial` and `PlayerChatListener`, using
  `PunishmentScreens` for the reason. Log once per player, no message text.

## 2. Velocity: muted commands

- [ ] 2.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/MuteCommandGateTest.java`
  (new): listed command denied for a muted sender with the relay present; unsigned listed command denied
  without the relay; signed listed command disconnected without the relay; unlisted command untouched;
  `minecraft:msg` matched as `msg`; empty list changes nothing.
- [ ] 2.2 Implement `MuteCommandGate` (new, in `enforcement/`) and register it from `BlackholeVelocity`
  next to the chat listener. Add `mute.commands` (default list) to the plugin configuration with a comment
  in the config class.

## 3. Velocity: relay state at startup

- [ ] 3.1 Red: `velocity/src/test/java/net/onelitefeather/blackhole/velocity/enforcement/RelayStateTest.java`
  (new): present -> state `present`; absent -> state `absent` and an ERROR line that names the
  consequence (asserted through a captured appender, not by reading console output).
- [ ] 3.2 Implement `RelayState` (new) and the ERROR line in `BlackholeVelocity`, replacing the WARN at
  lines 207-211. Keep `MuteChatDenial` and `CommandDenial` constructors unchanged.

## 4. Backend: relay state route, metric and banner

- [ ] 4.1 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/proxy/ProxyCapabilityServiceTest.java`
  (new, fixed `Clock`): a report stores `present` for the proxy; after twice the heartbeat interval with no
  report the state reads `unknown`; a report from a missing proxy id is refused with the Error variant.
- [ ] 4.2 Red: `backend/src/test/java/net/onelitefeather/blackhole/backend/proxy/ProxyCapabilityControllerTest.java`
  (new): the controller delegates once and maps the result to 204 / the Error response; no repository injection.
- [ ] 4.3 Implement `proxy/` package: `ProxyCapabilityApi` (OpenAPI annotations, PROXY role on
  `PUT /proxy/capabilities`), thin `ProxyCapabilityController`, `ProxyCapabilityService` (Dragonfly store with
  TTL), sealed `ProxyCapabilityDTO` (`Report`, `Response`, `Error`). Gauge `blackhole.proxy.signedvelocity`.
- [ ] 4.4 Regenerate the OpenAPI client: bump `inputSpec` in `client/build.gradle.kts` to the next version after
  0.0.44 at implementation time, then `./gradlew :client:openApiGenerate`. Do not edit generated sources.
- [ ] 4.5 Proxy reports its state: after startup and on the existing heartbeat, `velocity/.../punish/` gets a
  `ProxyCapabilityReporter` with a JUnit test (fixed scheduler, no real network; `BackendGateway` fake).

## 5. Web panel: banner

- [ ] 5.1 Red: `web/src/components/admin/RelayBanner.test.ts` (component test): hidden when every proxy is
  `present`; shows the proxy id and the consequence when one is `absent`; shows "unknown" when a proxy has
  not reported.
- [ ] 5.2 Implement `RelayBanner.vue` and mount it in the admin layout; fetch via the generated client.

## 6. Documentation and runbook

- [ ] 6.1 Update `README.md` section "Muting and signed chat (SignedVelocity)" and the command-rules paragraph:
  mutes block commands, signed chat without the relay disconnects the player, the startup error line.
- [ ] 6.2 Runbook: the checklist of every OLF backend server and its SignedVelocity build; pinned version
  (confirm the release first, see design open question 3); add a server = add the checklist step.
- [ ] 6.3 Re-check `openspec validate enforce-mutes-without-signed-velocity --strict` after edits.

## 7. Open the code PR

- [ ] 7.1 Run `./gradlew :velocity:test :backend:test` and the web unit tests; open the code PR titled
  `fix(velocity): enforce mutes without signed velocity` (no file under `openspec/`). Body from
  `.github/pull_request_template.md`, no session links.
