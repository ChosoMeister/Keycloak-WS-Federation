#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${KEYCLOAK_URL:-http://127.0.0.1:8080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-change-me-now}"
REALM="${WSFED_REALM:-wsfed-demo}"
# Where curl reaches Keycloak from this host, when that differs from the URL kcadm uses.
HTTP_URL="${WSFED_TEST_HTTP_URL:-${BASE_URL}}"
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

grep -q 'BEGIN CERTIFICATE' <<<"${broker_json}"

echo "WS-Federation idempotent configuration test passed for realm ${REALM}."

# --- Active WS-Trust end to end ---------------------------------------------------------------

client_uuid=$(jq -r '.[0].id' <<<"${client_json}")
"${KCADM}" update "realms/${REALM}" -s 'attributes."wsfed.ws-trust.enabled"=true'
"${KCADM}" update "clients/${client_uuid}" -r "${REALM}" -s directAccessGrantsEnabled=true

test_user='wsfed-integration-user'
test_password='Integration-Pass-123'
if [[ -z "$("${KCADM}" get users -r "${REALM}" -q "username=${test_user}" -q exact=true --fields id --format csv --noquotes)" ]]; then
  "${KCADM}" create users -r "${REALM}" -s "username=${test_user}" -s enabled=true \
    -s email=wsfed-integration@example.test -s firstName=Integration -s lastName=User -s emailVerified=true
fi
"${KCADM}" set-password -r "${REALM}" --username "${test_user}" --new-password "${test_password}"

descriptor=$(curl -fsS "${HTTP_URL}/realms/${REALM}/protocol/wsfed/descriptor")
grep -q "/protocol/wsfed/usernamemixed</wsa:Address>" <<<"${descriptor}"
grep -q "/protocol/wsfed/mex</wsa:Address>" <<<"${descriptor}"
curl -fsS -o /dev/null "${HTTP_URL}/realms/${REALM}/protocol/wsfed/mex"

rst() {
  cat <<XML
<s:Envelope xmlns:s="$1" xmlns:a="http://www.w3.org/2005/08/addressing"><s:Header><a:MessageID>urn:uuid:integration</a:MessageID><o:Security xmlns:o="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><o:UsernameToken><o:Username>$2</o:Username><o:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText">$3</o:Password></o:UsernameToken></o:Security></s:Header><s:Body><trust:RequestSecurityToken xmlns:trust="http://docs.oasis-open.org/ws-sx/ws-trust/200512"><wsp:AppliesTo xmlns:wsp="http://schemas.xmlsoap.org/ws/2004/09/policy"><a:EndpointReference><a:Address>${WSFED_CLIENT_ID}</a:Address></a:EndpointReference></wsp:AppliesTo><trust:RequestType>http://docs.oasis-open.org/ws-sx/ws-trust/200512/Issue</trust:RequestType></trust:RequestSecurityToken></s:Body></s:Envelope>
XML
}
soap() {
  rst "$@" | curl -sS -X POST -H 'Content-Type: application/soap+xml' --data-binary @- \
    "${HTTP_URL}/realms/${REALM}/protocol/wsfed/usernamemixed"
}
SOAP12='http://www.w3.org/2003/05/soap-envelope'

issued=$(soap "${SOAP12}" "${test_user}" "${test_password}")
grep -q 'RequestSecurityTokenResponseCollection' <<<"${issued}"
grep -q 'AuthnStatement' <<<"${issued}"
grep -q 'RelatesTo' <<<"${issued}"
soap "${SOAP12}" "${test_user}" wrong-password | grep -q 'Authentication failed.'
soap 'http://schemas.xmlsoap.org/soap/envelope/' "${test_user}" "${test_password}" | grep -q 'Only SOAP 1.2'

"${KCADM}" update "clients/${client_uuid}" -r "${REALM}" -s directAccessGrantsEnabled=false
soap "${SOAP12}" "${test_user}" "${test_password}" | grep -q 'does not accept password sign-in'

"${KCADM}" update "realms/${REALM}" -s 'attributes."wsfed.ws-trust.enabled"=false'
[[ "$(curl -s -o /dev/null -w '%{http_code}' "${HTTP_URL}/realms/${REALM}/protocol/wsfed/mex")" == 404 ]]

echo "Active WS-Trust end-to-end test passed for realm ${REALM}."
