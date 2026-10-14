ARG KEYCLOAK_IMAGE
ARG KEYCLOAK_RUNTIME_IMAGE
FROM ${KEYCLOAK_IMAGE} AS build
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true
FROM ${KEYCLOAK_RUNTIME_IMAGE}
RUN apk add --no-cache bash && adduser -D -u 1000 -G root keycloak
COPY --from=build --chown=1000:0 /opt/keycloak /opt/keycloak
WORKDIR /opt/keycloak
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
