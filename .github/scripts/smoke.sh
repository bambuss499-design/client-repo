#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export LIBGL_ALWAYS_SOFTWARE=1
export ALSOFT_DRIVERS=null
# Keep the game outside Gradle's build output tree so its stale-output cleanup cannot
# remove the selected mod between launches (and never touch the developer's run/).
game=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/cloudnine-smoke-XXXXXX")
export CLOUDNINE_SMOKE_DIR="$game"
mkdir -p build/smoke-logs "$game/mods" "$game/cloudnine/clients" "$game/cloudnine/smoke"
cp 'build/libs/cloudnine-0.3.0.jar' "$game/mods/cloudnine-0.3.0.jar"
target=${CLOUDNINE_SMOKE_TARGET:-fixture}
if [[ "$target" == fixture ]]; then
  cp 'build/smoke/Moonlit Fixture.jar' "$game/cloudnine/smoke/Moonlit Fixture.jar"
  python3 - "$game/cloudnine/clients/Moonlit Fixture.zip" <<'PYCODE'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as archive:
    archive.writestr('README.txt', 'Synthetic source fixture; not a client.')
PYCODE
fi
printf 'version:4671\nguiScale:2\nonboardAccessibility:false\nkey_key.cloudnine.open:key.keyboard.right.alt\n' > "$game/options.txt"

pid=''
cleanup() {
  if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  mkdir -p build/production-evidence
  for path in screenshots crash-reports options.txt cloudnine/cleanup.log cloudnine/handoff.log logs/latest.log; do
    if [[ -e "$game/$path" ]]; then cp -a "$game/$path" build/production-evidence/ || true; fi
  done
  rm -rf "$game"
}
trap cleanup EXIT

wait_for_file() {
  local file="$1" seconds="$2"
  for ((i=0; i<seconds*2; i++)); do
    if [[ -s "$file" ]]; then return 0; fi
    if [[ -f "$game/cloudnine/smoke/FAILED" ]]; then cat "$game/cloudnine/smoke/FAILED"; return 1; fi
    if ! kill -0 "$pid" 2>/dev/null; then echo "Minecraft exited before $file"; return 1; fi
    sleep 0.5
  done
  echo "Timed out waiting for $file"
  return 1
}

launch() {
  local phase="$1"
  rm -f "$game/cloudnine/smoke/title-ready" "$game/cloudnine/smoke/FAILED"
  echo "Launching Minecraft: $phase"
  if [[ "$target" == dgclient && "$phase" == loaded && -n "${CLOUDNINE_SMOKE_DOCKER_IMAGE:-}" ]]; then
    # Run third-party code with no network or repository credentials. Keep the
    # container alive briefly after Minecraft exits so the return helper can run.
    timeout 400s docker run --rm --network none --user "$(id -u):$(id -g)" \
      -v "$PWD:$PWD" -w "$PWD" -v "$HOME/.gradle:$HOME/.gradle" -v "$game:$game" \
      -e HOME="$HOME" -e GRADLE_USER_HOME="$HOME/.gradle" -e CLOUDNINE_SMOKE_DIR="$game" \
      -e CLOUDNINE_SMOKE_PHASE="$phase" -e CLOUDNINE_SMOKE_TARGET="$target" \
      -e LIBGL_ALWAYS_SOFTWARE=1 -e ALSOFT_DRIVERS=null "$CLOUDNINE_SMOKE_DOCKER_IMAGE" \
      bash .github/scripts/isolated-loaded.sh >"build/smoke-logs/$phase.log" 2>&1 &
  else
    CLOUDNINE_SMOKE_PHASE="$phase" CLOUDNINE_SMOKE_TARGET="$target" timeout 400s ./gradlew --no-daemon productionSmoke >"build/smoke-logs/$phase.log" 2>&1 &
  fi
  pid=$!
  wait_for_file "$game/cloudnine/smoke/title-ready" 120
}
finish() {
  local phase="$1"
  if ! wait "$pid"; then
    echo "Minecraft $phase failed"; tail -200 "build/smoke-logs/$phase.log"; return 1
  fi
  pid=''
  if [[ -f "$game/cloudnine/smoke/FAILED" ]]; then cat "$game/cloudnine/smoke/FAILED"; return 1; fi
  test -f "$game/cloudnine/smoke/$phase"
}

launch stage
wait_for_file "$game/cloudnine/smoke/stage" 180
finish stage
test -f "$game/screenshots/cloudnine-smoke.png"
cp "$game/screenshots/cloudnine-smoke.png" build/cloudnine-screen.png

# The independent helper must move the selector out of mods and activate the
# chosen client plus the temporary return guard only after this JVM exits.
for ((i=0; i<150; i++)); do
  if [[ ! -f "$game/mods/cloudnine-0.3.0.jar" ]] && compgen -G "$game/mods/cloudnine-once-*.jar" >/dev/null \
      && compgen -G "$game/mods/cloudnine-return-*.jar" >/dev/null; then break; fi
  sleep 0.2
done
if [[ -f "$game/mods/cloudnine-0.3.0.jar" ]] || ! compgen -G "$game/mods/cloudnine-once-*.jar" >/dev/null \
    || ! compgen -G "$game/mods/cloudnine-return-*.jar" >/dev/null; then
  echo 'One-session handoff did not prepare correctly'; cat "$game/cloudnine/handoff.log" || true; exit 1
fi

launch loaded
finish loaded
for ((i=0; i<150; i++)); do
  if [[ -f "$game/mods/cloudnine-0.3.0.jar" ]] && ! compgen -G "$game/mods/cloudnine-once-*.jar" >/dev/null \
      && ! compgen -G "$game/mods/cloudnine-return-*.jar" >/dev/null; then break; fi
  sleep 0.2
done
if [[ ! -f "$game/mods/cloudnine-0.3.0.jar" ]] || compgen -G "$game/mods/cloudnine-once-*.jar" >/dev/null \
    || compgen -G "$game/mods/cloudnine-return-*.jar" >/dev/null; then
  echo 'One-shot handoff did not restore the selector after the game exited'
  cat "$game/cloudnine/handoff.log" || true
  exit 1
fi

launch empty
finish empty
echo "PASS: Left Alt selected $target; it loaded without Cloud Nine, which was then restored."
