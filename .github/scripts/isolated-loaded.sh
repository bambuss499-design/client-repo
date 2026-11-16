#!/usr/bin/env bash
set -euo pipefail
# This process is inside --network none. All Gradle/Minecraft assets were
# fetched by the selector phase; the third-party client runs with fake login.
xvfb-run -a -s '-screen 0 1280x720x24 -ac +extension GLX +render -noreset' \
  ./gradlew --offline --no-daemon productionSmoke
# Keep the container alive while the post-exit helper replaces the old client.
for ((i=0; i<150; i++)); do
  if [[ -f "$CLOUDNINE_SMOKE_DIR/mods/cloudnine-test-selector.jar" && \
        ! -e "$CLOUDNINE_SMOKE_DIR/cloudnine/switch/next.properties" ]] && \
      compgen -G "$CLOUDNINE_SMOKE_DIR/mods/cloudnine-once-*.jar" >/dev/null; then
    exit 0
  fi
  sleep 0.2
done
echo 'Persistent switch helper did not finish inside the isolated container' >&2
exit 1
