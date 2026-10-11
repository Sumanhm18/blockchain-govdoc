ARG POSTGRES_IMAGE
FROM ${POSTGRES_IMAGE}
# Start directly as the upstream Alpine postgres account; no root-to-user helper is needed.
USER root
RUN rm /usr/local/bin/gosu && chown 70:70 /var/lib/postgresql/data /var/run/postgresql
USER 70:70
