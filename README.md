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
keycloak-wsfed/target/keycloak-wsfed-26.7.0-SNAPSHOT.jar
```

### Installation

Copy the provider JAR into the Quarkus provider directory and rebuild Keycloak:

```bash
cp keycloak-wsfed/target/keycloak-wsfed-26.7.0-SNAPSHOT.jar \
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

### Configuration

#### What appears in the Admin Console?

The extension has two distinct modes:

| Mode | Admin Console behavior |
|---|---|
| Keycloak brokers to an external WS-Federation IdP | `WS-Fed` appears under **Identity Providers → Add provider**. Its configuration form is generated from the provider properties. |
| Keycloak issues WS-Federation tokens to a relying party | No separate top-level menu is added. Keycloak's client-creation wizard does not provide a supported UI extension point for this custom login protocol. Create the `protocol=wsfed` client with the supplied script, Admin REST API, or realm import. The client is visible after creation. |

If `WS-Fed` is missing from **Identity Providers**, verify that the JAR is in `providers/`, that `kc.sh build` completed, and that the running image contains the rebuilt `/opt/keycloak` tree.

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

The tested snapshot JAR was 671 KiB and had this SHA-256 digest:

```text
a34d1156fffded042484a45e6164b9b500382503b262e94d4a0a8670e9776de5
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

This maintained implementation is distributed primarily under Apache License 2.0. It contains adapted code from earlier open-source WS-Federation implementations, and individual files may retain their original copyright and license headers, including LGPL-2.1-or-later material. Those file-level terms are not replaced by the repository's primary license.

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
keycloak-wsfed/target/keycloak-wsfed-26.7.0-SNAPSHOT.jar
```

### نصب

فایل JAR را داخل پوشه Providerهای توزیع Quarkus کپی و Keycloak را مجدداً Build کنید:

```bash
cp keycloak-wsfed/target/keycloak-wsfed-26.7.0-SNAPSHOT.jar \
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

### پیکربندی

#### چه چیزی در Admin Console دیده می‌شود؟

افزونه دو حالت مستقل دارد:

| حالت | رفتار Admin Console |
|---|---|
| اتصال Keycloak به یک WS-Federation IdP خارجی | گزینه `WS-Fed` در مسیر **Identity Providers → Add provider** ظاهر می‌شود و فرم تنظیمات از propertyهای Provider ساخته می‌شود. |
| صدور توکن WS-Federation توسط Keycloak برای یک Relying Party | منوی اصلی جداگانه‌ای اضافه نمی‌شود. Wizard ساخت Client در Keycloak نقطه توسعه پشتیبانی‌شده‌ای برای این Login Protocol سفارشی ندارد. Client با `protocol=wsfed` را از طریق اسکریپت پروژه، Admin REST API یا Realm Import بسازید. Client پس از ساخت در Console قابل مشاهده است. |

اگر گزینه `WS-Fed` در **Identity Providers** دیده نمی‌شود، قرارگرفتن JAR در `providers/`، موفقیت `kc.sh build` و کپی‌شدن درخت بازسازی‌شده `/opt/keycloak` به image نهایی را بررسی کنید.

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

حجم فایل JAR آزمایش‌شده 671 KiB و SHA-256 آن به‌شکل زیر بود:

```text
a34d1156fffded042484a45e6164b9b500382503b262e94d4a0a8670e9776de5
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

این پیاده‌سازی نگه‌داری‌شده عمدتاً تحت Apache License 2.0 منتشر می‌شود. پروژه شامل کدهای سازگارشده از پیاده‌سازی‌های متن‌باز قدیمی WS-Federation است و بعضی فایل‌ها هدر copyright و مجوز اصلی خود، از جمله LGPL-2.1-or-later، را حفظ می‌کنند. مجوز اصلی مخزن جایگزین شرایط اختصاصی درج‌شده در آن فایل‌ها نمی‌شود.

جزئیات مجوز و attribution در فایل‌های [LICENSE](LICENSE) و [NOTICE](NOTICE) قرار دارد. namespace نگه‌داری‌شده Java برابر `io.github.chosomeister.keycloak` است.
