# Changelog

This project uses the Keycloak version it targets as its own version, followed by a build number:
`26.7.0-1` is the first build for Keycloak `26.7.x`.

## Unreleased

Targets Keycloak `26.7.x`. Verified against `26.6.3` and `26.7.0`.

### Behaviour changes

These are corrections rather than features, but they change how the extension responds to input it
previously accepted. Read them before upgrading a working deployment.

- **An assertion with no audience restriction is rejected.** It was previously accepted. Such an
  assertion is not scoped to any relying party, so the same issuer may have minted it for a
  different one. A broker whose external identity provider omits `AudienceRestriction` stops
  working, and the provider has to be configured to send it. There is deliberately no switch to
  turn the check off.
- **A response carrying anything other than exactly one assertion is rejected.** Signature
  validation and claim extraction ran over separately parsed copies of the response, so a signed
  decoy alongside an unsigned assertion was a signature wrapping vector.
- **A client that does not use the `wsfed` protocol can no longer obtain a token.** `wtrealm` was
  resolved by client id alone, so any OIDC or SAML client in the realm could be driven through the
  security token service.
- **An invalid `wreply` is an error instead of falling back to the client base URL.** A rejected
  reply address previously produced a successful login to a different address, with nothing logged.
  An absent `wreply` still falls back to the base URL, as before.
- **`wfresh` is honoured.** Re-authentication was never required, so a relying party asking for a
  bounded authentication age was silently downgraded to single sign-on. Relying parties that send
  `wfresh` will now see users prompted to authenticate again when the session is older than the
  bound they asked for.

### Added

- `wsfed.token.lifespan.from-session` lets a client's tokens follow the user's Keycloak session:
  the earlier of SSO Session Idle and the remainder of SSO Session Max, computed by Keycloak's own
  session logic. The lifetime is then managed from the realm's Sessions settings in the console,
  which does not offer an assertion lifespan field for `wsfed` clients. An explicit
  `saml.assertion.lifespan` still takes precedence.

- An active WS-Trust endpoint at `/realms/{realm}/protocol/wsfed/usernamemixed`, for relying
  parties that authenticate without a browser. It takes a WS-Trust 1.3 RequestSecurityToken in a
  SOAP envelope with a UsernameToken and issues the same signed token the passive flow does. Off
  unless a realm sets `wsfed.ws-trust.enabled`, since it accepts a password directly.
- A WS-MetadataExchange endpoint at `/realms/{realm}/protocol/wsfed/mex`, serving the WSDL a client
  reads to discover the active endpoint. Answers under the same realm attribute, which also makes
  the federation metadata announce the WS-Trust namespaces now that they are true.

- `wsfed.metadata.claim-types` and `wsfed.metadata.announce-ws-trust` realm attributes steer the
  federation metadata. The claim types advertised are no longer fixed, and the WS-Trust namespaces
  are no longer announced by default: this extension implements the passive requestor profile only,
  and a client told otherwise looks for an active endpoint that does not exist.

- Federation metadata publishes `fed:TokenTypesOffered`, `fed:ClaimTypesOffered`, WS-Trust
  namespaces in `protocolSupportEnumeration`, and a service display name. Strict consumers such as
  the Dynamics 365 on-premises claims-based authentication wizard reject a descriptor without them.
- `saml.server.signature.keyinfo.xmlSigKeyInfoKeyNameTransformer` is honoured, using the same
  values and the same `KEY_ID` default as Keycloak's SAML protocol. Setting it to `NONE` omits
  `KeyName` from the signature, which relying parties built on Windows Identity Foundation require;
  they report its presence as `ID4037`.
- `wsfed-ad-primary-sid-mapper`, an LDAP mapper that reads the binary Active Directory `objectSid`
  and stores it as a user attribute in its `S-1-5-21-...` string form, so it can be issued as the
  `primarysid` claim. Keycloak converts `objectGUID` but has no equivalent for the SID.
- `scripts/configure-ad-claims.sh`, which configures the UPN, primary SID, and Name claims that
  AD FS relying parties expect, across both the LDAP and protocol mapper layers.

### Fixed

- The token lifetime could not be changed, and its shortest window was 60 seconds. A relying party
  such as Dynamics 365 ties its session to the shortest validity in the token, so users were signed
  out a minute after signing in. `saml.assertion.lifespan` is now honoured with the same meaning as
  in Keycloak's SAML protocol, and replaces the condition window, the subject confirmation window
  and the WS-Trust `Lifetime` together. Unset, the realm defaults apply exactly as before.

- The admin console crashed with `Cannot read properties of undefined (reading 'helpText')` on the
  dedicated client scope page of any `wsfed` client. Nine of the twelve built-in mappers referenced
  mapper types that were not registered for the protocol.
- No claim mapping was applied to JWT tokens at all, for the same reason.
- The broker callback used a redirect target taken from the `wctx` relay state without validating
  it against a registered redirect URI, which is an open redirect.
- Logout failed with a server error when the user had no session for the client named by `wtrealm`,
  or when the logout URL could not be resolved. Both were reachable unauthenticated.
- `RequestedUnattachedReference` identified a freshly generated id rather than the issued assertion.
- The broker now also accepts the WSS SAML 1.1 token profile URI that AD FS announces.
- An attribute mapper saved with neither a name nor a friendly name matched the first attribute in
  the assertion rather than nothing.
- Apostrophes are escaped in generated HTML attributes.

## 26.7.0-1

First release for Keycloak 26.7.
