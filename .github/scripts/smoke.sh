#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
export LIBGL_ALWAYS_SOFTWARE=1
export ALSOFT_DRIVERS=null
mkdir -p build/smoke-logs run/mods run/cloudnine/clients run/cloudnine/smoke
cp build/smoke/cloudnine-smoke-harness.jar run/mods/
cp 'build/smoke/Moonlit Fixture.jar' 'run/cloudnine/clients/Moonlit Fixture.jar'
printf 'guiScale:2\n' > run/options.txt

pid=''
cleanup() { if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi; }
trap cleanup EXIT

wait_for_file() {
  local file="$1" seconds="$2"
  for ((i=0; i<seconds*2; i++)); do
    if [[ -s "$file" ]]; then return 0; fi
    if [[ -f run/cloudnine/smoke/FAILED ]]; then cat run/cloudnine/smoke/FAILED; return 1; fi
    if ! kill -0 "$pid" 2>/dev/null; then echo "Minecraft exited before $file"; return 1; fi
    sleep 0.5
  done
  echo "Timed out waiting for $file"
  return 1
}

launch() {
  local phase="$1"
  rm -f run/cloudnine/smoke/title-ready run/cloudnine/smoke/FAILED
  echo "Launching Minecraft: $phase"
  CLOUDNINE_SMOKE_PHASE="$phase" timeout 400s ./gradlew --no-daemon runClient >"build/smoke-logs/$phase.log" 2>&1 &
  pid=$!
  wait_for_file run/cloudnine/smoke/title-ready 300
}
finish() {
  local phase="$1"
  if ! wait "$pid"; then
    echo "Minecraft $phase failed"; tail -200 "build/smoke-logs/$phase.log"; return 1
  fi
  pid=''
  if [[ -f run/cloudnine/smoke/FAILED ]]; then cat run/cloudnine/smoke/FAILED; return 1; fi
  test -f "run/cloudnine/smoke/$phase"
}

launch stage
wait_for_file run/cloudnine/smoke/stage 90
finish stage
test -f run/screenshots/cloudnine-smoke.png
cp run/screenshots/cloudnine-smoke.png build/cloudnine-screen.png

launch loaded
finish loaded
for ((i=0; i<100; i++)); do
  if ! compgen -G 'run/mods/cloudnine-once-*.jar' >/dev/null; then break; fi
  sleep 0.2
done
if compgen -G 'run/mods/cloudnine-once-*.jar' >/dev/null; then
  echo 'One-shot cleanup did not remove the managed JAR after the game exited'
  cat run/cloudnine/cleanup.log || true
  exit 1
fi

launch empty
finish empty
echo 'PASS: Right Alt opened the GUI; selected fixture loaded for one session only.'
