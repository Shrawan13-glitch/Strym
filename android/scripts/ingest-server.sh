#!/usr/bin/env bash
# Run the core's RTMP ingest server on the host for local e2e + dev.
#
# Emulator clients publish to rtmp://10.0.2.2:1935/live/<key>; physical
# devices use the host LAN IP. Incoming streams are written to ./ingest/*.flv
# for inspection with ffprobe.
#
# Usage: ./ingest-server.sh [addr] [app]     (defaults: 0.0.0.0:1935, live)
set -euo pipefail
cd "$(dirname "$0")/../rust"

ADDR="${1:-0.0.0.0:1935}"
APP="${2:-live}"

echo ">> building ingest server (pinned core)"
cargo run --example ingest --quiet -- "$ADDR" "$APP"
