ARG IPFS_IMAGE
FROM ${IPFS_IMAGE}
# The daemon starts unprivileged; the old Go-built root-to-user helper is unnecessary.
USER root
RUN rm /sbin/gosu && chown 1000:100 /data/ipfs
USER 1000:100
