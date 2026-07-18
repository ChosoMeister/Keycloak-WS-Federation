#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_HOME="${1:-}"
ACTION="${2:-install}"
ARTIFACT="${SCRIPT_DIR}/target/keycloak-wsfed-26.7.0-1.jar"

usage() {
  echo "Usage: $0 <keycloak-home> [install|uninstall]" >&2
  exit 2
}

[[ -n "${KEYCLOAK_HOME}" ]] || usage
[[ -x "${KEYCLOAK_HOME}/bin/kc.sh" ]] || {
  echo "Not a Keycloak Quarkus distribution: ${KEYCLOAK_HOME}" >&2
  exit 1
}

case "${ACTION}" in
  install)
    [[ -f "${ARTIFACT}" ]] || {
      echo "Build the extension first: mvn clean package" >&2
      exit 1
    }
    install -m 0644 "${ARTIFACT}" "${KEYCLOAK_HOME}/providers/keycloak-wsfed.jar"
    ;;
  uninstall)
    rm -f -- "${KEYCLOAK_HOME}/providers/keycloak-wsfed.jar"
    ;;
  *) usage ;;
esac

"${KEYCLOAK_HOME}/bin/kc.sh" build
echo "WS-Federation extension ${ACTION} completed."
