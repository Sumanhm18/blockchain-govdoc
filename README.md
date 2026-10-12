# Government Document Registry

A development implementation of document registration, encrypted storage, verification and revocation using Spring Boot, Hyperledger Fabric and IPFS. The ledger stores a plaintext SHA-256 digest and limited metadata; IPFS stores AES-256-GCM ciphertext. A matching hash is valid only while the record is not revoked.

This repository is a prototype, not a certified government service. Its Fabric sample network has two organizations, one peer per organization and one Raft orderer. It is not highly available. Blockchain preserves submitted records; it cannot establish whether their original contents are true.

## Submission and presentation

Start with the [October 14 submission guide](docs/SUBMISSION.md) and [project report draft](docs/PROJECT_REPORT.md). On a prepared computer, run `python3 scripts/resume-demo.py`, then `./demo.sh --pause` for a live six-step walkthrough. The restart command reuses existing containers without downloads or rebuilds.

## Quick start

Requirements: macOS or Linux, Docker with Compose, Git, Python 3.12+, and internet access. Node 20+ and Java 17+/Maven are needed only for host-side tests/development. Start Docker first. Run from this directory:

```sh
./deploy.sh
./test_demo.sh
```

`deploy.sh` initializes the pinned Fabric samples submodule, downloads versioned Fabric tools, generates fresh development credentials and encryption keys, creates an isolated network, deploys the contract, enrolls an attributed issuer identity, and builds the API container with tests. First deployment includes a pinned Go source build for IPFS and can take substantially longer on a cold cache. Prepare before presentation day; use `python3 scripts/resume-demo.py` for subsequent rehearsals. See [IPFS runtime](docs/IPFS_RUNTIME.md). Private runtime files and detailed logs remain in ignored `.runtime/`; do not publish that directory. Development account passwords are available locally in `.runtime/secrets/credentials.json`, never in source or command examples.

API: `http://127.0.0.1:18081`. Readiness: `/actuator/health/readiness`. The IPFS administrative API is local-only at port 15001. Fabric host ports are 17050/17051/19051; CA ports are 17054/18054/19054. Existing standard Fabric sample containers are not used.

The synthetic integration test registers and revokes a new test record. It checks authorization, encrypted storage/download, hash and file verification, duplicates, missing records and history, plus two direct Fabric authorization checks. Run `python3 scripts/test-recovery.py` against the container stack to exercise an IPFS outage and API restart. It leaves its synthetic record on the development ledger.

## Implemented

- Existing register/get/verify/revoke routes, plus encrypted upload/download, file verification, history and upload status.
- Fail-closed authentication, method roles, JWT issuer/audience/department validation, issuer attributes checked in chaincode, department-controlled revocation.
- Actual-byte hashing, bounded PDF/PNG/JPEG signature checks, structured metadata and errors, per-account and preauthentication IP rate limiting and redacted request audit logs.
- Durable encrypted upload staging with retry/reconciliation across IPFS/Fabric failures and uncertain commit outcomes.
- External configuration, fresh development identities, pinned dependency/image versions, readiness checks and automated tests.

## Documentation

- [Architecture and trust boundaries](docs/ARCHITECTURE.md)
- [API contract and compatibility](docs/API.md)
- [Operation, recovery, backup and rotation](docs/OPERATIONS.md)
- [TLS/OIDC staging, citizen access and operational drills](docs/DEPLOYMENT_VALIDATION.md)
- [Security and historical credential exposure](docs/SECURITY.md)
- [Validation evidence and remaining work](TESTING_SUMMARY.md)

## Tests without Docker

```sh
npm ci --prefix chaincode/docregistry
npm test --prefix chaincode/docregistry
mvn -B -f application/api-spring/pom.xml verify
python3 scripts/check-secrets.py
```

CI runs these checks. A separately invoked integration workflow provisions the development stack and runs the synthetic scenario.

## Scope

Department readers can access records from their issuing department/MSP. Citizens with validated JWTs can retrieve, download and verify records assigned to their issuer/subject hash. Citizens cannot issue, revoke or access history. The API still uses one Fabric service identity per department; application logs identify individual actors by a stable hash. See the deployment validation document for identity proofing and production limits.

QR codes, a frontend, DID/VC, selective disclosure, zero-knowledge proofs, malware scanning, replicated IPFS, multi-orderer availability and performance benchmarking are not implemented. See the operations and security documents before adapting this development setup for real documents.
