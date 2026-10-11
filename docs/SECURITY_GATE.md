# Reproducible security release gate

Run from the repository root:

```sh
python3 scripts/install-trivy.py
./scripts/security-scan.sh --build
```

The installer downloads Trivy 0.75.0 from its official GitHub release and checks the archive against the platform-specific SHA-256 committed in `config/trivy.json`. It extracts only the scanner binary into ignored `.runtime/tools`. Updating the scanner requires reviewing its release and changing the committed version/checksums together.

The runner builds the API, optimized Keycloak and patched Fabric images without starting services or generating credentials. It resolves local image tags to immutable content IDs, then scans the application source, API, Keycloak, PostgreSQL, Fabric peer/orderer/CA/Node runtime and IPFS. Third-party references come from the committed image pins. Full mode includes OS and supported language dependencies; Trivy may download its large Java index. Build and scanner subprocesses have timeouts.

Each run creates a new restricted `.runtime/security/<timestamp>-<random>/` directory containing raw JSON inventory/findings, protected diagnostic logs and `summary.json`. Existing reports cannot be mistaken for a fresh successful scan. Repository ignore/config files and inherited `TRIVY_*` environment settings cannot silently suppress results. No vulnerability exclusions or unfixed-finding exemptions are enabled.

| Exit code | Meaning |
|---|---|
| 0 | Every requested full scan completed with no high, critical or unknown-severity findings |
| 1 | Completed scans contain blocking findings |
| 2 | Partial coverage, missing image, missing/invalid report or scan error |

Build/install failures also return nonzero. An image with no analyzed targets is incomplete, rather than presumed clean. `--os-only` is available for quick triage but **always returns 2** because language coverage is missing. A passing scanner result alone does not establish production security or satisfy the external gates in [Deployment validation](DEPLOYMENT_VALIDATION.md).

The `Security release gate` GitHub workflow runs on pull requests and manual dispatch. It publishes a compact status in the job summary, without uploading runtime logs or private image metadata. Repository branch-protection settings have not been changed; an administrator must require this check before merging/releasing. GitHub's account billing lock currently prevents hosted execution.

## Local validation and backlog

The OS triage pass scanned the actual API and optimized Keycloak builds plus the pinned runtime images. Counts below are package findings; the same advisory can affect multiple packages or images.

| Target | Critical | High | Medium | Low | Unknown |
|---|---:|---:|---:|---:|---:|
| Source dependency graph | 2 | 0 | 0 | 0 | 0 |
| API OS | 0 | 0 | 15 | 16 | 0 |
| Keycloak OS | 0 | 6 | 29 | 29 | 0 |
| PostgreSQL OS | 1 | 63 | 110 | 132 | 11 |
| Patched Fabric peer OS | 0 | 0 | 155 | 46 | 0 |
| Patched Fabric orderer OS | 0 | 0 | 155 | 46 | 0 |
| Patched Fabric CA OS | 0 | 0 | 155 | 46 | 0 |
| Patched Fabric Node runtime OS | 0 | 0 | 1 | 0 | 0 |

IPFS returned no OS package targets, so this pass does not establish its vulnerability status. Its compiled Go dependency scan remains required. The broader full library scans also remain incomplete. The runner correctly returns `INCOMPLETE`, not a passing release result.

The [priority Fabric OS fixes](RUNTIME_PATCHES.md) are applied and validated. Next: review PostgreSQL/Keycloak findings against vendor support and reachable features, and run full binary/library scans. A listed package fix does not justify an untested in-place change to a running blockchain network. Pin and validate replacement images through the existing integration/recovery tests before promotion. The previously documented Spring feature-applicability assessment remains in [Security scan](SECURITY_SCAN.md); this gate does not automatically waive those findings.

Five regression tests exercise critical/unfixed/unknown finding rejection, full clean/lower-severity results, partial coverage, missing/failed scans and invalid reports:

```sh
python3 scripts/test-security-gate.py
```
