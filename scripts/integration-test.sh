#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${KEYCLOAK_URL:-http://127.0.0.1:8080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-change-me-now}"
REALM="${WSFED_REALM:-wsfed-demo}"
KCADM_WRAPPER="${KCADM:-$PWD/scripts/kcadm-compose.sh}"

certificate_directory=$(mktemp -d)
trap 'rm -rf -- "${certificate_directory}"' EXIT
certificate_file="${certificate_directory}/signing.pem"

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -subj '/CN=wsfed-integration-test' \
  -keyout "${certificate_directory}/signing.key" \
  -out "${certificate_file}" >/dev/null 2>&1

export KEYCLOAK_URL="${BASE_URL}"
export KEYCLOAK_ADMIN="${ADMIN_USER}"
export KEYCLOAK_ADMIN_PASSWORD="${ADMIN_PASSWORD}"
export WSFED_REALM="${REALM}"
export KCADM="${KCADM_WRAPPER}"

export WSFED_CLIENT_ID='urn:example:wsfed:integration-test'
export WSFED_REPLY_URL='http://localhost:9999/integration-test/callback'
export WSFED_TOKEN_FORMAT='SAML 2.0'
export WSFED_USE_JWT='false'
export WSFED_INCLUDE_X5T='false'
./scripts/configure-client.sh
./scripts/configure-client.sh

export WSFED_BROKER_ALIAS='integration-test-wsfed'
export WSFED_SSO_URL='https://idp.example.test/adfs/ls/'
export WSFED_SLO_URL='https://idp.example.test/adfs/ls/'
export WSFED_ISSUER_REALM='urn:example:keycloak:integration-test'
export WSFED_SIGNING_CERTIFICATE_FILE="${certificate_file}"
export WSFED_VALIDATE_SIGNATURE='true'
export WSFED_BACKCHANNEL_LOGOUT='false'
./scripts/configure-broker.sh
./scripts/configure-broker.sh

client_json=$("${KCADM}" get clients -r "${REALM}" -q "clientId=${WSFED_CLIENT_ID}")
broker_json=$("${KCADM}" get "identity-provider/instances/${WSFED_BROKER_ALIAS}" -r "${REALM}")

grep -q '"protocol" : "wsfed"' <<<"${client_json}"
grep -q '"wsfed.saml_assertion_token_format" : "SAML 2.0"' <<<"${client_json}"
grep -q '"providerId" : "wsfed"' <<<"${broker_json}"
grep -q '"validateSignature" : "true"' <<<"${broker_json}"
grep -q '"wsfedRealm" : "urn:example:keycloak:integration-test"' <<<"${broker_json}"

echo "WS-Federation idempotent configuration test passed for realm ${REALM}."
