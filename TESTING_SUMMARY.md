# Validation record

Validated locally on 10 October 2026 using macOS arm64, Docker Desktop, a fresh isolated Fabric development network, IPFS and synthetic documents only. No external government deployment was tested.

## Automated suites

| Check | Result / coverage |
|---|---|
| Java Maven suite | 32 tests pass: HTTP authentication/roles/validation/errors, JWT claim validation, account/IP limits, structured IPFS responses/failures/limits, encryption and tampering, durable recovery, duplicate handling, revoked download and Gateway dependency compatibility |
| Node contract suite | 15 tests pass: registration, issuer attributes, cross-department/MSP denial, duplicates, missing IDs, validation, matching/mismatching/revoked outcomes, deterministic metadata, post-2038 timestamps, history and Fabric dispatcher argument count |
| Live API/IPFS/Fabric | 16 checks pass: authenticated upload, encrypted IPFS bytes, verified plaintext download, hash/file verification, revocation, duplicates, missing records and history |
| Direct Fabric authorization | 2 checks pass: unattributed identity and department impersonation rejected without relying on HTTP security |
| Live outage/restart recovery | Pass: IPFS failure, durable staging, API restart, automatic recovery, exact download and no duplicate registration |
| Repeat deployment | Pass: rerunning `deploy.sh` preserves the existing development state and returns readiness |
| Container build | Multi-stage build passes Maven tests on Java 17; API starts as a non-root user with read-only root filesystem and secret mounts |
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

CI definitions are included for source checks and a manually invoked full development integration run. They have not yet run on GitHub because these changes have not been pushed. Runtime logs contain private CA/bootstrap material and are intentionally excluded from CI artifacts.

## Not established by these tests

Production readiness, external OIDC integration, HSM/KMS integration, certificate-revocation rollout, multi-node failover, multi-instance journal correctness, malicious-file detection, load/latency targets, legal compliance, comprehensive vulnerability absence, third-party penetration testing and disaster recovery are not established. The npm result is point-in-time and does not assess Maven/container/OS dependencies. The source scan is a limited check, not a full history secret audit. Read [Security](docs/SECURITY.md) and [Operations](docs/OPERATIONS.md).
