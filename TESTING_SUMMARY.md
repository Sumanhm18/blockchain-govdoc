# Validation record

Validated locally on 11–12 October 2026 using macOS arm64, Docker Desktop, a fresh isolated Fabric development network, IPFS and synthetic documents only. No external government deployment was tested.

## Automated suites

| Check | Result / coverage |
|---|---|
| Java Maven suite | 41 tests pass on Spring Boot 4.1.1, Jackson 3.1.7 and Tomcat 11.0.26: signed JWT validation, citizen ownership and department isolation, protected metrics, HTTP authentication/roles/validation/errors, JWT claim validation, account/IP limits, structured IPFS responses/failures/limits, encryption and tampering, durable recovery, duplicate handling, revoked download and Gateway dependency compatibility |
| Node contract suite | 15 tests pass: registration, issuer attributes, cross-department/MSP denial, duplicates, missing IDs, validation, matching/mismatching/revoked outcomes, deterministic metadata, post-2038 timestamps, history and Fabric dispatcher argument count |
| Live API/IPFS/Fabric | 16 checks pass: authenticated upload, encrypted IPFS bytes, verified plaintext download, hash/file verification, revocation, duplicates, missing records and history |
| Direct Fabric authorization | 2 checks pass: unattributed identity and department impersonation rejected without relying on HTTP security |
| Live outage/restart recovery | Pass: IPFS failure, durable staging, API restart, automatic recovery, exact download and no duplicate registration |
| Repeat deployment | Pass: rerunning `deploy.sh` preserves the existing development state and returns readiness |
| Container build | Multi-stage build passes Maven tests on Java 17; API starts as a non-root user with read-only root filesystem and secret mounts |
| Real HTTPS/OIDC staging | 37 checks pass against Keycloak, including token signature/discovery, citizen isolation, ownership retry conflicts and revocation; 20 concurrent-read smoke requests have zero failures |
| Encrypted restore | Pass: encrypted snapshot, tamper rejection, checksums and exact download from an isolated restored API/IPFS instance |
| Disposable certificate revocation | Pass: enrollment, CA revocation, signed channel MSP CRL update, revoked identity denial and existing service continuity |
| Patched runtime deployment | Fabric 2.5.16 / CA 1.5.22; all six services and both chaincode containers use the patched image contents. IPFS runs as UID 1000; live checks pass |
| Identity migration | Encrypted logical PostgreSQL 17 migration preserves identity/client/credential fingerprints; old volume retained; 37 OIDC checks pass after non-root PostgreSQL/Keycloak changes |
| IPFS source rebuild | Go 1.26.9 build, locked module checksums and upstream repo/fsrepo + core/coreunix tests pass; isolated encrypted restore, 16 live API + 2 Fabric + 14 walkthrough + 37 OIDC checks and outage/restart recovery pass with the deployed image |
| Fresh data volumes | IPFS initializes as UID 1000 and PostgreSQL as UID 70 without the old gosu helpers |
| Full security gate | All nine targets scanned; no critical findings. API/Keycloak/PostgreSQL/Node runtime have no high/unknown findings. Fabric high/unknown findings and the IPFS/source x/crypto unknown finding correctly block release |
| Main branch protection | Three required checks, strict branch updates and administrator enforcement enabled; force pushes/deletions disabled |
| Required endorser outage | Pass: writes fail safely during outage and recover without duplicate registration; this is not HA failover |
| Prepared demo restart | All eight owned services stopped and resumed without builds, pulls, key regeneration or data reset; 14-check walkthrough passes afterward |
| Presentation walkthrough | 14 checks pass; included in integration CI: role denial, actual-file digest, IPFS ciphertext, exact download, original/tampered verification, revocation and retained history |
| npm audit | No advisories reported for the locked chaincode dependency graph at validation time |
| Source hygiene | Private-key/generated-artifact pattern check and `git diff --check` pass |

The API workflow passed both with a host-run Java process and the containerized API. Runtime validation found and fixed a Protobuf BOM conflict, peer TLS trust-root selection, isolated CA certificate path references, and JavaScript default-parameter arity that Fabric uses to construct transaction metadata. These issues demonstrate why mock-only tests were insufficient.

## Recovery scenario

`python3 scripts/test-recovery.py` requires the running `./deploy.sh` container stack. It checks project ownership before stopping only this project's IPFS container, uploads a synthetic PDF and expects durable HTTP 202/STAGING, restarts the API, restores IPFS in a finally block, then checks automatic confirmation, exact decrypted bytes, identical-request retry and a single registration history event. It revokes its synthetic record at the end. This scenario passed against the containerized stack.

Unit tests also cover the distinct uncertain-commit case: Fabric commits but the caller receives a dependency error; reopening the journal and observing the matching record confirms it without uploading or submitting again. That particular post-commit network fault is simulated in the unit suite, not injected into the live orderer connection.

## Reproduce

```sh
npm ci --prefix chaincode/docregistry
npm test --prefix chaincode/docregistry
mvn -B -f application/api-spring/pom.xml verify
python3 scripts/check-secrets.py
./deploy.sh
./test_demo.sh
python3 scripts/test-recovery.py
```

CI definitions run source and development integration checks on pull requests, with manual integration dispatch also available. The draft PR is published, but GitHub rejected workflow startup because the account is locked by a billing issue; hosted CI has not executed. Runtime logs contain private CA/bootstrap material and are intentionally excluded from CI artifacts.

## Not established by these tests

Production readiness, live government identity integration, HSM/KMS integration, revocation on unidentified external deployments, highly available failover, multi-instance journal correctness, malicious-file detection, production load targets, legal compliance, vulnerability absence, penetration testing and full disaster recovery are not established. Read [Security scan](docs/SECURITY_SCAN.md) for residual Maven/container findings and coverage limits. History checks search limited private-key patterns; they cannot prove all sensitive data has been removed. Read [Security](docs/SECURITY.md) and [Operations](docs/OPERATIONS.md).
