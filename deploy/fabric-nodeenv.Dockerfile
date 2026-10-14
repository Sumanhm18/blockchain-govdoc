# Preserve the upstream Fabric build/start contract; lock npm's patched dependency graph.
ARG FABRIC_NODEENV
ARG NODE_IMAGE
FROM ${FABRIC_NODEENV} AS upstream
FROM ${NODE_IMAGE} AS npm-build
ARG NPM_VERSION
RUN npm install --global "npm@${NPM_VERSION}" \
    && cp -a /usr/local/lib/node_modules/npm /opt/npm-patched
COPY npm-runtime/package.json npm-runtime/package-lock.json /opt/npm-patched/
RUN node -e 'if(require("/opt/npm-patched/package.json").version !== process.argv[1]) process.exit(1)' "$NPM_VERSION" \
    && npm ci --prefix /opt/npm-patched --omit=dev --ignore-scripts --no-audit --allow-remote=all
FROM ${NODE_IMAGE}
RUN rm -rf /usr/local/lib/node_modules/npm
COPY --from=npm-build /opt/npm-patched /usr/local/lib/node_modules/npm
RUN npm --version && apk add --no-cache make python3 g++ \
    && mkdir -p /chaincode/input /chaincode/output /usr/local/src
COPY --from=upstream /chaincode/build.sh /chaincode/start.sh /chaincode/
