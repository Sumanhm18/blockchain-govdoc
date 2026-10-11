# Priority runtime remediation — 11 October 2026

The Fabric Node runtime was rebuilt on digest-pinned Node 22.23.3 / Alpine 3.24.2. It retains `build.sh` and `start.sh` from the pinned Fabric 2.5.8-compatible upstream image, together with the compiler/Python tools needed to install chaincode. Its source base pins are in `config/node-runtime.json`.

The peer, orderer and CA retain their existing Fabric binaries and data formats. Derived images install the Jammy security fixes `libssl3=3.0.2-0ubuntu1.30` and `gpgv=2.2.27-3ubuntu2.5`. A newer official Fabric peer was evaluated, but its base still had high OpenSSL findings; changing its version alone would not close this issue. A separate Fabric binary/library upgrade remains appropriate after full scans.

## Measured OS scan results

These are Trivy package findings, not counts of distinct exploitable vulnerabilities.

| Runtime | Before critical / high | After critical / high | Remaining medium / low |
|---|---:|---:|---:|
| Fabric Node | 2 / 48 | 0 / 0 | 1 / 0 |
| Fabric peer | 0 / 3 | 0 / 0 | 155 / 46 |
| Fabric orderer | 0 / 3 | 0 / 0 | 155 / 46 |
| Fabric CA | 0 / 3 | 0 / 0 | 155 / 46 |

All four patched runtime images were scanned. These results do not cover all language/binary vulnerabilities, PostgreSQL, Keycloak or external deployments. The full release gate remains blocked; no findings were suppressed to obtain these results.

## Applying and verifying

`./deploy.sh` now builds these images before preparing the local network. Build outputs receive local tags derived from their complete image IDs; restricted `.runtime/node-runtime.json` and `.runtime/fabric-images.json` record the exact contents. Preparation checks image IDs before using their references. The security runner scans these derived images, rather than the unused upstream bases. Node build-tool packages are resolved from the pinned base's Alpine repository at build time, so capture and review each resulting image ID; this is not a bit-for-bit reproducible package snapshot or a published multi-architecture release image.

Existing generated network configuration is updated without regenerating CA credentials, MSPs, ledgers or volumes. Startup explicitly reconciles CA containers, because the upstream sample otherwise skips them once organizations exist. The default chaincode lifecycle version includes the Node runtime image hash, forcing a new chaincode package/build after a runtime change instead of reusing a vulnerable cached image. If overriding `CC_VERSION`, operators must choose a new package/version when changing runtimes.

`python3 scripts/verify-runtime-images.py` checks the actual running image IDs of the two peers, orderer and three CAs. It also compares the root filesystem layer prefix of both running registry chaincode images against the patched Node runtime. `test_demo.sh` includes this check.

## Validation and limits

The patched chaincode deployed successfully, with 16 live API/IPFS/Fabric checks and two direct Fabric authorization checks passing. The first immediate upload during peer reconnection returned durable HTTP 202; the steady-state run passed after readiness recovered. Disposable certificate revocation through a channel MSP CRL update, missing-endorser recovery, IPFS outage/API restart recovery and all 37 HTTPS/OIDC checks passed against the patched network. The encrypted application/IPFS backup and isolated restore drill also passed; it does not restore the Fabric ledger. No deployment outside the isolated local development network was changed.

PostgreSQL's critical finding, Keycloak's high findings, conditional Spring findings and incomplete full binary/library scans still block a production release. The account billing lock still prevents hosted CI. Do not interpret these OS fixes as production approval.
