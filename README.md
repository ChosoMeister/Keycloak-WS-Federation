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

#### Keycloak as a WS-Federation Identity Provider

Create a client and select `wsfed` as its client protocol. At minimum, configure:

- a unique client ID representing the relying-party realm, for example `urn:example:wsfed:rp`;
- exact, trusted redirect URIs for the relying party;
- the required assertion and signature settings; and
- any user, property, group, or role protocol mappers required by the relying party.

Do not use broad wildcard redirect URIs in production.

#### Keycloak as a WS-Federation broker

Create a new Identity Provider and select WS-Federation. Configure the external IdP/STS realm, endpoint, certificate/signature policy, and mapper settings according to the upstream provider.

Signature validation should remain enabled in production.

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

#### استفاده از Keycloak به‌عنوان ارائه‌دهنده WS-Federation

یک Client بسازید و پروتکل آن را روی `wsfed` قرار دهید. حداقل موارد زیر باید تنظیم شوند:

- یک Client ID یکتا که Realm سامانه Relying Party را نمایش دهد؛ برای مثال `urn:example:wsfed:rp`؛
- Redirect URIهای دقیق و مورد اعتماد؛
- تنظیمات لازم Assertion و امضا؛ و
- Mapperهای مورد نیاز برای ویژگی کاربر، مشخصات کاربر، گروه یا نقش.

در محیط Production از Redirect URIهای wildcard و گسترده استفاده نکنید.

#### استفاده از Keycloak به‌عنوان Broker

یک Identity Provider جدید از نوع WS-Federation ایجاد کنید. سپس Realm و endpoint سرویس IdP/STS خارجی، سیاست بررسی certificate و امضا، و Mapperهای لازم را مطابق ارائه‌دهنده بالادستی تنظیم کنید.

بررسی امضا در محیط Production باید فعال باقی بماند.

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

### مجوز و Attribution

این پیاده‌سازی نگه‌داری‌شده عمدتاً تحت Apache License 2.0 منتشر می‌شود. پروژه شامل کدهای سازگارشده از پیاده‌سازی‌های متن‌باز قدیمی WS-Federation است و بعضی فایل‌ها هدر copyright و مجوز اصلی خود، از جمله LGPL-2.1-or-later، را حفظ می‌کنند. مجوز اصلی مخزن جایگزین شرایط اختصاصی درج‌شده در آن فایل‌ها نمی‌شود.

جزئیات مجوز و attribution در فایل‌های [LICENSE](LICENSE) و [NOTICE](NOTICE) قرار دارد. namespace نگه‌داری‌شده Java برابر `io.github.chosomeister.keycloak` است.
