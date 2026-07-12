#!/bin/bash
# Requires a passing ./gradlew :backend:test run before a commit that stages
# backend Java changes, per this project's "must runtime-verify" convention.
# Gated at commit time (not every turn) since tests need real MariaDB/RabbitMQ
# (docker/docker-compose.yml) and are too slow/heavy to run on every Stop event.
INPUT=$(cat)
cd "$CLAUDE_PROJECT_DIR" || exit 0

STAGED_JAVA=$(git diff --cached --name-only -- backend/src/main/java 2>/dev/null)
if [ -z "$STAGED_JAVA" ]; then
  exit 0
fi

echo "Staged backend Java changes detected; running ./gradlew :backend:test before allowing the commit..." >&2
if ! ./gradlew :backend:test --quiet > /tmp/blackhole-backend-test-precommit.log 2>&1; then
  echo "Blocked: ./gradlew :backend:test failed (or MariaDB/RabbitMQ from docker/docker-compose.yml aren't running — start them with 'cd docker && docker compose up -d'). See /tmp/blackhole-backend-test-precommit.log for details." >&2
  exit 2
fi
exit 0
