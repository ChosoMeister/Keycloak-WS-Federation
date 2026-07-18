ARG KEYCLOAK_VERSION=26.7.0

FROM maven:3.9.11-eclipse-temurin-21 AS extension-builder
WORKDIR /workspace
COPY pom.xml ./
COPY keycloak-wsfed/pom.xml keycloak-wsfed/pom.xml
COPY keycloak-wsfed/src keycloak-wsfed/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean verify

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS keycloak-builder
ENV KC_HEALTH_ENABLED=true
COPY --from=extension-builder \
    /workspace/keycloak-wsfed/target/keycloak-wsfed-26.7.0-SNAPSHOT.jar \
    /opt/keycloak/providers/keycloak-wsfed.jar
RUN /opt/keycloak/bin/kc.sh build --db=postgres

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=keycloak-builder /opt/keycloak/ /opt/keycloak/
ENV KC_HEALTH_ENABLED=true
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
