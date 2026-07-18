#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${KEYCLOAK_URL:-http://127.0.0.1:8080}"
REALM="${WSFED_REALM:-wsfed-demo}"
CLIENT_ID="${WSFED_CLIENT_ID:-urn:example:wsfed:rp}"
REPLY_URL="${WSFED_REPLY_URL:-http://localhost:9999/callback}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-120}"

deadline=$((SECONDS + TIMEOUT_SECONDS))
until [[ "$(curl --max-time 2 -s -o /dev/null -w '%{http_code}' "${BASE_URL}/realms/${REALM}/.well-known/openid-configuration" || true)" == "200" ]]; do
  (( SECONDS < deadline )) || { echo "Keycloak did not become ready within ${TIMEOUT_SECONDS}s." >&2; exit 1; }
  sleep 1
done

metadata=$(curl --fail --max-time 10 --silent --show-error "${BASE_URL}/realms/${REALM}/protocol/wsfed/descriptor")
grep -q 'fed:SecurityTokenServiceType' <<<"${metadata}"
grep -q 'X509Certificate' <<<"${metadata}"

login_page=$(curl --fail --max-time 10 --silent --show-error --get \
  --data-urlencode 'wa=wsignin1.0' \
  --data-urlencode "wtrealm=${CLIENT_ID}" \
  --data-urlencode "wreply=${REPLY_URL}" \
  "${BASE_URL}/realms/${REALM}/protocol/wsfed")
grep -q 'name="username"' <<<"${login_page}"

invalid_status=$(curl --max-time 10 --silent --output /dev/null --write-out '%{http_code}' \
  "${BASE_URL}/realms/${REALM}/protocol/wsfed")
[[ "${invalid_status}" == "400" ]]

echo "WS-Federation smoke test passed for realm ${REALM}."
