# Priority runtime remediation — 12 October 2026

The Fabric Node runtime was rebuilt on digest-pinned Node 22.23.3 / Alpine 3.24.2. It retains `build.sh` and `start.sh` from the pinned Fabric 2.5.8-compatible upstream image, together with the compiler/Python tools needed to install chaincode. npm is pinned to 12.2.0 with a separately locked, patched dependency graph; the full runtime scan now reports only two medium findings. See `deploy/npm-runtime/README.md`. Its source base pins are in `config/node-runtime.json`.

The peer/orderer and build environment now use Fabric 2.5.16; the CA uses 1.5.22. Derived images also install the Jammy security fixes `libssl3=3.0.2-0ubuntu1.30` and `gpgv=2.2.27-3ubuntu2.5`. The local network was upgraded after an encrypted cold snapshot, retaining its identities, ledgers and volumes. `scripts/backup-fabric-runtime.py` creates that restricted snapshot and restarts the services it stopped; creating the snapshot is not a Fabric ledger restore drill.

The IPFS image starts directly as UID 1000/GID 100 and removes the obsolete Go-built `/sbin/gosu` privilege-switching helper. Existing data remains in its original volume. A fresh isolated volume also passed non-root initialization. After helper removal, the earlier full scan reported zero critical and 13 high findings, versus three critical and 45 high before it. The subsequent [source-pinned IPFS rebuild](IPFS_RUNTIME.md) on October 12 removes those 13 high findings; one unknown-severity x/crypto advisory remains. The rebuilt image passed isolated encrypted restore and live checks before being retained on the existing volume.
## Earlier OS remediation baseline

These are Trivy package findings, not counts of distinct exploitable vulnerabilities.

| Runtime | Before critical / high | After critical / high | Remaining medium / low |
|---|---:|---:|---:|
| Fabric Node | 2 / 48 | 0 / 0 | 1 / 0 |
| Fabric peer | 0 / 3 | 0 / 0 | 155 / 46 |
| Fabric orderer | 0 / 3 | 0 / 0 | 155 / 46 |
| Fabric CA | 0 / 3 | 0 / 0 | 155 / 46 |

These earlier OS-only measurements describe the initial 2.5.12/1.5.15 remediation. Current full image results, including the later binary upgrades, are recorded in [Security gate](SECURITY_GATE.md). No advisory exclusions are used.

## Applying and verifying

`./deploy.sh` now builds these images before preparing the local network. Build outputs receive local tags derived from their complete image IDs; restricted `.runtime/node-runtime.json` and `.runtime/fabric-images.json` record the exact contents. Preparation checks image IDs before using their references. The security runner scans these derived images, rather than the unused upstream bases. Node build-tool packages are resolved from the pinned base's Alpine repository at build time, so capture and review each resulting image ID; this is not a bit-for-bit reproducible package snapshot or a published multi-architecture release image.

Existing generated network configuration is updated without regenerating CA credentials, MSPs, ledgers or volumes. Startup explicitly reconciles CA containers, because the upstream sample otherwise skips them once organizations exist. The default chaincode lifecycle version includes the Node runtime image hash, forcing a new chaincode package/build after a runtime change instead of reusing a vulnerable cached image. If overriding `CC_VERSION`, operators must choose a new package/version when changing runtimes.

`python3 scripts/verify-runtime-images.py` checks the actual running image IDs of the two peers, orderer and three CAs. It also compares the root filesystem layer prefix of both running registry chaincode images against the patched Node runtime. It also verifies the deployed IPFS image and non-root user. `test_demo.sh` includes these checks.

## Validation and limits

The patched chaincode deployed successfully, with 16 live API/IPFS/Fabric checks and two direct Fabric authorization checks passing. The first immediate upload during peer reconnection returned durable HTTP 202; the steady-state run passed after readiness recovered. Disposable certificate revocation through a channel MSP CRL update, missing-endorser recovery, IPFS outage/API restart recovery and all 37 HTTPS/OIDC checks passed against the patched network. The encrypted application/IPFS backup and isolated restore drill also passed; it does not restore the Fabric ledger. No deployment outside the isolated local development network was changed.

The [identity runtime migration](IDENTITY_MIGRATION.md) addresses PostgreSQL and Keycloak OS findings. The API has moved to Spring Boot 4 with patched Jackson and Tomcat versions. Residual full-scan findings and the account billing lock still block release; see [Security gate](SECURITY_GATE.md).
