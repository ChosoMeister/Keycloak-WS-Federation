#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
docker compose --project-directory "${PROJECT_DIR}" exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@"
