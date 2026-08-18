#!/usr/bin/env bash
set -euo pipefail

# Publishes the AD-backed WS-Federation claims that AD FS relying parties expect:
# UPN, primary SID, and Name.
#
# Two layers are configured. LDAP mappers bring the Active Directory attributes into
# Keycloak user attributes, and WS-Federation protocol mappers emit those user attributes
# under the claim URIs the relying party knows. Both layers are idempotent.
#
# The Name claim deliberately carries DOMAIN\user rather than a display name, because that is
# what AD FS issues: its rule reads windowsaccountname and re-emits the value under the name
# claim type. Relying parties provisioned against AD FS expect that form.
#
# The primary SID is read through this extension's own LDAP mapper, which converts the binary
# objectSid into the S-1-5-21-... string AD FS issues. The stock attribute mapper would store
# base64 that no relying party can match against.

: "${KEYCLOAK_URL:?Set KEYCLOAK_URL, for example https://keycloak.example.com}"
: "${KEYCLOAK_ADMIN:?Set KEYCLOAK_ADMIN}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Set KEYCLOAK_ADMIN_PASSWORD}"
: "${WSFED_REALM:?Set WSFED_REALM to the Keycloak realm name}"
: "${WSFED_CLIENT_ID:?Set WSFED_CLIENT_ID to the relying-party wtrealm}"

KCADM="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
LDAP_ALIAS="${WSFED_LDAP_ALIAS:-}"

# Active Directory source attributes. msDS-PrincipalName is a constructed attribute holding
# the NT-style DOMAIN\user form; override it when a directory does not return it.
LDAP_UPN_ATTRIBUTE="${WSFED_LDAP_UPN_ATTRIBUTE:-userPrincipalName}"
LDAP_ACCOUNT_ATTRIBUTE="${WSFED_LDAP_ACCOUNT_ATTRIBUTE:-msDS-PrincipalName}"
LDAP_SID_ATTRIBUTE="${WSFED_LDAP_SID_ATTRIBUTE:-objectSid}"

# Keycloak user attributes used to carry the values between the two layers.
USER_ATTR_UPN='upn'
USER_ATTR_ACCOUNT='windowsAccountName'
USER_ATTR_SID='ad_primary_sid'

CLAIM_UPN='http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn'
CLAIM_NAME='http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name'
CLAIM_SID='http://schemas.microsoft.com/ws/2008/06/identity/claims/primarysid'

command -v jq >/dev/null || { echo "jq is required." >&2; exit 2; }

"${KCADM}" config credentials \
  --server "${KEYCLOAK_URL}" \
  --realm master \
  --user "${KEYCLOAK_ADMIN}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD}"

# --- Resolve the relying-party client -----------------------------------------------------

client_json=$("${KCADM}" get clients -r "${WSFED_REALM}" -q "clientId=${WSFED_CLIENT_ID}")
client_uuid=$(jq -r '.[0].id // empty' <<<"${client_json}")
client_protocol=$(jq -r '.[0].protocol // empty' <<<"${client_json}")

[[ -n "${client_uuid}" ]] || {
  echo "Client ${WSFED_CLIENT_ID} does not exist in realm ${WSFED_REALM}. Run configure-client.sh first." >&2
  exit 1
}
[[ "${client_protocol}" == "wsfed" ]] || {
  echo "Client ${WSFED_CLIENT_ID} uses protocol '${client_protocol}', not 'wsfed'." >&2
  exit 1
}

# SAML 1.1 and SAML 2.0 carry an attribute's identity differently, so the mappers have to be
# written to match whichever format the client actually issues. The client's own setting is
# authoritative; WSFED_TOKEN_FORMAT overrides it when the two are being changed together.
client_format=$(jq -r '.[0].attributes["wsfed.saml_assertion_token_format"] // "SAML 2.0"' <<<"${client_json}")
TOKEN_FORMAT="${WSFED_TOKEN_FORMAT:-${client_format}}"

case "${TOKEN_FORMAT}" in
  "SAML 2.0"|"SAML 1.1") ;;
  *) echo "Token format must be 'SAML 2.0' or 'SAML 1.1', got '${TOKEN_FORMAT}'." >&2; exit 2 ;;
esac

# --- Resolve the LDAP user federation provider --------------------------------------------

ldap_json=$("${KCADM}" get components -r "${WSFED_REALM}" \
  -q "type=org.keycloak.storage.UserStorageProvider")
ldap_json=$(jq '[.[] | select(.providerId == "ldap")]' <<<"${ldap_json}")

