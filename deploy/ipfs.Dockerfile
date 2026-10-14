ARG IPFS_IMAGE
ARG GO_IMAGE
FROM ${GO_IMAGE} AS build
ARG KUBO_COMMIT
ARG KUBO_SOURCE_SHA256
ADD --checksum=sha256:${KUBO_SOURCE_SHA256} https://codeload.github.com/ipfs/kubo/tar.gz/${KUBO_COMMIT} /tmp/source.tar.gz
RUN mkdir /src && tar -xzf /tmp/source.tar.gz --strip-components=1 -C /src
WORKDIR /src
COPY ipfs-runtime/go.mod ipfs-runtime/go.sum ./
ENV GOTOOLCHAIN=local CGO_ENABLED=0 GOMAXPROCS=2
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    sha256sum go.mod go.sum > /tmp/module-lock.sha256 \
    && go test -mod=readonly ./repo/fsrepo ./core/coreunix \
    && go build -mod=readonly -trimpath -buildvcs=false \
       -ldflags="-X github.com/ipfs/kubo.CurrentCommit=${KUBO_COMMIT}-govdoc -X github.com/ipfs/kubo.buildOrigin=github.com/Sumanhm18/blockchain-govdoc" \
       -o /out/ipfs ./cmd/ipfs \
    && go mod verify && sha256sum -c /tmp/module-lock.sha256
FROM ${IPFS_IMAGE}
USER root
RUN rm /sbin/gosu && chown 1000:100 /data/ipfs
COPY --from=build /out/ipfs /usr/local/bin/ipfs
USER 1000:100
