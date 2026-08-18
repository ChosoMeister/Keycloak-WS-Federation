# Keycloak WS-Federation Extension

[فارسی](#فارسی) · [English](#english)

---

## English

A maintained WS-Federation 1.2 passive-requestor extension for modern, Quarkus-based Keycloak.

It enables Keycloak to operate as:

- an Identity Provider / Security Token Service for WS-Federation relying parties; and
- an identity broker connected to an external WS-Federation Identity Provider.

This maintained edition replaces the obsolete WildFly-era integration with a reworked Keycloak 26.x provider, a new Java namespace, hardened defaults, and a modern build and test workflow. Compatible wire-level identifiers are preserved where practical.

> [!IMPORTANT]
> This extension uses internal Keycloak SPIs. Its minor version must match the Keycloak minor version, and compatibility must be verified before every Keycloak upgrade.

### Current compatibility

| Extension | Keycloak | Java | Runtime | Status |
|---|---:|---:|---|---|
| `26.7.x` | `26.7.x` | 21 | Quarkus | Build and Docker smoke-tested |

Keycloak releases other than `26.7.x` are not currently claimed as compatible.

### Five-minute quick start

The repository includes a complete local stack: Keycloak 26.7, PostgreSQL, the built provider, and an imported `wsfed-demo` realm with an example relying-party client.

```bash
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
export KC_BOOTSTRAP_ADMIN_PASSWORD='replace-this-password'

docker compose up --detach --build
./scripts/smoke-test.sh
```

Open `http://localhost:8080/admin/`, sign in with the bootstrap credentials, and select the `wsfed-demo` realm. The imported client is `urn:example:wsfed:rp` and its demonstration reply URL is `http://localhost:9999/callback`.

Useful local URLs:

```text
Admin Console: http://localhost:8080/admin/
Metadata:      http://localhost:8080/realms/wsfed-demo/protocol/wsfed/descriptor
Protocol:      http://localhost:8080/realms/wsfed-demo/protocol/wsfed
Health:        http://localhost:9000/health/ready
```

Stop the stack without deleting the PostgreSQL volume:

```bash
docker compose down
```

Delete the demonstration database as well:

```bash
docker compose down --volumes
```

> [!WARNING]
> The Compose stack is a runnable example, not a production topology. Replace all demonstration passwords and URLs. Configure TLS, a fixed hostname, proxy headers, secret management, backups, clustering, monitoring, and production PostgreSQL settings before deployment.

### Supported scope

- WS-Federation 1.2 passive sign-in requests (`wsignin1.0`)
- WS-Federation passive sign-out handling
- IdP / STS metadata generation
- External WS-Federation Identity Provider brokering
- SAML 2.0 assertions
- Legacy SAML 1.1 assertion tokens
- User attribute, user property, group, and role protocol mappers
- Broker attribute-to-user and attribute-to-role mappers

The following are outside the current scope:

- active WS-Trust endpoints;
- attribute and pseudonym services; and
- a guaranteed interoperability claim for every third-party AD FS or WS-Federation implementation.

### Build

Requirements:

- JDK 21
- Maven 3.9+
- Docker, only for the container and runtime tests

Build and run the unit test suite:

```bash
mvn clean verify
```

If Maven is not installed locally, build with Docker:

```bash
docker run --rm \
  -u "$(id -u):$(id -g)" \
  -e MAVEN_CONFIG=/tmp/m2 \
  -v "$PWD:/workspace" \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-21 \
  mvn -B -Dmaven.repo.local=/tmp/m2/repository clean verify
```

The self-contained provider is generated at:

```text
keycloak-wsfed/target/keycloak-wsfed-26.7.0-1.jar
```

Prebuilt release artifacts and `SHA256SUMS` are published on the [GitHub Releases](https://github.com/ChosoMeister/Keycloak-WS-Federation/releases) page. Verify the checksum before installing a downloaded JAR.

### Installation

Copy the provider JAR into the Quarkus provider directory and rebuild Keycloak:

```bash
cp keycloak-wsfed/target/keycloak-wsfed-26.7.0-1.jar \
  /opt/keycloak/providers/keycloak-wsfed.jar

/opt/keycloak/bin/kc.sh build
```

The included installer performs the same operation:

```bash
./keycloak-wsfed/install.sh /opt/keycloak install
```

Uninstall the provider and rebuild the optimized server image:

```bash
./keycloak-wsfed/install.sh /opt/keycloak uninstall
```

Do not copy this extension into WildFly modules and do not modify `standalone.xml`. Those instructions only apply to obsolete Keycloak distributions.

### Container image

```dockerfile
FROM quay.io/keycloak/keycloak:26.7.0 AS builder

COPY keycloak-wsfed.jar /opt/keycloak/providers/keycloak-wsfed.jar
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Start it using the normal Keycloak production options for your database, hostname, TLS, proxy, cache, and observability configuration.

#### Upgrading the extension in a running container

> [!IMPORTANT]
> Replacing the JAR is not enough on its own. Keycloak resolves providers from the augmented server tree produced by `kc.sh build`, so a container that was already built keeps serving the previous version of the extension until it is rebuilt and replaced.

`docker compose restart` reuses the existing container and therefore keeps the old provider. Recreate the container instead:

```bash
docker compose up --detach --build --force-recreate keycloak
```

When the JAR is bind-mounted into `providers/` rather than baked into the image, confirm that the new file is the one the container sees and that no older copy remains beside it:

```bash
docker compose exec -T keycloak ls -l /opt/keycloak/providers/
```

To confirm which build is actually serving traffic, compare the registered mapper types against the built-in mappers. Every built-in must resolve to a registered type; any that does not indicates a stale provider:

```bash
curl -s -H "Authorization: Bearer ${TOKEN}" "${KEYCLOAK_URL}/admin/serverinfo" \
  | jq '{types: (.protocolMapperTypes.wsfed | length),
         unresolved: ([.builtinProtocolMappers.wsfed[].protocolMapper]
                      - [.protocolMapperTypes.wsfed[].id] | length)}'
```

A current build reports eight mapper types and no unresolved built-ins. Do not compare JAR checksums between machines: the archive embeds build timestamps, so identical sources produce different digests.

### Adding the extension to an existing production Keycloak

Use this runbook when Keycloak is already installed and serving traffic. Do not test a new provider for the first time on the only production instance.

#### 1. Pre-deployment checks

1. Confirm the running version with `/opt/keycloak/bin/kc.sh --version`. This release supports **Keycloak 26.7.x only**; do not install it on another minor version.
2. Build the JAR with `mvn clean verify`, or download the JAR artifact produced by this repository's successful CI run. Record its SHA-256 with `sha256sum keycloak-wsfed.jar`.
3. Back up the Keycloak database and current realm export/configuration according to your recovery procedure. The provider does not intentionally migrate the database, but the later client and broker configuration changes are stored there.
4. Record the current Keycloak image tag, startup command, build-time options, environment, mounted files, and the existing contents of `providers/`.
5. Test the same Keycloak build, database type, proxy/hostname settings, and representative realm configuration in staging.

Provider JARs are trusted server code with the permissions of the Keycloak process. Install only an artifact you built or verified. See Keycloak's official [provider configuration](https://www.keycloak.org/server/configuration-provider) documentation.

#### 2A. Direct ZIP/package installation

The following example assumes Keycloak is installed at `/opt/keycloak` and managed by a systemd unit named `keycloak`. Adjust the path, service account, service name, and **build-time options** to match the existing deployment.

```bash
# Copy the tested artifact to the server before entering the maintenance window.
sha256sum /tmp/keycloak-wsfed.jar

# Stop traffic to this node, then stop Keycloak.
sudo systemctl stop keycloak

# Preserve the currently deployed extension when this is an upgrade.
if sudo test -f /opt/keycloak/providers/keycloak-wsfed.jar; then
  sudo cp -p /opt/keycloak/providers/keycloak-wsfed.jar \
    /opt/keycloak/providers/keycloak-wsfed.jar.before-upgrade
fi

sudo install -o keycloak -g keycloak -m 0644 \
  /tmp/keycloak-wsfed.jar \
  /opt/keycloak/providers/keycloak-wsfed.jar

# Reuse every build-time option from the current installation.
sudo -u keycloak /opt/keycloak/bin/kc.sh build --db=postgres

sudo systemctl start keycloak
sudo systemctl status keycloak --no-pager
curl --fail --silent --show-error http://127.0.0.1:9000/health/ready
```

`--db=postgres` above is only an example. Omitting or changing an existing build-time option can produce a server different from the currently approved build. After a successful build, keep the existing production startup command; optimized deployments should continue to use `start --optimized`.

For a multi-node installation, remove one node from the load balancer, update and validate it, return it to service, and then continue one node at a time. All active nodes should run the same Keycloak and extension versions. Do not replace the provider simultaneously on every node.

#### 2B. Existing Docker or Podman deployment

Do not use `docker cp` or modify a running container. Build a new immutable image from the exact Keycloak version already approved in production:

```dockerfile
FROM quay.io/keycloak/keycloak:26.7.0 AS builder

COPY --chown=keycloak:keycloak --chmod=0644 \
  keycloak-wsfed.jar /opt/keycloak/providers/keycloak-wsfed.jar
RUN /opt/keycloak/bin/kc.sh build --db=postgres

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Build, scan, tag, and push an immutable version, for example `registry.example.com/keycloak-wsfed:26.7.0-1`. Deploy that tag with the existing runtime environment, secrets, database, hostname, TLS, proxy, cache, and `start --optimized` arguments. Keycloak's official [container guide](https://www.keycloak.org/server/containers) also requires the provider to be copied before the build step.

#### 2C. Existing Kubernetes deployment

Use the immutable image produced in the previous step; do not mount or inject the provider JAR into running Pods. For a regular Kubernetes Deployment:

```bash
kubectl -n identity set image deployment/keycloak \
  keycloak=registry.example.com/keycloak-wsfed:26.7.0-1

kubectl -n identity rollout status deployment/keycloak --timeout=10m
kubectl -n identity get pods
```

Preserve the existing readiness/liveness probes and use a rolling strategy that keeps a validated instance available. Confirm that Pod restarts remain at zero after the rollout.

With the Keycloak Operator, set the custom image in the Keycloak custom resource and keep the Operator version aligned with the Keycloak image version:

```yaml
apiVersion: k8s.keycloak.org/v2beta1
kind: Keycloak
metadata:
  name: keycloak
spec:
  image: registry.example.com/keycloak-wsfed:26.7.0-1
  startOptimized: true
```

Keep the resource's existing `instances`, hostname, TLS, database, scheduling, and secret configuration; the snippet shows only the fields relevant to the custom image. See the official [custom Keycloak image](https://www.keycloak.org/operator/customizing-keycloak) guide.

#### 3. Post-deployment validation

Do not configure a production relying party until all checks pass:

1. Readiness is healthy on every node: `/health/ready` on the management port, normally `9000`.
2. Startup logs contain no provider loading error, linkage error, `ClassNotFoundException`, or `NoSuchMethodError`.
3. **Identity Providers → Add provider** contains `WS-Fed`.
4. A test realm/client returns HTTP 200 and XML from `/realms/{realm}/protocol/wsfed/descriptor`.
5. A controlled `wsignin1.0` request reaches the login flow and preserves the expected `wtrealm`, `wreply`, and `wctx`.
6. Existing OIDC and SAML smoke tests, login, logout, database connectivity, cluster discovery, metrics, and memory/startup baselines remain healthy.

Only then apply the client or broker configuration described below. Start with one non-critical relying party and keep signature validation enabled.

#### 4. Rollback

If validation fails, stop routing traffic to the affected instance. For containers or Kubernetes, redeploy the previously recorded immutable image tag. A regular Deployment can be reverted with:

```bash
kubectl -n identity rollout undo deployment/keycloak
kubectl -n identity rollout status deployment/keycloak --timeout=10m
```

For a direct installation, stop Keycloak, remove the new JAR, restore `keycloak-wsfed.jar.before-upgrade` when one existed, run `kc.sh build` again with the original build-time options, and restart the service. If client or broker configuration was already changed, restore those records or the database/realm backup separately. Removing the JAR does not remove configuration stored in the database.

### Configuration

#### What appears in the Admin Console?

The extension has two distinct modes:

| Mode | Admin Console behavior |
|---|---|
| Keycloak brokers to an external WS-Federation IdP | `WS-Fed` appears under **Identity Providers → Add provider**. Its configuration form is generated from the provider properties. |
| Keycloak issues WS-Federation tokens to a relying party | No separate top-level menu is added. Keycloak's client-creation wizard does not provide a supported UI extension point for this custom login protocol. Create the `protocol=wsfed` client with the supplied script, Admin REST API, or realm import. The client is visible after creation. |

If `WS-Fed` is missing from **Identity Providers**, verify that the JAR is in `providers/`, that `kc.sh build` completed, and that the running image contains the rebuilt `/opt/keycloak` tree.

#### Why does the client type read `wsfed` rather than `WS-Federation`?

The Admin Console resolves protocol labels through a hardcoded `getProtocolName` switch that covers only the protocols shipped with Keycloak and falls back to returning the raw provider id. No SPI, theme, or message bundle overrides it, so the label can only be changed by renaming the provider id itself. That id is also the `/realms/{realm}/protocol/wsfed` path segment and the `protocol` value stored on every client and realm export, so it is deliberately left as `wsfed` to preserve wire-level and realm-export compatibility.

Two console limitations follow from the same lack of an extension point and are expected, not defects: the **Capability config** wizard step renders empty for `wsfed` clients, and no WS-Federation entry is added to the client-creation UI beyond the client-type dropdown. Clients created through the wizard, the supplied scripts, the Admin REST API, or realm import all work correctly.

#### Keycloak as a WS-Federation Identity Provider

In this mode, a legacy application or service such as a WS-Federation relying party redirects the browser to Keycloak. Keycloak authenticates the user and posts a signed token back to the application's reply URL.

The quickest production-oriented setup uses the idempotent helper. For the Compose stack, use its `kcadm` wrapper:

```bash
export KEYCLOAK_URL='http://localhost:8080'
export KEYCLOAK_ADMIN='admin'
export KEYCLOAK_ADMIN_PASSWORD='replace-this-password'
export WSFED_REALM='wsfed-demo'
export WSFED_CLIENT_ID='urn:example:wsfed:rp'
export WSFED_REPLY_URL='https://application.example.com/wsfed/callback'
export WSFED_TOKEN_FORMAT='SAML 2.0'
export WSFED_USE_JWT='false'
export WSFED_INCLUDE_X5T='false'
export KCADM="$PWD/scripts/kcadm-compose.sh"

./scripts/configure-client.sh
```

For a standalone Keycloak distribution, point `KCADM` at its binary instead:

```bash
export KCADM='/opt/keycloak/bin/kcadm.sh'
./scripts/configure-client.sh
```

The script creates the client when absent and updates it when it already exists. It configures:

- a unique client ID representing the relying-party realm, for example `urn:example:wsfed:rp`;
- `protocol=wsfed`;
- the exact trusted reply/redirect URI;
- SAML 2.0, SAML 1.1, or JWT token selection; and
- optional `x5t` inclusion for JWT compatibility.

| Variable | Required | Meaning |
|---|---|---|
| `KEYCLOAK_URL` | Yes | Public base URL of Keycloak, without a trailing slash |
| `KEYCLOAK_ADMIN` | Yes | Administrative username used by `kcadm` |
| `KEYCLOAK_ADMIN_PASSWORD` | Yes | Administrative password; prefer a short-lived automation credential |
| `WSFED_REALM` | Yes | Keycloak realm containing the relying-party client |
| `WSFED_CLIENT_ID` | Yes | Exact value the relying party sends as `wtrealm` |
| `WSFED_REPLY_URL` | Yes | Exact callback that receives the WS-Federation response |
| `WSFED_TOKEN_FORMAT` | No | `SAML 2.0` (default) or `SAML 1.1` |
| `WSFED_USE_JWT` | No | `true` sends a JWT instead of a SAML assertion; default `false` |
| `WSFED_INCLUDE_X5T` | No | Include an `x5t` header in JWT mode; default `false` |
| `KCADM` | No | Path to `kcadm.sh`; defaults to `/opt/keycloak/bin/kcadm.sh` |

The relying party starts authentication with a browser request similar to:

```text
GET https://keycloak.example.com/realms/production/protocol/wsfed
    ?wa=wsignin1.0
    &wtrealm=urn:example:wsfed:rp
    &wreply=https%3A%2F%2Fapplication.example.com%2Fwsfed%2Fcallback
    &wctx=opaque-application-state
```

Keycloak validates `wreply` against the client's registered redirect URIs. After authentication it sends an auto-submitted HTML form containing `wa`, `wresult`, and `wctx` to that reply URL. The relying party must validate the returned XML signature, issuer, audience, validity interval, and replay policy.

Metadata for the realm is available at:

```text
https://keycloak.example.com/realms/production/protocol/wsfed/descriptor
```

Do not use broad wildcard redirect URIs in production.

#### Keycloak as a WS-Federation broker

In this mode, Keycloak redirects its users to an external WS-Federation IdP such as AD FS, consumes the signed response, and links or imports the external identity.

From the Admin Console:

1. Select the target realm.
2. Open **Identity Providers**.
3. Choose **Add provider → WS-Fed**.
4. Set an alias that will remain stable, for example `corporate-adfs`.
5. Keycloak 26.7's generic identity-provider form requires **Client ID** and **Client Secret** for every user-defined provider. WS-Federation does not use either value; enter a non-sensitive placeholder such as `not-used-by-wsfed` in both fields. Do not put a real application secret there.
6. Enter the passive SSO endpoint, external realm, and signing certificate.
7. Leave **Validate signatures** enabled.
8. Save, then register the displayed Keycloak callback at the external IdP.

The form was verified in the Keycloak 26.7 Admin Console. It displays **Alias**, **Display name**, the two unused generic client fields, **Single sign-on service URL**, **Single logout service URL**, **WS-Federation realm**, **Signing certificate**, **Validate signatures**, **Back-channel logout**, and **Empty action means logout**. The supplied script is recommended for automation because it writes only the settings the provider actually consumes.

The same configuration can be applied repeatedly with the helper script:

```bash
export KEYCLOAK_URL='https://keycloak.example.com'
export KEYCLOAK_ADMIN='admin'
export KEYCLOAK_ADMIN_PASSWORD='replace-this-password'
export WSFED_REALM='production'
export WSFED_BROKER_ALIAS='corporate-adfs'
export WSFED_SSO_URL='https://adfs.example.com/adfs/ls/'
export WSFED_SLO_URL='https://adfs.example.com/adfs/ls/'
export WSFED_ISSUER_REALM='urn:keycloak:production'
export WSFED_SIGNING_CERTIFICATE_FILE='/secure/adfs-signing.pem'
export WSFED_VALIDATE_SIGNATURE='true'
export WSFED_BACKCHANNEL_LOGOUT='false'
export KCADM='/opt/keycloak/bin/kcadm.sh'

./scripts/configure-broker.sh
```

| Field / variable | Meaning |
|---|---|
| Alias / `WSFED_BROKER_ALIAS` | Stable Keycloak identifier used in the callback path |
| Single sign-on URL / `WSFED_SSO_URL` | External passive-requestor endpoint |
| Single logout URL / `WSFED_SLO_URL` | Optional external logout endpoint |
| WS-Federation realm / `WSFED_ISSUER_REALM` | `wtrealm` Keycloak sends to the external provider |
| Signing certificate | PEM or Base64 external assertion-signing certificate |
| Validate signatures | Secure default `true`; rejects assertions that cannot be verified |
| Back-channel logout | Calls the configured logout endpoint server-to-server when enabled |
| Empty action means logout | Compatibility switch for providers that omit `wa` on logout responses |

Register this callback URL at the external IdP:

```text
https://keycloak.example.com/realms/production/broker/corporate-adfs/endpoint
```

After saving, the provider can appear on the realm login page. Use the Identity Provider's **Mappers** tab to map external assertion attributes to Keycloak user attributes or roles.

Signature validation should remain enabled in production.

#### Active Directory claims for AD FS relying parties

Relying parties written against AD FS expect Microsoft claim URIs rather than Keycloak's
default attribute names. When users arrive through LDAP user federation, `scripts/configure-ad-claims.sh`
configures both halves of that mapping and is safe to re-run:

```bash
export KEYCLOAK_URL='https://keycloak.example.com'
export KEYCLOAK_ADMIN='admin'
export KEYCLOAK_ADMIN_PASSWORD='replace-this-password'
export WSFED_REALM='production'
export WSFED_CLIENT_ID='urn:example:wsfed:rp'

./scripts/configure-ad-claims.sh
```

| Claim | URI | Directory attribute |
|---|---|---|
| UPN | `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/upn` | `userPrincipalName` |
| Name | `http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name` | `displayName` |
| Windows account name | `http://schemas.microsoft.com/ws/2008/06/identity/claims/windowsaccountname` | `msDS-PrincipalName` |

| Variable | Required | Meaning |
|---|---|---|
| `WSFED_LDAP_ALIAS` | Only with several LDAP providers | Name of the provider to attach the mappers to |
| `WSFED_LDAP_UPN_ATTRIBUTE` | No | Source attribute for UPN; default `userPrincipalName` |
| `WSFED_LDAP_NAME_ATTRIBUTE` | No | Source attribute for Name; default `displayName` |
| `WSFED_LDAP_ACCOUNT_ATTRIBUTE` | No | Source attribute for the account name; default `msDS-PrincipalName` |

Two conditions decide whether the claims actually reach the relying party.

Since Keycloak 24 a realm rejects attributes its user profile does not declare. These claims travel
on unmanaged attributes, so a realm left at the default policy issues an assertion with no claims at
all. Set **Realm settings → General → Unmanaged Attributes** to *Enabled*, or declare the three
attributes in the user profile. The script warns when the realm would drop them.

`msDS-PrincipalName` is a constructed attribute rather than a stored one, and some directories do not
return it. Confirm it arrives for a real account, and otherwise point `WSFED_LDAP_ACCOUNT_ATTRIBUTE`
at an attribute holding the `DOMAIN\user` form.

The primary SID claim is intentionally not configured here. Active Directory stores `objectSid` as
binary and AD FS converts it to its SDDL string form before issuing it. Keycloak performs no such
conversion, so mapping the attribute directly emits base64 that no relying party can match against.
Issuing that claim faithfully requires a dedicated LDAP mapper.

#### Certificate format and rotation

The Broker accepts a PEM certificate or its Base64 certificate body. Supply the public signing certificate, never the private key. When an upstream IdP rotates certificates, update the configured certificate before the old key expires. Metadata auto-import is not implemented yet, so certificate rotation is an explicit administrative operation.

#### Troubleshooting

| Symptom | Check |
|---|---|
| `WS-Fed` is absent from Add provider | JAR location, `kc.sh build` output, container stage copying `/opt/keycloak`, and Keycloak/extension minor-version match |
| `unknown login requester` | `wtrealm` must exactly equal the WS-Federation client's `clientId` |
| `invalid redirect uri` | `wreply` must exactly match a registered client redirect URI |
| Broker response signature failure | Correct upstream signing certificate, certificate rotation, XML signature algorithm, and unmodified response body |
| Login loops after external IdP | Broker callback URL, cookies/HTTPS, proxy headers, public hostname, and first-broker-login flow |
| Provider works with `start-dev` but not `start --optimized` | Re-run `kc.sh build` after copying the JAR and rebuild the final container image |
| Slow production startup | Compare an identically built baseline, database migrations, cache topology, DNS/TLS, mounted storage, and JVM limits; do not compare first-build time with optimized startup |

### Endpoints

| Purpose | Endpoint |
|---|---|
| Protocol endpoint | `/realms/{realm}/protocol/wsfed` |
| IdP / STS metadata | `/realms/{realm}/protocol/wsfed/descriptor` |
| Broker callback | `/realms/{realm}/broker/{alias}/endpoint` |

The protocol identifier remains `wsfed` for realm-export and wire-level compatibility.

### Security defaults

- Assertion signature validation is enabled unless explicitly disabled.
- XML external entities, DTDs, and external schemas are disabled.
- Broker response bodies are limited to 1 MiB.
- context values are limited to 8 KiB.
- Generated HTML form attributes are escaped.
- Only supported GET/POST response methods are accepted.
- Logout redirects must belong to a concrete client and match its registered redirect URIs.
- The legacy JavaScript mapper is not registered.

Provider JARs execute with the permissions of the Keycloak process and must be treated as trusted server code. Review configuration changes and never disable signature validation merely to work around an integration problem.

### Verified test results

The following checks were performed on 2026-07-18 using the official `quay.io/keycloak/keycloak:26.7.0` image, Docker Engine 29.4.0, and Linux/ARM64 containers on an ARM64 host.

#### Build and runtime checks

| Check | Result |
|---|---|
| Maven clean build | Passed |
| Unit tests | 5 passed, 0 failed, 0 errors, 0 skipped |
| Provider installation through `kc.sh build` | Passed |
| WS-Federation factories visible in Keycloak server info | Passed |
| Test realm creation | Passed |
| Client creation with `protocol=wsfed` | Passed |
| Metadata endpoint | HTTP 200, `application/xml` |
| Standard `wsignin1.0` request | HTTP 200 login page and authentication session created |
| Runtime `ERROR`, `FATAL`, or exception entries | None observed |

The tested `26.7.0-1` release JAR was 671 KiB and had this SHA-256 digest:

```text
9b5b1e4aea591f7b067f2f6a7610aaa0edc3c9736cd5717e69faed4086a9cbc8
```

#### Startup and metadata measurements

Both images were explicitly augmented with `kc.sh build` and started with the same optimized production-mode command. Readiness was measured by polling the master realm OpenID configuration endpoint.

| Measurement | Keycloak baseline | Keycloak with extension |
|---|---:|---:|
| Keycloak-reported startup time | 9.281 s | 8.320 s |
| External readiness measurement | 9.727 s | 8.774 s |

Fifty sequential requests to the WS-Federation metadata endpoint produced:

| Requests | Average | Minimum | Maximum |
|---:|---:|---:|---:|
| 50 | 3.759 ms | 2.750 ms | 6.782 ms |

The running extension-enabled test container reported approximately 668.6 MiB of memory, 69 processes/threads as reported by Docker, and 1.14% CPU at the sampled instant.

> [!NOTE]
> These are local smoke-test observations from one ARM64 Docker environment, not statistically significant performance guarantees. The faster extension startup in this sample should be treated as run-to-run variance. The useful conclusion is that no severe startup regression was reproduced under this test setup. Production benchmarking requires repeated runs, controlled resources, the production database/cache topology, realistic realm data, and representative traffic.

### Current validation limits

The smoke test validates provider discovery, Keycloak startup, realm/client configuration, metadata generation, and entry into the WS-Federation login flow. A complete token round trip against an independent external WS-Federation IdP or relying party, including signed assertion consumption and logout interoperability, has not yet been automated. Do not treat the current results as complete third-party interoperability certification.

CI additionally runs `scripts/integration-test.sh`, which executes both configuration helpers twice and verifies their resulting client and broker records. This catches create/update regressions without claiming external IdP interoperability.

### License and attribution

The repository-level distribution license is GNU Affero General Public License 3.0. The project contains adapted code from earlier open-source WS-Federation implementations, and individual files retain applicable original copyright and license notices, including Apache-2.0 and LGPL notices. Review [NOTICE](NOTICE) and the relevant source headers before redistribution; the repository license does not erase file-level attribution or license obligations.

See [LICENSE](LICENSE) and [NOTICE](NOTICE) for license and attribution details. The maintained Java namespace is `io.github.chosomeister.keycloak`.

---

## فارسی

این پروژه یک افزونه نگه‌داری‌شده برای پشتیبانی از WS-Federation 1.2 Passive Requestor در نسخه‌های جدید و مبتنی بر Quarkus سرویس Keycloak است.

این افزونه به Keycloak اجازه می‌دهد در دو نقش فعالیت کند:

- ارائه‌دهنده هویت / سرویس صدور توکن برای سامانه‌های متکی بر WS-Federation؛ و
- واسط هویتی متصل به یک ارائه‌دهنده هویت خارجی مبتنی بر WS-Federation.

این نسخه نگه‌داری‌شده، روش منسوخ نصب روی WildFly را با یک Provider بازطراحی‌شده برای Keycloak 26.x، namespace جدید Java، تنظیمات امنیتی سخت‌گیرانه‌تر و فرایند مدرن Build و Test جایگزین می‌کند. شناسه‌های ارتباطی سازگار تا حد ممکن حفظ شده‌اند.

> [!IMPORTANT]
> این افزونه از SPIهای داخلی Keycloak استفاده می‌کند. نسخه minor افزونه باید با نسخه minor سرویس Keycloak یکسان باشد و پیش از هر ارتقای Keycloak، سازگاری آن مجدداً آزمایش شود.

### سازگاری فعلی

| نسخه افزونه | نسخه Keycloak | Java | محیط اجرا | وضعیت |
|---|---:|---:|---|---|
| `26.7.x` | `26.7.x` | 21 | Quarkus | Build و Smoke Test مبتنی بر Docker انجام شده |

در حال حاضر سازگاری با نسخه‌هایی غیر از `26.7.x` ادعا نمی‌شود.

### راه‌اندازی پنج‌دقیقه‌ای

مخزن شامل یک محیط محلی کامل است: Keycloak 26.7، PostgreSQL، Provider ساخته‌شده و Realm آماده `wsfed-demo` همراه با یک Client نمونه برای Relying Party.

```bash
export KC_BOOTSTRAP_ADMIN_USERNAME=admin
export KC_BOOTSTRAP_ADMIN_PASSWORD='replace-this-password'

docker compose up --detach --build
./scripts/smoke-test.sh
```

آدرس `http://localhost:8080/admin/` را باز کنید، با اطلاعات Bootstrap وارد شوید و Realm با نام `wsfed-demo` را انتخاب کنید. Client واردشده `urn:example:wsfed:rp` است و Reply URL نمایشی آن `http://localhost:9999/callback` است.

آدرس‌های کاربردی محیط محلی:

```text
Admin Console: http://localhost:8080/admin/
Metadata:      http://localhost:8080/realms/wsfed-demo/protocol/wsfed/descriptor
Protocol:      http://localhost:8080/realms/wsfed-demo/protocol/wsfed
Health:        http://localhost:9000/health/ready
```

توقف stack بدون حذف داده PostgreSQL:

```bash
docker compose down
```

حذف دیتابیس نمایشی همراه با stack:

```bash
docker compose down --volumes
```

> [!WARNING]
> محیط Compose یک نمونه قابل اجرا است، نه معماری Production. تمام passwordها و URLهای نمونه را تغییر دهید. پیش از استقرار، TLS، hostname ثابت، proxy headers، مدیریت secret، backup، clustering، monitoring و تنظیمات Production دیتابیس را اعمال کنید.

### قابلیت‌های تحت پوشش

- درخواست ورود Passive در WS-Federation 1.2 با `wsignin1.0`
- مدیریت خروج Passive در WS-Federation
- تولید metadata برای IdP / STS
- اتصال Broker به ارائه‌دهنده هویت خارجی WS-Federation
- Assertionهای SAML 2.0
- توکن‌های قدیمی Assertion مبتنی بر SAML 1.1
- Mapperهای ویژگی کاربر، مشخصات کاربر، گروه و نقش
- Mapperهای Broker برای تبدیل ویژگی‌ها به کاربر و نقش

موارد زیر فعلاً خارج از محدوده پروژه هستند:

- endpointهای Active WS-Trust؛
- سرویس‌های Attribute و Pseudonym؛ و
- تضمین سازگاری با تمام پیاده‌سازی‌های AD FS یا WS-Federation شرکت‌های ثالث.

### ساخت پروژه

پیش‌نیازها:

- JDK 21
- Maven 3.9 یا جدیدتر
- Docker فقط برای تست کانتینر و اجرای واقعی

برای Build و اجرای تست‌های واحد:

```bash
mvn clean verify
```

اگر Maven روی سیستم نصب نیست، می‌توان پروژه را با Docker ساخت:

```bash
docker run --rm \
  -u "$(id -u):$(id -g)" \
  -e MAVEN_CONFIG=/tmp/m2 \
  -v "$PWD:/workspace" \
  -w /workspace \
  maven:3.9.11-eclipse-temurin-21 \
  mvn -B -Dmaven.repo.local=/tmp/m2/repository clean verify
```

فایل مستقل Provider در مسیر زیر ساخته می‌شود:

```text
keycloak-wsfed/target/keycloak-wsfed-26.7.0-1.jar
```

فایل JAR آماده و `SHA256SUMS` در صفحه [GitHub Releases](https://github.com/ChosoMeister/Keycloak-WS-Federation/releases) منتشر می‌شوند. پیش از نصب JAR دانلودشده، checksum آن را بررسی کنید.

### نصب

فایل JAR را داخل پوشه Providerهای توزیع Quarkus کپی و Keycloak را مجدداً Build کنید:

```bash
cp keycloak-wsfed/target/keycloak-wsfed-26.7.0-1.jar \
  /opt/keycloak/providers/keycloak-wsfed.jar

/opt/keycloak/bin/kc.sh build
```

اسکریپت همراه پروژه همین عملیات را انجام می‌دهد:

```bash
./keycloak-wsfed/install.sh /opt/keycloak install
```

برای حذف Provider و بازسازی سرور بهینه‌شده:

```bash
./keycloak-wsfed/install.sh /opt/keycloak uninstall
```

این افزونه را داخل moduleهای WildFly کپی نکنید و فایل `standalone.xml` را تغییر ندهید. این روش‌ها فقط متعلق به نسخه‌های منسوخ‌شده Keycloak هستند.

### ساخت image کانتینر

```dockerfile
FROM quay.io/keycloak/keycloak:26.7.0 AS builder

COPY keycloak-wsfed.jar /opt/keycloak/providers/keycloak-wsfed.jar
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

برای اجرای نهایی، تنظیمات استاندارد محیط Production شامل دیتابیس، hostname، TLS، پراکسی، cache و observability را مطابق معماری خود اعمال کنید.

#### به‌روزرسانی افزونه در کانتینر در حال اجرا

> [!IMPORTANT]
> جایگزینی JAR به‌تنهایی کافی نیست. Keycloak پروایدرها را از درخت augment‌شده‌ای که `kc.sh build` تولید می‌کند resolve می‌کند؛ بنابراین کانتینری که قبلاً build شده تا زمان بازسازی و جایگزینی، همچنان نسخه قبلی افزونه را سرو می‌کند.

دستور `docker compose restart` کانتینر موجود را دوباره استفاده می‌کند و در نتیجه پروایدر قدیمی باقی می‌ماند. به‌جای آن کانتینر را recreate کنید:

```bash
docker compose up --detach --build --force-recreate keycloak
```

اگر JAR به‌جای قرارگرفتن داخل image به `providers/` بایند شده است، مطمئن شوید کانتینر همان فایل جدید را می‌بیند و نسخه قدیمی در کنار آن باقی نمانده است:

```bash
docker compose exec -T keycloak ls -l /opt/keycloak/providers/
```

برای اطمینان از اینکه کدام build واقعاً در حال سرویس‌دهی است، Mapper Typeهای ثبت‌شده را با Mapperهای پیش‌فرض مقایسه کنید. هر Mapper پیش‌فرض باید به یک Type ثبت‌شده resolve شود و هر مورد resolve‌نشده نشانه باقی‌ماندن پروایدر قدیمی است:

```bash
curl -s -H "Authorization: Bearer ${TOKEN}" "${KEYCLOAK_URL}/admin/serverinfo" \
  | jq '{types: (.protocolMapperTypes.wsfed | length),
         unresolved: ([.builtinProtocolMappers.wsfed[].protocolMapper]
                      - [.protocolMapperTypes.wsfed[].id] | length)}'
```

یک build به‌روز، هشت Mapper Type و صفر مورد resolve‌نشده گزارش می‌کند. از مقایسه checksum فایل JAR بین ماشین‌ها استفاده نکنید: آرشیو حاوی timestamp ساخت است و سورس یکسان روی ماشین‌های مختلف digest متفاوت تولید می‌کند.

### افزودن افزونه به Keycloak عملیاتی موجود

این Runbook برای زمانی است که Keycloak از قبل نصب شده و در حال سرویس‌دهی است. افزونه جدید را برای اولین بار روی تنها instance محیط Production آزمایش نکنید.

#### ۱. بررسی‌های پیش از انتشار

1. نسخه در حال اجرا را با `/opt/keycloak/bin/kc.sh --version` بررسی کنید. این نسخه افزونه فقط از **Keycloak 26.7.x** پشتیبانی می‌کند و نباید روی minor version دیگری نصب شود.
2. فایل JAR را با `mvn clean verify` بسازید یا artifact مربوط به اجرای موفق CI همین مخزن را دریافت کنید. مقدار SHA-256 را با `sha256sum keycloak-wsfed.jar` ثبت کنید.
3. طبق Runbook بازیابی سازمان، از دیتابیس Keycloak و Realm/configuration فعلی backup بگیرید. Provider عمداً migration دیتابیس اجرا نمی‌کند، اما تنظیمات Client و Broker که بعداً اعمال می‌شوند در دیتابیس ذخیره خواهند شد.
4. image tag، فرمان startup، build-time optionها، متغیرهای محیطی، فایل‌های mountشده و محتوای فعلی `providers/` را ثبت کنید.
5. همین نسخه Keycloak، نوع دیتابیس، تنظیمات proxy/hostname و Realm نزدیک به Production را ابتدا در Staging آزمایش کنید.

فایل Provider یک کد مورد اعتماد با سطح دسترسی پردازش Keycloak است. فقط artifact ساخته‌شده یا اعتبارسنجی‌شده را نصب کنید. جزئیات رسمی در مستند [تنظیم Providerهای Keycloak](https://www.keycloak.org/server/configuration-provider) موجود است.

#### ۲-الف. نصب مستقیم ZIP یا Package

نمونه زیر فرض می‌کند Keycloak در `/opt/keycloak` نصب شده و نام unit در systemd برابر `keycloak` است. مسیرها، service account، نام سرویس و به‌خصوص **build-time optionها** را با محیط واقعی خود تطبیق دهید.

```bash
# پیش از maintenance window، artifact تست‌شده را روی سرور قرار دهید.
sha256sum /tmp/keycloak-wsfed.jar

# ابتدا ترافیک را از این Node خارج و سپس Keycloak را متوقف کنید.
sudo systemctl stop keycloak

# در زمان Upgrade نسخه قبلی افزونه را نگه دارید.
if sudo test -f /opt/keycloak/providers/keycloak-wsfed.jar; then
  sudo cp -p /opt/keycloak/providers/keycloak-wsfed.jar \
    /opt/keycloak/providers/keycloak-wsfed.jar.before-upgrade
fi

sudo install -o keycloak -g keycloak -m 0644 \
  /tmp/keycloak-wsfed.jar \
  /opt/keycloak/providers/keycloak-wsfed.jar

# تمام build-time optionهای نصب فعلی را دوباره استفاده کنید.
sudo -u keycloak /opt/keycloak/bin/kc.sh build --db=postgres

sudo systemctl start keycloak
sudo systemctl status keycloak --no-pager
curl --fail --silent --show-error http://127.0.0.1:9000/health/ready
```

مقدار `--db=postgres` فقط نمونه است. حذف یا تغییر build-time optionهای فعلی می‌تواند خروجی متفاوتی از Build تأییدشده ایجاد کند. پس از Build موفق، فرمان Production موجود را حفظ کنید؛ محیط optimized باید همچنان با `start --optimized` اجرا شود.

در Cluster چند Node، یک Node را از Load Balancer خارج کنید، آن را ارتقا داده و اعتبارسنجی کنید، سپس به سرویس برگردانید و سراغ Node بعدی بروید. تمام Nodeهای فعال باید نسخه یکسان Keycloak و افزونه را اجرا کنند. Provider را هم‌زمان روی تمام Nodeها جایگزین نکنید.

#### ۲-ب. استقرار موجود Docker یا Podman

از `docker cp` استفاده نکنید و container در حال اجرا را تغییر ندهید. بر پایه دقیقاً همان نسخه Keycloak تأییدشده در Production، یک image تغییرناپذیر جدید بسازید:

```dockerfile
FROM quay.io/keycloak/keycloak:26.7.0 AS builder

COPY --chown=keycloak:keycloak --chmod=0644 \
  keycloak-wsfed.jar /opt/keycloak/providers/keycloak-wsfed.jar
RUN /opt/keycloak/bin/kc.sh build --db=postgres

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
```

Image را Build و scan کرده و با یک tag تغییرناپذیر مانند `registry.example.com/keycloak-wsfed:26.7.0-1` منتشر کنید. همان environment، secretها، دیتابیس، hostname، TLS، proxy، cache و آرگومان‌های `start --optimized` محیط فعلی را برای نسخه جدید حفظ کنید. [راهنمای رسمی Container در Keycloak](https://www.keycloak.org/server/containers) نیز تأکید می‌کند Provider باید قبل از مرحله Build کپی شود.

#### ۲-ج. استقرار موجود Kubernetes

از image تغییرناپذیر مرحله قبل استفاده کنید؛ فایل JAR را داخل Pod در حال اجرا mount یا inject نکنید. برای Kubernetes Deployment معمولی:

```bash
kubectl -n identity set image deployment/keycloak \
  keycloak=registry.example.com/keycloak-wsfed:26.7.0-1

kubectl -n identity rollout status deployment/keycloak --timeout=10m
kubectl -n identity get pods
```

Readiness/Liveness Probeهای موجود را حفظ کنید و Rolling Strategy باید در طول انتشار حداقل یک instance اعتبارسنجی‌شده را در دسترس نگه دارد. پس از rollout بررسی کنید تعداد restartهای Pod افزایش پیدا نکرده باشد.

در صورت استفاده از Keycloak Operator، custom image را در Custom Resource مربوط به Keycloak تنظیم کنید و نسخه Operator را با نسخه Keycloak داخل image یکسان نگه دارید:

```yaml
apiVersion: k8s.keycloak.org/v2beta1
kind: Keycloak
metadata:
  name: keycloak
spec:
  image: registry.example.com/keycloak-wsfed:26.7.0-1
  startOptimized: true
```

تنظیمات فعلی `instances`، hostname، TLS، دیتابیس، scheduling و secretها را حفظ کنید؛ نمونه بالا فقط فیلدهای مرتبط با custom image را نشان می‌دهد. راهنمای رسمی [Custom Image در Keycloak Operator](https://www.keycloak.org/operator/customizing-keycloak) جزئیات بیشتری دارد.

#### ۳. اعتبارسنجی پس از انتشار

تا قبل از موفقیت تمام موارد زیر، Relying Party محیط Production را تنظیم نکنید:

1. Readiness تمام Nodeها روی `/health/ready` در management port که معمولاً `9000` است سالم باشد.
2. در Startup Log هیچ خطای بارگذاری Provider، linkage error، `ClassNotFoundException` یا `NoSuchMethodError` وجود نداشته باشد.
3. گزینه `WS-Fed` در مسیر **Identity Providers → Add provider** دیده شود.
4. در Realm آزمایشی، مسیر `/realms/{realm}/protocol/wsfed/descriptor` پاسخ XML با HTTP 200 بدهد.
5. یک درخواست کنترل‌شده `wsignin1.0` وارد Login Flow شود و مقادیر مورد انتظار `wtrealm`، `wreply` و `wctx` حفظ شوند.
6. Smoke Testهای OIDC و SAML موجود، Login، Logout، اتصال دیتابیس، Cluster Discovery، Metricها و baseline حافظه/Startup سالم باقی بمانند.

فقط پس از این بررسی‌ها تنظیم Client یا Broker بخش بعد را اعمال کنید. کار را با یک Relying Party کم‌ریسک شروع و اعتبارسنجی امضا را فعال نگه دارید.

#### ۴. Rollback

در صورت شکست اعتبارسنجی، ترافیک را از instance معیوب خارج کنید. در Container یا Kubernetes، image tag تغییرناپذیر قبلی را دوباره deploy کنید. برای Deployment معمولی:

```bash
kubectl -n identity rollout undo deployment/keycloak
kubectl -n identity rollout status deployment/keycloak --timeout=10m
```

در نصب مستقیم، Keycloak را متوقف کنید، JAR جدید را حذف و در صورت وجود فایل `keycloak-wsfed.jar.before-upgrade` آن را بازیابی کنید. سپس `kc.sh build` را با build-time optionهای قبلی اجرا و سرویس را راه‌اندازی کنید. اگر تنظیمات Client یا Broker تغییر کرده‌اند، آن رکوردها یا backup دیتابیس/Realm را جداگانه برگردانید. حذف JAR تنظیمات ذخیره‌شده در دیتابیس را حذف نمی‌کند.

### پیکربندی

#### چه چیزی در Admin Console دیده می‌شود؟

افزونه دو حالت مستقل دارد:

| حالت | رفتار Admin Console |
|---|---|
| اتصال Keycloak به یک WS-Federation IdP خارجی | گزینه `WS-Fed` در مسیر **Identity Providers → Add provider** ظاهر می‌شود و فرم تنظیمات از propertyهای Provider ساخته می‌شود. |
| صدور توکن WS-Federation توسط Keycloak برای یک Relying Party | منوی اصلی جداگانه‌ای اضافه نمی‌شود. Wizard ساخت Client در Keycloak نقطه توسعه پشتیبانی‌شده‌ای برای این Login Protocol سفارشی ندارد. Client با `protocol=wsfed` را از طریق اسکریپت پروژه، Admin REST API یا Realm Import بسازید. Client پس از ساخت در Console قابل مشاهده است. |

اگر گزینه `WS-Fed` در **Identity Providers** دیده نمی‌شود، قرارگرفتن JAR در `providers/`، موفقیت `kc.sh build` و کپی‌شدن درخت بازسازی‌شده `/opt/keycloak` به image نهایی را بررسی کنید.

#### چرا نوع Client به‌جای `WS-Federation` مقدار `wsfed` نشان داده می‌شود؟

Admin Console برچسب پروتکل‌ها را از طریق یک `switch` ثابت در تابع `getProtocolName` تعیین می‌کند که تنها پروتکل‌های همراه خود Keycloak را پوشش می‌دهد و در حالت پیش‌فرض، شناسه خام Provider را برمی‌گرداند. هیچ SPI، Theme یا Message Bundle‌ای این رفتار را override نمی‌کند؛ بنابراین تغییر این برچسب تنها با تغییر خود شناسه Provider ممکن است. این شناسه هم‌زمان بخش مسیر `/realms/{realm}/protocol/wsfed` و مقدار `protocol` ذخیره‌شده روی هر Client و Realm Export است، و به همین دلیل عمداً `wsfed` باقی مانده تا سازگاری در سطح Wire و Realm Export حفظ شود.

دو محدودیت دیگر Console از همین نبود نقطه توسعه ناشی می‌شوند و رفتار مورد انتظار هستند، نه نقص: مرحله **Capability config** در Wizard برای Clientهای `wsfed` خالی رندر می‌شود، و به‌جز فهرست کشویی نوع Client، مدخل دیگری برای WS-Federation به رابط ساخت Client اضافه نمی‌شود. Clientهایی که از طریق Wizard، اسکریپت‌های پروژه، Admin REST API یا Realm Import ساخته شوند، همگی درست کار می‌کنند.

#### استفاده از Keycloak به‌عنوان ارائه‌دهنده WS-Federation

در این حالت یک برنامه قدیمی یا Relying Party مرورگر را به Keycloak هدایت می‌کند. Keycloak کاربر را احراز هویت کرده و توکن امضاشده را به Reply URL برنامه POST می‌کند.

سریع‌ترین روش قابل‌تکرار استفاده از ابزار idempotent پروژه است. برای محیط Compose از wrapper مربوط به `kcadm` استفاده کنید:

```bash
export KEYCLOAK_URL='http://localhost:8080'
export KEYCLOAK_ADMIN='admin'
export KEYCLOAK_ADMIN_PASSWORD='replace-this-password'
export WSFED_REALM='wsfed-demo'
export WSFED_CLIENT_ID='urn:example:wsfed:rp'
export WSFED_REPLY_URL='https://application.example.com/wsfed/callback'
export WSFED_TOKEN_FORMAT='SAML 2.0'
export WSFED_USE_JWT='false'
export WSFED_INCLUDE_X5T='false'
export KCADM="$PWD/scripts/kcadm-compose.sh"

./scripts/configure-client.sh
```

برای نصب مستقیم Keycloak، مسیر ابزار آن را مشخص کنید:

```bash
export KCADM='/opt/keycloak/bin/kcadm.sh'
./scripts/configure-client.sh
```

اسکریپت در صورت نبود Client آن را می‌سازد و در اجرای مجدد همان Client را update می‌کند. موارد زیر تنظیم می‌شوند:

- یک Client ID یکتا که Realm سامانه Relying Party را نمایش دهد؛ برای مثال `urn:example:wsfed:rp`؛
- مقدار `protocol=wsfed`؛
- Reply/Redirect URI دقیق و مورد اعتماد؛
- انتخاب توکن SAML 2.0، SAML 1.1 یا JWT؛ و
- افزودن اختیاری `x5t` برای سازگاری JWT.

| متغیر | اجباری | کاربرد |
|---|---|---|
| `KEYCLOAK_URL` | بله | آدرس عمومی Keycloak بدون `/` انتهایی |
| `KEYCLOAK_ADMIN` | بله | نام کاربری مدیریتی مورد استفاده `kcadm` |
| `KEYCLOAK_ADMIN_PASSWORD` | بله | رمز مدیر؛ برای automation از credential کوتاه‌عمر استفاده شود |
| `WSFED_REALM` | بله | Realm سرویس Keycloak که Client در آن قرار دارد |
| `WSFED_CLIENT_ID` | بله | دقیقاً همان مقداری که Relying Party در `wtrealm` می‌فرستد |
| `WSFED_REPLY_URL` | بله | callback دقیقی که پاسخ WS-Federation را دریافت می‌کند |
| `WSFED_TOKEN_FORMAT` | خیر | مقدار `SAML 2.0` پیش‌فرض یا `SAML 1.1` |
| `WSFED_USE_JWT` | خیر | با `true` به‌جای SAML، توکن JWT ارسال می‌شود؛ پیش‌فرض `false` |
| `WSFED_INCLUDE_X5T` | خیر | افزودن header مربوط به `x5t` در حالت JWT؛ پیش‌فرض `false` |
| `KCADM` | خیر | مسیر `kcadm.sh`؛ پیش‌فرض `/opt/keycloak/bin/kcadm.sh` |

Relying Party فرایند ورود را با درخواستی مشابه زیر آغاز می‌کند:

```text
GET https://keycloak.example.com/realms/production/protocol/wsfed
    ?wa=wsignin1.0
    &wtrealm=urn:example:wsfed:rp
    &wreply=https%3A%2F%2Fapplication.example.com%2Fwsfed%2Fcallback
    &wctx=opaque-application-state
```

Keycloak مقدار `wreply` را با Redirect URIهای Client تطبیق می‌دهد. پس از احراز هویت، یک فرم HTML خودکار شامل `wa`، `wresult` و `wctx` به Reply URL ارسال می‌شود. Relying Party باید امضای XML، issuer، audience، بازه اعتبار زمانی و سیاست جلوگیری از replay را بررسی کند.

Metadata مربوط به Realm:

```text
https://keycloak.example.com/realms/production/protocol/wsfed/descriptor
```

در محیط Production از Redirect URIهای wildcard و گسترده استفاده نکنید.

#### استفاده از Keycloak به‌عنوان Broker

در این حالت Keycloak کاربران را به یک WS-Federation IdP خارجی مانند AD FS هدایت می‌کند، پاسخ امضاشده را دریافت کرده و هویت خارجی را link یا import می‌کند.

روش تنظیم از Admin Console:

1. Realm مقصد را انتخاب کنید.
2. وارد **Identity Providers** شوید.
3. گزینه **Add provider → WS-Fed** را انتخاب کنید.
4. یک alias ثابت مانند `corporate-adfs` تعیین کنید.
5. فرم عمومی Identity Provider در Keycloak 26.7 برای همه Providerهای سفارشی دو فیلد **Client ID** و **Client Secret** را اجباری نمایش می‌دهد. WS-Federation از این دو مقدار استفاده نمی‌کند؛ در هر دو یک placeholder غیرحساس مانند `not-used-by-wsfed` وارد کنید و هیچ secret واقعی را آنجا قرار ندهید.
6. endpoint مربوط به Passive SSO، Realm خارجی و certificate امضا را وارد کنید.
7. گزینه **Validate signatures** را فعال نگه دارید.
8. تنظیمات را ذخیره و callback نمایش‌داده‌شده را در IdP خارجی ثبت کنید.

این فرم مستقیماً در Admin Console نسخه 26.7 بررسی شده است و فیلدهای **Alias**، **Display name**، دو فیلد عمومی و بدون استفاده Client، **Single sign-on service URL**، **Single logout service URL**، **WS-Federation realm**، **Signing certificate**، **Validate signatures**، **Back-channel logout** و **Empty action means logout** را نمایش می‌دهد. برای automation استفاده از اسکریپت پروژه پیشنهاد می‌شود، چون فقط تنظیماتی را ثبت می‌کند که Provider واقعاً مصرف می‌کند.

همین تنظیمات را می‌توان با اسکریپت پروژه به‌صورت تکرارپذیر اعمال کرد:

```bash
export KEYCLOAK_URL='https://keycloak.example.com'
export KEYCLOAK_ADMIN='admin'
export KEYCLOAK_ADMIN_PASSWORD='replace-this-password'
export WSFED_REALM='production'
export WSFED_BROKER_ALIAS='corporate-adfs'
export WSFED_SSO_URL='https://adfs.example.com/adfs/ls/'
export WSFED_SLO_URL='https://adfs.example.com/adfs/ls/'
export WSFED_ISSUER_REALM='urn:keycloak:production'
export WSFED_SIGNING_CERTIFICATE_FILE='/secure/adfs-signing.pem'
export WSFED_VALIDATE_SIGNATURE='true'
export WSFED_BACKCHANNEL_LOGOUT='false'
export KCADM='/opt/keycloak/bin/kcadm.sh'

./scripts/configure-broker.sh
```

| فیلد یا متغیر | کاربرد |
|---|---|
| Alias یا `WSFED_BROKER_ALIAS` | شناسه ثابت Keycloak که در مسیر callback قرار می‌گیرد |
| Single sign-on URL یا `WSFED_SSO_URL` | endpoint مربوط به Passive Requestor در سرویس خارجی |
| Single logout URL یا `WSFED_SLO_URL` | endpoint اختیاری Logout در سرویس خارجی |
| WS-Federation realm یا `WSFED_ISSUER_REALM` | مقدار `wtrealm` که Keycloak برای سرویس خارجی ارسال می‌کند |
| Signing certificate | certificate عمومی امضای Assertion به‌شکل PEM یا Base64 |
| Validate signatures | مقدار امن پیش‌فرض `true`؛ Assertion بدون امضای معتبر رد می‌شود |
| Back-channel logout | در صورت فعال‌بودن، endpoint خروج از سمت سرور فراخوانی می‌شود |
| Empty action means logout | حالت سازگاری برای Providerهایی که در پاسخ Logout مقدار `wa` ارسال نمی‌کنند |

این callback را در IdP خارجی ثبت کنید:

```text
https://keycloak.example.com/realms/production/broker/corporate-adfs/endpoint
```

پس از ذخیره، Provider می‌تواند در صفحه Login مربوط به Realm ظاهر شود. برای تبدیل attributeهای Assertion خارجی به attribute یا role داخلی Keycloak از تب **Mappers** همان Identity Provider استفاده کنید.

بررسی امضا در محیط Production باید فعال باقی بماند.

#### فرمت و تعویض Certificate

Broker یک certificate به‌شکل PEM یا بدنه Base64 آن را می‌پذیرد. فقط certificate عمومی امضا را وارد کنید و هرگز private key را در این تنظیم قرار ندهید. هنگام تعویض certificate در IdP بالادستی، پیش از انقضای کلید قبلی تنظیمات Keycloak را به‌روزرسانی کنید. Import خودکار metadata هنوز پیاده‌سازی نشده و تعویض certificate فعلاً یک عملیات مدیریتی صریح است.

#### عیب‌یابی

| نشانه | مورد قابل بررسی |
|---|---|
| گزینه `WS-Fed` در Add provider دیده نمی‌شود | مسیر JAR، خروجی `kc.sh build`، کپی‌شدن `/opt/keycloak` به image نهایی و یکسان‌بودن minor version افزونه و Keycloak |
| خطای `unknown login requester` | مقدار `wtrealm` باید دقیقاً برابر `clientId` مربوط به Client پروتکل WS-Fed باشد |
| خطای `invalid redirect uri` | مقدار `wreply` باید دقیقاً با Redirect URI ثبت‌شده مطابقت داشته باشد |
| خطای امضای پاسخ Broker | certificate عمومی درست، تعویض certificate، الگوریتم XML Signature و دست‌نخورده‌بودن response body |
| حلقه Login پس از بازگشت از IdP | callback مربوط به Broker، Cookie/HTTPS، proxy headers، hostname عمومی و First Broker Login Flow |
| کارکرد با `start-dev` و شکست با `start --optimized` | پس از کپی JAR دوباره `kc.sh build` اجرا و image نهایی بازسازی شود |
| Startup کند در Production | baseline کاملاً مشابه، migration دیتابیس، cache، DNS/TLS، storage و محدودیت JVM بررسی شود؛ زمان build اولیه با startup بهینه مقایسه نشود |

### Endpointها

| کاربرد | Endpoint |
|---|---|
| endpoint اصلی پروتکل | `/realms/{realm}/protocol/wsfed` |
| metadata مربوط به IdP / STS | `/realms/{realm}/protocol/wsfed/descriptor` |
| callback مربوط به Broker | `/realms/{realm}/broker/{alias}/endpoint` |

شناسه پروتکل برای سازگاری ارتباطی و Realm Exportها همچنان `wsfed` باقی مانده است.

### تنظیمات امنیتی پیش‌فرض

- اعتبارسنجی امضای Assertion به‌صورت پیش‌فرض فعال است.
- XML External Entity، فایل‌های DTD و Schemaهای خارجی غیرفعال هستند.
- اندازه body پاسخ Broker به 1 MiB محدود شده است.
- مقدار context حداکثر 8 KiB است.
- ویژگی‌های فرم HTML خروجی escape می‌شوند.
- فقط روش‌های پاسخ GET/POST پشتیبانی‌شده پذیرفته می‌شوند.
- Redirect خروج باید متعلق به یک Client مشخص و مطابق Redirect URI ثبت‌شده آن باشد.
- Mapper قدیمی مبتنی بر JavaScript ثبت نمی‌شود.

فایل‌های Provider با سطح دسترسی پردازش Keycloak اجرا می‌شوند و باید کد مورد اعتماد محسوب شوند. تغییرات پیکربندی را بازبینی کنید و برای دور زدن مشکل Integration، اعتبارسنجی امضا را غیرفعال نکنید.

### نتایج تست تأییدشده

تست‌های زیر در تاریخ 2026-07-18 با image رسمی `quay.io/keycloak/keycloak:26.7.0`، نسخه Docker Engine 29.4.0 و کانتینر Linux/ARM64 روی میزبان ARM64 انجام شده‌اند.

#### تست‌های Build و Runtime

| تست | نتیجه |
|---|---|
| Clean Build با Maven | موفق |
| تست‌های واحد | 5 موفق، بدون Failure، Error یا Skip |
| نصب Provider با `kc.sh build` | موفق |
| مشاهده Factoryهای WS-Federation در Server Info | موفق |
| ساخت Realm آزمایشی | موفق |
| ساخت Client با `protocol=wsfed` | موفق |
| endpoint مربوط به Metadata | HTTP 200 با `application/xml` |
| درخواست استاندارد `wsignin1.0` | نمایش صفحه ورود با HTTP 200 و ساخت Authentication Session |
| خطای Runtime از نوع `ERROR`، `FATAL` یا Exception | مشاهده نشد |

حجم فایل JAR آزمایش‌شده نسخه Release برابر `26.7.0-1` معادل 671 KiB و SHA-256 آن به‌شکل زیر بود:

```text
9b5b1e4aea591f7b067f2f6a7610aaa0edc3c9736cd5717e69faed4086a9cbc8
```

#### اندازه‌گیری Startup و Metadata

هر دو image با `kc.sh build` آماده شدند و با دستور یکسان در حالت Production و optimized اجرا شدند. زمان readiness با درخواست دوره‌ای به OpenID Configuration مربوط به Realm اصلی اندازه‌گیری شد.

| معیار | Keycloak خام | Keycloak دارای افزونه |
|---|---:|---:|
| زمان Startup گزارش‌شده توسط Keycloak | 9.281 ثانیه | 8.320 ثانیه |
| زمان خارجی رسیدن به Readiness | 9.727 ثانیه | 8.774 ثانیه |

نتایج 50 درخواست متوالی به endpoint مربوط به WS-Federation metadata:

| تعداد درخواست | میانگین | کمینه | بیشینه |
|---:|---:|---:|---:|
| 50 | 3.759 ms | 2.750 ms | 6.782 ms |

کانتینر آزمایشی دارای افزونه در لحظه نمونه‌برداری تقریباً 668.6 MiB حافظه، 69 پردازش/Thread طبق گزارش Docker و 1.14 درصد CPU مصرف می‌کرد.

> [!NOTE]
> این اعداد مشاهدات یک Smoke Test محلی در یک محیط Docker مبتنی بر ARM64 هستند و تضمین آماری کارایی محسوب نمی‌شوند. سریع‌تر بودن Startup نسخه دارای افزونه در این نمونه باید به‌عنوان نوسان بین اجراها در نظر گرفته شود. نتیجه قابل اتکا این است که در این محیط، کندی شدید Startup بازتولید نشد. Benchmark محیط Production باید با چندین اجرای تکرارشونده، منابع کنترل‌شده، دیتابیس و cache واقعی، Realmهای نزدیک به Production و ترافیک نماینده انجام شود.

### محدودیت‌های اعتبارسنجی فعلی

Smoke Test فعلی، شناسایی Provider، Startup سرویس Keycloak، پیکربندی Realm و Client، تولید metadata و ورود به جریان Login مربوط به WS-Federation را تأیید می‌کند. هنوز چرخه کامل تبادل توکن با یک IdP یا Relying Party مستقل، شامل دریافت Assertion امضاشده و سازگاری Logout، به‌صورت خودکار اجرا نشده است. بنابراین نتایج فعلی نباید به‌عنوان گواهی کامل سازگاری با تمام سرویس‌های ثالث تلقی شوند.

در CI اسکریپت `scripts/integration-test.sh` نیز اجرا می‌شود؛ این تست هر دو ابزار پیکربندی را دو بار اجرا کرده و رکوردهای نهایی Client و Broker را بررسی می‌کند. به این ترتیب regression مسیر create/update شناسایی می‌شود، بدون اینکه ادعای سازگاری کامل با IdP خارجی مطرح شود.

### مجوز و Attribution

مجوز انتشار در سطح مخزن GNU Affero General Public License 3.0 است. پروژه شامل کدهای سازگارشده از پیاده‌سازی‌های متن‌باز قدیمی WS-Federation است و فایل‌ها اعلان‌های copyright و مجوز اصلی مرتبط، از جمله Apache-2.0 و LGPL، را حفظ می‌کنند. پیش از بازتوزیع، فایل [NOTICE](NOTICE) و header فایل‌های مربوط را بررسی کنید؛ مجوز سطح مخزن، الزام‌های attribution یا مجوزهای درج‌شده در فایل‌ها را حذف نمی‌کند.

جزئیات مجوز و attribution در فایل‌های [LICENSE](LICENSE) و [NOTICE](NOTICE) قرار دارد. namespace نگه‌داری‌شده Java برابر `io.github.chosomeister.keycloak` است.
