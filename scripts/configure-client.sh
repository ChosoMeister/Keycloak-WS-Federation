#!/usr/bin/env bash
set -euo pipefail

: "${KEYCLOAK_URL:?Set KEYCLOAK_URL, for example https://keycloak.example.com}"
: "${KEYCLOAK_ADMIN:?Set KEYCLOAK_ADMIN}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Set KEYCLOAK_ADMIN_PASSWORD}"
: "${WSFED_REALM:?Set WSFED_REALM to the Keycloak realm name}"
: "${WSFED_CLIENT_ID:?Set WSFED_CLIENT_ID to the relying-party wtrealm}"
: "${WSFED_REPLY_URL:?Set WSFED_REPLY_URL to the exact relying-party callback URL}"

KCADM="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
TOKEN_FORMAT="${WSFED_TOKEN_FORMAT:-SAML 2.0}"
USE_JWT="${WSFED_USE_JWT:-false}"
INCLUDE_X5T="${WSFED_INCLUDE_X5T:-false}"

case "${TOKEN_FORMAT}" in
  "SAML 2.0"|"SAML 1.1") ;;
  *) echo "WSFED_TOKEN_FORMAT must be 'SAML 2.0' or 'SAML 1.1'." >&2; exit 2 ;;
esac
case "${USE_JWT}" in true|false) ;; *) echo "WSFED_USE_JWT must be true or false." >&2; exit 2 ;; esac
case "${INCLUDE_X5T}" in true|false) ;; *) echo "WSFED_INCLUDE_X5T must be true or false." >&2; exit 2 ;; esac

"${KCADM}" config credentials \
  --server "${KEYCLOAK_URL}" \
  --realm master \
  --user "${KEYCLOAK_ADMIN}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}"

client_uuid=$("${KCADM}" get clients -r "${WSFED_REALM}" -q "clientId=${WSFED_CLIENT_ID}" --fields id --format csv --noquotes | head -n 1)
client_payload=$(printf '{"clientId":"%s","name":"%s","protocol":"wsfed","enabled":true,"publicClient":true,"bearerOnly":false,"baseUrl":"%s","redirectUris":["%s"],"attributes":{"wsfed.saml_assertion_token_format":"%s","wsfed.jwt":"%s","wsfed.x5t":"%s"}}' \
  "${WSFED_CLIENT_ID}" "${WSFED_CLIENT_ID}" "${WSFED_REPLY_URL}" "${WSFED_REPLY_URL}" "${TOKEN_FORMAT}" "${USE_JWT}" "${INCLUDE_X5T}")

if [[ -n "${client_uuid}" ]]; then
  printf '%s' "${client_payload}" | "${KCADM}" update "clients/${client_uuid}" -r "${WSFED_REALM}" -f -
  echo "Updated WS-Federation client ${WSFED_CLIENT_ID}."
else
  printf '%s' "${client_payload}" | "${KCADM}" create clients -r "${WSFED_REALM}" -f -
  echo "Created WS-Federation client ${WSFED_CLIENT_ID}."
fi

echo "Metadata: ${KEYCLOAK_URL}/realms/${WSFED_REALM}/protocol/wsfed/descriptor"
echo "Endpoint: ${KEYCLOAK_URL}/realms/${WSFED_REALM}/protocol/wsfed"
