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
cp 'build/smoke/Moonlit Fixture.jar' "$game/cloudnine/clients/Moonlit Fixture.jar"
python3 - "$game/cloudnine/clients/Moonlit Fixture.zip" <<'PYCODE'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w') as archive:
    archive.writestr('README.txt', 'Synthetic source fixture; not a client.')
PYCODE
printf 'version:4671\nguiScale:2\nonboardAccessibility:false\n' > "$game/options.txt"

pid=''
cleanup() {
  if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  mkdir -p build/production-evidence
  for path in screenshots crash-reports options.txt cloudnine/cleanup.log logs/latest.log; do
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
  CLOUDNINE_SMOKE_PHASE="$phase" timeout 400s ./gradlew --no-daemon productionSmoke >"build/smoke-logs/$phase.log" 2>&1 &
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
wait_for_file "$game/cloudnine/smoke/stage" 90
finish stage
test -f "$game/screenshots/cloudnine-smoke.png"
cp "$game/screenshots/cloudnine-smoke.png" build/cloudnine-screen.png

launch loaded
finish loaded
for ((i=0; i<100; i++)); do
  if ! compgen -G "$game/mods/cloudnine-once-*.jar" >/dev/null; then break; fi
  sleep 0.2
done
if compgen -G "$game/mods/cloudnine-once-*.jar" >/dev/null; then
  echo 'One-shot cleanup did not remove the managed JAR after the game exited'
  cat "$game/cloudnine/cleanup.log" || true
  exit 1
fi

launch empty
finish empty
echo 'PASS: Right Alt opened the GUI; selected fixture loaded for one session only.'
