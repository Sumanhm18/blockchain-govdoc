ARG BASE_IMAGE
FROM ${BASE_IMAGE}
USER root
# Jammy security fixes; retain Fabric binaries, entrypoint and persistent-data format.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libssl3=3.0.2-0ubuntu1.30 gpgv=2.2.27-3ubuntu2.5 \
    && rm -rf /var/lib/apt/lists/*
