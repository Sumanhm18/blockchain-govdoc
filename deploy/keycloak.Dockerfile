ARG KEYCLOAK_IMAGE
FROM ${KEYCLOAK_IMAGE}
RUN /opt/keycloak/bin/kc.sh build --db=postgres --health-enabled=true --metrics-enabled=true
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
