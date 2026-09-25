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
command -v jq >/dev/null || { echo "jq is required." >&2; exit 2; }
SSO_LOGOUT_URL="${WSFED_SLO_URL:-}"
VALIDATE_SIGNATURE="${WSFED_VALIDATE_SIGNATURE:-true}"
BACKCHANNEL_LOGOUT="${WSFED_BACKCHANNEL_LOGOUT:-false}"

case "${VALIDATE_SIGNATURE}" in true|false) ;; *) echo "WSFED_VALIDATE_SIGNATURE must be true or false." >&2; exit 2 ;; esac
case "${BACKCHANNEL_LOGOUT}" in true|false) ;; *) echo "WSFED_BACKCHANNEL_LOGOUT must be true or false." >&2; exit 2 ;; esac
[[ -r "${WSFED_SIGNING_CERTIFICATE_FILE}" ]] || { echo "Cannot read signing certificate file." >&2; exit 2; }

"${KCADM}" config credentials \
  --server "${KEYCLOAK_URL}" \
  --realm master \
  --user "${KEYCLOAK_ADMIN}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}"

# Built with jq so the certificate's line breaks and any quote in a value stay valid JSON.
broker_payload=$(jq -n \
  --arg alias "${WSFED_BROKER_ALIAS}" --arg sso "${WSFED_SSO_URL}" --arg slo "${SSO_LOGOUT_URL}" \
  --arg realm "${WSFED_ISSUER_REALM}" --rawfile cert "${WSFED_SIGNING_CERTIFICATE_FILE}" \
  --arg validate "${VALIDATE_SIGNATURE}" --arg backchannel "${BACKCHANNEL_LOGOUT}" \
  '{alias: $alias, displayName: $alias, providerId: "wsfed", enabled: true, trustEmail: false,
    storeToken: false, linkOnly: false, firstBrokerLoginFlowAlias: "first broker login",
    config: {singleSignOnServiceUrl: $sso, singleLogoutServiceUrl: $slo, wsfedRealm: $realm,
             signingCertificate: ($cert | sub("\\s+$"; "")), validateSignature: $validate,
             backchannelSupported: $backchannel, emptyActionHandledAsLogout: "false"}}')

if "${KCADM}" get "identity-provider/instances/${WSFED_BROKER_ALIAS}" -r "${WSFED_REALM}" >/dev/null 2>&1; then
  printf '%s' "${broker_payload}" | "${KCADM}" update "identity-provider/instances/${WSFED_BROKER_ALIAS}" -r "${WSFED_REALM}" -f -
  echo "Updated WS-Federation broker ${WSFED_BROKER_ALIAS}."
else
  printf '%s' "${broker_payload}" | "${KCADM}" create identity-provider/instances -r "${WSFED_REALM}" -f -
  echo "Created WS-Federation broker ${WSFED_BROKER_ALIAS}."
fi

echo "Register this callback at the external provider:"
echo "${KEYCLOAK_URL}/realms/${WSFED_REALM}/broker/${WSFED_BROKER_ALIAS}/endpoint"
