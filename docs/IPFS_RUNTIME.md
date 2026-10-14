# IPFS runtime dependency rebuild

Kubo stays on version 0.43.1 and its existing repository format. The runtime is rebuilt from upstream commit `acc53c093810a8fe9a4bc2f023f0b1e0c1d629b5`, with Go 1.26.9, `golang.org/x/net` 0.60.0 and gRPC-Go 1.83.2. These address the high findings recorded against the original binary's older Go toolchain and dependencies. The exact module graph and checksums are committed under `deploy/ipfs-runtime/`; `config/ipfs-runtime.json` records the source archive checksum and digest-pinned builder.

The Docker build verifies the source archive SHA-256 before extraction, downloads the locked modules, verifies their checksums and runs the upstream `repo/fsrepo` and `core/coreunix` tests. It builds a static binary and identifies its commit/origin as a local derivative. The final image retains the pinned upstream startup scripts, runs as UID 1000/GID 100 and removes the obsolete gosu helper. This is a maintained local rebuild, not an unmodified vendor binary or a claim that all optional upstream features have been validated.

## Build and inspect

`deploy.sh` calls `scripts/build-ipfs-image.py` through bootstrap. The security workflow uses the same script. It builds without restarting services and writes the exact resulting image ID and source revision into ignored `.runtime/ipfs-image.json`. Compose uses the already-built `govdoc-ipfs:local` image. This adds a Go source build to initial setup; prepare the demonstration computer before submission day and use `scripts/resume-demo.py` for rehearsals.

To build manually after sourcing `scripts/docker-env.sh` with the repository root set in `ROOT_DIR`:

```sh
python3 scripts/build-ipfs-image.py
```

Keep a tagged copy of the previous image before changing the live service. Building does not migrate, wipe or replace the data volume. Runtime changes still require explicit deployment and validation; a successful build is not a successful rollout.

## Restore compatibility before rollout

The backup/restore drill accepts a locally built candidate image:

```sh
.runtime/tools/bin/python scripts/test-backup-restore.py --ipfs-image govdoc-ipfs:build
```

It snapshots the existing owned application/IPFS state, restarts the original services, and uses the candidate only for an isolated restored IPFS container. It validates backup tamper rejection, file checksums and exact authorized document download through a restored API. The candidate never mounts the live IPFS volume. The Python environment needs `config/operations-tools-requirements.txt`.

A candidate built from these source/module pins passed that restore check and the two upstream test packages. Full scans and final live checks are recorded in [Security gate](SECURITY_GATE.md) and [Validation](../TESTING_SUMMARY.md). No full upstream test-suite, FUSE mounting, optional plugin, production load or production resilience claim is made.

## October 12 deployment result

The final image passed isolated encrypted restore, then replaced only the owned IPFS service on its existing volume. The 16 API checks, two direct Fabric authorization checks, 14 presentation checks, 37 HTTPS/OIDC checks and IPFS outage/API restart recovery passed. The prior image remains locally tagged `govdoc-ipfs-rollback:pre-source-rebuild`; its image ID is retained in restricted `.runtime/ipfs-before-rebuild.json`.

All nine full security scans completed. IPFS now reports zero critical/high findings and one unknown-severity advisory, GO-2026-5932 in x/crypto, with no listed fixed version. The same advisory appears in the source lock scan. Neither finding is suppressed; the release gate remains blocked.
