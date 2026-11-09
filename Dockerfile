# Auth service for the CBA platform, built on the official Keycloak image.
# Keycloak's own code is used unchanged; this image adds our build options,
# our login theme and our plugins.

ARG KEYCLOAK_VERSION=26.8.1

# Our auth-service plugins, built against the same Keycloak version.
FROM maven:3.9.9-eclipse-temurin-21 AS providers
WORKDIR /build
COPY providers/cba-core-user-migration/pom.xml cba-core-user-migration/pom.xml
RUN mvn -q -B -f cba-core-user-migration/pom.xml dependency:go-offline
COPY providers/cba-core-user-migration/src cba-core-user-migration/src
RUN mvn -q -B -f cba-core-user-migration/pom.xml package

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder

# Build-time options. They are fixed into the image by `kc.sh build`.
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true

WORKDIR /opt/keycloak

# Our login theme and plugins.
COPY themes/ /opt/keycloak/themes/
COPY --from=providers /build/cba-core-user-migration/target/cba-core-user-migration.jar /opt/keycloak/providers/

RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}

COPY --from=builder /opt/keycloak/ /opt/keycloak/

# 8080: HTTP (TLS ends at the load balancer). 9000: health and metrics.
EXPOSE 8080 9000

ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized"]
