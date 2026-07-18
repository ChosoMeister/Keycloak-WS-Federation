#!/usr/bin/env bash
set -euo pipefail

: "${KEYCLOAK_URL:?Set KEYCLOAK_URL, for example https://keycloak.example.com}"
: "${KEYCLOAK_ADMIN:?Set KEYCLOAK_ADMIN}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Set KEYCLOAK_ADMIN_PASSWORD}"
: "${WSFED_REALM:?Set WSFED_REALM to the Keycloak realm name}"
: "${WSFED_BROKER_ALIAS:?Set WSFED_BROKER_ALIAS, for example corporate-adfs}"
: "${WSFED_SSO_URL:?Set WSFED_SSO_URL to the external WS-Federation passive endpoint}"
: "${WSFED_ISSUER_REALM:?Set WSFED_ISSUER_REALM to the wtrealm sent to the external provider}"
: "${WSFED_SIGNING_CERTIFICATE_FILE:?Set WSFED_SIGNING_CERTIFICATE_FILE to the external signing certificate file}"

KCADM="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
SSO_LOGOUT_URL="${WSFED_SLO_URL:-}"
VALIDATE_SIGNATURE="${WSFED_VALIDATE_SIGNATURE:-true}"
BACKCHANNEL_LOGOUT="${WSFED_BACKCHANNEL_LOGOUT:-false}"

case "${VALIDATE_SIGNATURE}" in true|false) ;; *) echo "WSFED_VALIDATE_SIGNATURE must be true or false." >&2; exit 2 ;; esac
case "${BACKCHANNEL_LOGOUT}" in true|false) ;; *) echo "WSFED_BACKCHANNEL_LOGOUT must be true or false." >&2; exit 2 ;; esac
[[ -r "${WSFED_SIGNING_CERTIFICATE_FILE}" ]] || { echo "Cannot read signing certificate file." >&2; exit 2; }

signing_certificate=$(awk 'BEGIN {ORS="\\n"} {print}' "${WSFED_SIGNING_CERTIFICATE_FILE}")

"${KCADM}" config credentials \
  --server "${KEYCLOAK_URL}" \
  --realm master \
  --user "${KEYCLOAK_ADMIN}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}"

broker_payload=$(printf '{"alias":"%s","displayName":"%s","providerId":"wsfed","enabled":true,"trustEmail":false,"storeToken":false,"linkOnly":false,"firstBrokerLoginFlowAlias":"first broker login","config":{"singleSignOnServiceUrl":"%s","singleLogoutServiceUrl":"%s","wsfedRealm":"%s","signingCertificate":"%s","validateSignature":"%s","backchannelSupported":"%s","emptyActionHandledAsLogout":"false"}}' \
  "${WSFED_BROKER_ALIAS}" "${WSFED_BROKER_ALIAS}" "${WSFED_SSO_URL}" "${SSO_LOGOUT_URL}" "${WSFED_ISSUER_REALM}" "${signing_certificate}" "${VALIDATE_SIGNATURE}" "${BACKCHANNEL_LOGOUT}")

if "${KCADM}" get "identity-provider/instances/${WSFED_BROKER_ALIAS}" -r "${WSFED_REALM}" >/dev/null 2>&1; then
  printf '%s' "${broker_payload}" | "${KCADM}" update "identity-provider/instances/${WSFED_BROKER_ALIAS}" -r "${WSFED_REALM}" -f -
  echo "Updated WS-Federation broker ${WSFED_BROKER_ALIAS}."
else
  printf '%s' "${broker_payload}" | "${KCADM}" create identity-provider/instances -r "${WSFED_REALM}" -f -
  echo "Created WS-Federation broker ${WSFED_BROKER_ALIAS}."
fi

echo "Register this callback at the external provider:"
echo "${KEYCLOAK_URL}/realms/${WSFED_REALM}/broker/${WSFED_BROKER_ALIAS}/endpoint"
