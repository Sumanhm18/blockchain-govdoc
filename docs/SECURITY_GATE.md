# Reproducible security release gate

Run from the repository root:

```sh
python3 scripts/install-trivy.py
./scripts/security-scan.sh --build
```

The installer downloads Trivy 0.75.0 from its official GitHub release and checks the archive against the platform-specific SHA-256 committed in `config/trivy.json`. It extracts only the scanner binary into ignored `.runtime/tools`. Updating the scanner requires reviewing its release and changing the committed version/checksums together.

The runner builds the API, optimized Keycloak, non-root PostgreSQL/IPFS and patched Fabric images without starting services or generating credentials. It resolves local image tags to immutable content IDs, then scans the application source, API, Keycloak, PostgreSQL, Fabric peer/orderer/CA/Node runtime and IPFS. Third-party references come from the committed image pins. Full mode includes OS and supported language dependencies; Trivy may download its large Java index. Build and scanner subprocesses have timeouts.

Each run creates a new restricted `.runtime/security/<timestamp>-<random>/` directory containing raw JSON inventory/findings, protected diagnostic logs and `summary.json`. Existing reports cannot be mistaken for a fresh successful scan. Repository ignore/config files and inherited `TRIVY_*` environment settings cannot silently suppress results. No vulnerability exclusions or unfixed-finding exemptions are enabled.

| Exit code | Meaning |
|---|---|
| 0 | Every requested full scan completed with no high, critical or unknown-severity findings |
| 1 | Completed scans contain blocking findings |
| 2 | Partial coverage, missing image, missing/invalid report or scan error |

Build/install failures also return nonzero. An image with no analyzed targets is incomplete, rather than presumed clean. `--os-only` is available for quick triage but **always returns 2** because language coverage is missing. A passing scanner result alone does not establish production security or satisfy the external gates in [Deployment validation](DEPLOYMENT_VALIDATION.md).

The `Security release gate` GitHub workflow runs on pull requests and manual dispatch. It publishes a compact status in the job summary, without uploading runtime logs or private image metadata. Main-branch protection now requires `Source checks`, `Development integration` and `Security release gate`, with strict up-to-date status checks and administrator enforcement. Force pushes and deletions are disabled. The PR remains draft and unmerged. GitHub's account billing lock currently prevents hosted execution.

## Full local validation and backlog

All nine scans completed on 13 October 2026 (Asia/Kolkata), including OS packages and supported language libraries. The Java index was downloaded and checked against its OCI layer SHA-256. The release result is **BLOCKED_FINDINGS (exit 1)**, with no incomplete targets or critical findings. Counts are package findings, not distinct exploitable vulnerabilities.

| Target | Critical | High | Medium | Low | Unknown |
|---|---:|---:|---:|---:|---:|
| source | 0 | 0 | 1 | 0 | 1 |
| api | 0 | 0 | 15 | 16 | 0 |
| keycloak | 0 | 0 | 2 | 0 | 0 |
| postgres | 0 | 0 | 1 | 0 | 0 |
| fabric-peer | 0 | 57 | 137 | 36 | 20 |
| fabric-orderer | 0 | 19 | 119 | 33 | 8 |
| fabric-ca | 0 | 56 | 125 | 33 | 24 |
| fabric-nodeenv | 0 | 0 | 2 | 0 | 0 |
| kubo | 0 | 0 | 0 | 0 | 1 |

The source locks retain one medium finding and the unknown-severity Go advisory described below. The actual API, Keycloak, PostgreSQL and Node runtime images have no high, critical or unknown findings. Full scanning exposed old Go code in PostgreSQL/IPFS privilege-switching helpers; those unnecessary helpers were removed and both fresh-volume and existing-data startup were verified without root.

Fabric 2.5.16 / CA 1.5.22 still contain high/unknown findings. The rebuilt IPFS binary has no high findings, but retains GO-2026-5932 against golang.org/x/crypto with unknown severity and no listed fixed version; the source lock reports the same advisory. See [IPFS rebuild](IPFS_RUNTIME.md). Upstream patch upgrades and OS fixes reduced the findings but did not eliminate them. No advisory is automatically waived as unreachable or unfixed. These remaining binaries/dependencies need reviewed vendor/toolchain remediation, compatibility testing and another complete scan before release. The Node runtime now rebuilds npm 12.2.0 with a reviewed SHA-512-locked dependency graph (brace-expansion 5.0.11, http-cache-semantics 4.3.0 and undici 6.28.1). It reports two medium findings and no high/critical/unknown findings; both peers installed and ran the chaincode successfully. See `deploy/npm-runtime/README.md` for this maintained-build boundary.

The reports are retained locally at `.runtime/security/20261013T065713Z-58d3bdbb/`, with immutable image references in `summary.json`; they are intentionally not committed. These retained results precede the final receipt layout and mobile wrapping fixes. Later builds or database updates require fresh scans. Host, infrastructure and external deployment coverage remain outside this local gate. See [Security scan](SECURITY_SCAN.md), [Runtime patches](RUNTIME_PATCHES.md) and [Identity migration](IDENTITY_MIGRATION.md).

Five regression tests exercise critical/unfixed/unknown finding rejection, full clean/lower-severity results, partial coverage, missing/failed scans and invalid reports:

```sh
python3 scripts/test-security-gate.py
```