if [[ -n "${LDAP_ALIAS}" ]]; then
  ldap_id=$(jq -r --arg n "${LDAP_ALIAS}" '.[] | select(.name == $n) | .id' <<<"${ldap_json}")
  [[ -n "${ldap_id}" ]] || { echo "No LDAP provider named '${LDAP_ALIAS}' in realm ${WSFED_REALM}." >&2; exit 1; }
else
  ldap_count=$(jq 'length' <<<"${ldap_json}")
  case "${ldap_count}" in
    0) echo "Realm ${WSFED_REALM} has no LDAP user federation provider." >&2; exit 1 ;;
    1) ldap_id=$(jq -r '.[0].id' <<<"${ldap_json}") ;;
    *) echo "Realm ${WSFED_REALM} has ${ldap_count} LDAP providers. Set WSFED_LDAP_ALIAS to choose one." >&2
       jq -r '.[] | "  - " + .name' <<<"${ldap_json}" >&2
       exit 1 ;;
  esac
fi
LDAP_ALIAS=$(jq -r --arg id "${ldap_id}" '.[] | select(.id == $id) | .name' <<<"${ldap_json}")

# --- Declarative user profile check -------------------------------------------------------

# Since Keycloak 24 a realm rejects attributes that its user profile does not declare. The
# claims below are carried on unmanaged attributes, so a realm left at the default policy
# silently drops them and the relying party receives an assertion with no claims.
unmanaged_policy=$("${KCADM}" get "users/profile" -r "${WSFED_REALM}" \
  | jq -r '.unmanagedAttributePolicy // "DISABLED"')

if [[ "${unmanaged_policy}" == "DISABLED" ]]; then
  cat >&2 <<EOF
WARNING: realm ${WSFED_REALM} has unmanaged user attributes disabled.

  The mappers below will be created, but ${USER_ATTR_UPN}, ${USER_ATTR_ACCOUNT} and
  ${USER_ATTR_SID} will not be readable, so the assertion will carry no claims.

  Either enable unmanaged attributes for the realm:

    Realm settings -> General -> Unmanaged Attributes -> Enabled

  or declare the three attributes in the realm user profile.

EOF
fi

# --- LDAP attribute mappers ----------------------------------------------------------------

existing_ldap_mappers=$("${KCADM}" get components -r "${WSFED_REALM}" \
  -q "parent=${ldap_id}" -q "type=org.keycloak.storage.ldap.mappers.LDAPStorageMapper")

upsert_ldap_mapper() {
  local name="$1" user_attribute="$2" ldap_attribute="$3" payload existing_id

  # always.read.value.from.ldap keeps the value authoritative in the directory, so already
  # imported users pick it up on their next login without a federation re-sync.
  payload=$(jq -n \
    --arg name "$name" --arg parent "${ldap_id}" \
    --arg ua "$user_attribute" --arg la "$ldap_attribute" \
    '{name: $name, parentId: $parent, providerId: "user-attribute-ldap-mapper",
      providerType: "org.keycloak.storage.ldap.mappers.LDAPStorageMapper",
      config: {"user.model.attribute": [$ua], "ldap.attribute": [$la],
               "read.only": ["true"], "always.read.value.from.ldap": ["true"],
               "is.mandatory.in.ldap": ["false"]}}')

  existing_id=$(jq -r --arg n "$name" '.[] | select(.name == $n) | .id' <<<"${existing_ldap_mappers}")

  if [[ -n "${existing_id}" ]]; then
    printf '%s' "${payload}" | "${KCADM}" update "components/${existing_id}" -r "${WSFED_REALM}" -f -
    echo "  updated LDAP mapper ${name} (${ldap_attribute} -> ${user_attribute})"
  else
    printf '%s' "${payload}" | "${KCADM}" create components -r "${WSFED_REALM}" -f -
    echo "  created LDAP mapper ${name} (${ldap_attribute} -> ${user_attribute})"
  fi
}

# The SID needs this extension's own mapper rather than the stock attribute mapper, which would
# store the raw binary base64 encoded.
upsert_sid_mapper() {
  local name="$1" user_attribute="$2" ldap_attribute="$3" payload existing_id

  payload=$(jq -n \
    --arg name "$name" --arg parent "${ldap_id}" \
    --arg ua "$user_attribute" --arg la "$ldap_attribute" \
    '{name: $name, parentId: $parent, providerId: "wsfed-ad-primary-sid-mapper",
      providerType: "org.keycloak.storage.ldap.mappers.LDAPStorageMapper",
      config: {"user.model.attribute": [$ua], "ldap.attribute": [$la]}}')

  existing_id=$(jq -r --arg n "$name" '.[] | select(.name == $n) | .id' <<<"${existing_ldap_mappers}")

  if [[ -n "${existing_id}" ]]; then
    printf '%s' "${payload}" | "${KCADM}" update "components/${existing_id}" -r "${WSFED_REALM}" -f -
    echo "  updated SID mapper ${name} (${ldap_attribute} -> ${user_attribute})"
  else
    if ! printf '%s' "${payload}" | "${KCADM}" create components -r "${WSFED_REALM}" -f - 2>/dev/null; then
      cat >&2 <<EOF

ERROR: could not create the ${name} mapper.

  The wsfed-ad-primary-sid-mapper provider is not registered on this server. It ships with this
  extension, so the running Keycloak is using an older build. Rebuild the provider JAR from the
  current source and recreate the container, then run this script again.

EOF
      exit 1
    fi
    echo "  created SID mapper ${name} (${ldap_attribute} -> ${user_attribute})"
  fi
}

