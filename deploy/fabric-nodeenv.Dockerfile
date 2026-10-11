# Preserve the pinned upstream Fabric launch/build contract on a patched Node 22 base.
ARG FABRIC_NODEENV
ARG NODE_IMAGE
FROM ${FABRIC_NODEENV} AS upstream
FROM ${NODE_IMAGE}
ARG NPM_VERSION
RUN npm install --global "npm@${NPM_VERSION}" && npm cache clean --force
RUN apk add --no-cache make python3 g++ \
    && mkdir -p /chaincode/input /chaincode/output /usr/local/src
COPY --from=upstream /chaincode/build.sh /chaincode/start.sh /chaincode/
