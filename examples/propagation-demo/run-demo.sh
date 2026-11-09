#!/usr/bin/env bash
# Runs the three demos in Docker Compose. Needs only Docker. Only the demos' lines go to stdout, and the containers are removed on any exit.
set -euo pipefail

cd "$(dirname "$0")"

clean_up() {
    local status=$?
    trap "" INT TERM HUP
    if [ "$status" -ne 0 ]; then
        docker compose --profile demo logs --no-color --tail 100 >&2 || true
    fi
    docker compose --profile demo --progress quiet down --volumes --remove-orphans ||
        docker compose --profile demo --progress quiet down --volumes --remove-orphans
}
trap clean_up EXIT

docker compose up --build --detach >&2

if ! test_output="$(docker compose --progress quiet run --rm -T context-loss-test 2>&1)"; then
    printf '%s\n' "$test_output" >&2
    exit 1
fi
if ! demo_1="$(printf '%s\n' "$test_output" | grep -E '^    (Demo 1: |  test  |  caught  )' | sed 's/^    //')"; then
    printf '%s\n' "$test_output" >&2
    echo "The Demo 1 test passed but printed no Demo 1 lines." >&2
    exit 1
fi
printf '%s\n' "$demo_1"

docker compose --progress quiet run --rm -T client