echo "LDAP provider: ${LDAP_ALIAS}"
upsert_ldap_mapper 'wsfed-upn'                  "${USER_ATTR_UPN}"     "${LDAP_UPN_ATTRIBUTE}"
upsert_ldap_mapper 'wsfed-windows-account-name' "${USER_ATTR_ACCOUNT}" "${LDAP_ACCOUNT_ATTRIBUTE}"
upsert_sid_mapper  'wsfed-primary-sid'          "${USER_ATTR_SID}"     "${LDAP_SID_ATTRIBUTE}"

# --- WS-Federation protocol mappers ---------------------------------------------------------

existing_protocol_mappers=$("${KCADM}" get "clients/${client_uuid}/protocol-mappers/models" \
  -r "${WSFED_REALM}")

upsert_protocol_mapper() {
  local name="$1" user_attribute="$2" claim_uri="$3" payload existing_id

  existing_id=$(jq -r --arg n "$name" '.[] | select(.name == $n) | .id' <<<"${existing_protocol_mappers}")

  # SAML 2.0 puts the whole claim URI in the attribute name and leaves FriendlyName unused.
  # SAML 1.1 splits it: this extension emits the attribute name as the SAML 1.1 AttributeName
  # and reads FriendlyName as the AttributeNamespace, and WIF rebuilds the claim URI by joining
  # the two. Sending the full URI as the name under SAML 1.1 therefore yields the wrong claim.
  local attribute_name="$claim_uri" attribute_namespace=""
  if [[ "${TOKEN_FORMAT}" == "SAML 1.1" ]]; then
    attribute_name="${claim_uri##*/}"
    attribute_namespace="${claim_uri%/*}"
  fi

  # An update is rejected unless the representation carries the mapper id as well.
  payload=$(jq -n \
    --arg name "$name" --arg ua "$user_attribute" --arg an "$attribute_name" \
    --arg ns "$attribute_namespace" --arg id "$existing_id" \
    '{name: $name, protocol: "wsfed",
      protocolMapper: "wsfed-saml-user-attribute-mapper",
      config: {"user.attribute": $ua, "attribute.name": $an,
               "attribute.nameformat": "URI Reference", "friendly.name": $ns}}
     + (if $id == "" then {} else {id: $id} end)')

  if [[ -n "${existing_id}" ]]; then
    printf '%s' "${payload}" | "${KCADM}" update \
      "clients/${client_uuid}/protocol-mappers/models/${existing_id}" -r "${WSFED_REALM}" -f -
    echo "  updated claim mapper ${name} -> ${claim_uri}"
  else
    printf '%s' "${payload}" | "${KCADM}" create \
      "clients/${client_uuid}/protocol-mappers/models" -r "${WSFED_REALM}" -f -
    echo "  created claim mapper ${name} -> ${claim_uri}"
  fi
}

echo "WS-Federation client: ${WSFED_CLIENT_ID} (token format: ${TOKEN_FORMAT})"
upsert_protocol_mapper 'upn'         "${USER_ATTR_UPN}"     "${CLAIM_UPN}"
upsert_protocol_mapper 'primary sid'  "${USER_ATTR_SID}"     "${CLAIM_SID}"
# AD FS sources the name claim from the Windows account name, so this is DOMAIN\user.
upsert_protocol_mapper 'name'         "${USER_ATTR_ACCOUNT}" "${CLAIM_NAME}"

cat <<EOF

Configured three AD-backed claims for ${WSFED_CLIENT_ID} in realm ${WSFED_REALM}.

Verify against a real account before handing the configuration to the relying party. The
${LDAP_ACCOUNT_ATTRIBUTE} attribute in particular is constructed rather than stored, and some
directories do not return it; when it is missing, set WSFED_LDAP_ACCOUNT_ATTRIBUTE to an
attribute that holds the DOMAIN\\user form. Sign a user in, then inspect the attributes that
were actually read from the directory:

  ${KCADM} get users -r ${WSFED_REALM} -q username=<user> --fields username,attributes
EOF
