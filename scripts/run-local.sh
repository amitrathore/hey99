#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env.local
set +a
bash scripts/prepare-ui.sh
exec lein run
