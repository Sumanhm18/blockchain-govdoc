# Deployment validation and ownership policy

## Published work

Draft PR: https://github.com/Sumanhm18/blockchain-govdoc/pull/1. The initial GitHub check did not start: GitHub reported an account lock caused by a billing issue. This is an external account blocker, not a passing or failing source test. An account owner must resolve billing and rerun the workflows. No payment or billing setting was changed.

## Citizen access

An issuer can supply optional `ownerSubject` on metadata registration or multipart upload. This must be the citizen's immutable OIDC `sub`, obtained through the authorized issuance process, not an email address or display name. The API stores only `SHA-256(issuer URL + NUL + sub)` as `metadata.ownerSubjectHash`; it does not retain that raw ownership identifier. This is pseudonymization, not anonymity or a defense against a known-identifier dictionary attack.

A `READER` can read records whose issuer and MSP match the configured department. A `CITIZEN` can retrieve, download and verify only their own records in that department. Ownership comes from the validated JWT issuer and subject, never from a caller-supplied query parameter. Inaccessible records return 404 to avoid revealing their existence. Citizens cannot register, revoke, read transaction history or inspect registration jobs. Legacy records without a verified issuing MSP/owner hash need reviewed migration before these access paths permit them. Other Fabric channel members still retain their channel-level read rights; API access policy does not encrypt ledger metadata.

Ownership is assigned at issuance and cannot be changed by retrying an upload. A transfer requires a reviewed lifecycle/migration design. Citizen self-registration, account recovery and proofing remain identity-provider/government processes.

## Reproducible TLS/OIDC staging

```sh
./deploy.sh
./scripts/staging.sh up
python3 scripts/test-oidc.py
```

Staging uses Keycloak 26.8.0 in its secured server mode, PostgreSQL, a separate API journal/key ring, HTTPS on both identity and API endpoints, explicit CA trust and pinned images. It reuses the isolated development Fabric/IPFS dependencies. The API is at `https://localhost:18444`; identity requests are available at `https://localhost:18443`. The container-visible issuer name is `identity.govdoc.local`; no host DNS or system trust store is modified. The test client uses the staging CA file, not disabled TLS verification.

Generated credentials stay under `.runtime/staging/private`; certificate authority keys are not mounted into services. Certificates last 30 days. The generated `govdoc-lab` realm contains synthetic users and a password-grant test client solely for automated checks. Real citizen login must use authorization code + PKCE, a registered redirect URI, identity proofing, MFA as appropriate and managed account recovery. Do not promote this test realm or local CA unchanged. The Keycloak server setup follows its [container guidance](https://www.keycloak.org/server/containers) and [TLS configuration](https://www.keycloak.org/server/enabletls).

`deploy/production-api.compose.yml` is a fail-closed template for an operator-provisioned host, with HTTPS, JWT, explicit identities, mounted secret-store projections and persistent storage. It intentionally has one API replica because the current journal needs single-writer semantics. No cloud account, domain, public certificate, external CA administration endpoint or secret-manager account was supplied. Consequently no internet production deployment, HSM/KMS integration or highly available Fabric/IPFS deployment has been performed.

## Operational drills

- `scripts/test-backup-restore.py`: encrypted application journal/key/IPFS snapshot, tamper rejection, checksum validation and isolated restored API/IPFS download. Requires the pinned Python `cryptography` tool dependency. Stops and restores only this project's services. It does not restore the Fabric ledger or Keycloak database.
- `scripts/test-revocation.py`: enrolls a new disposable development identity, verifies it works, revokes it, generates a CRL, publishes the CRL through a signed channel MSP update, verifies denial and checks the existing service identity still works. It does not revoke old identities on unidentified deployments or replace compromised external CA roots.
- `scripts/test-quorum-recovery.py`: stops the required Org2 endorser, verifies writes fail, restores it, and verifies one registration. The topology is demonstrably not highly available.
- `scripts/test-oidc.py`: real issuer discovery/JWK/signature verification over TLS, citizen isolation, role denial, revocation and a small concurrent-read smoke test. Twenty reads at concurrency four are not a production capacity benchmark.

Encrypted backups are retained in `.runtime/backups`. Their AES-256-GCM wrapping key is stored separately at `.runtime/secrets/backup-master.key`. Move encrypted backups and a separately controlled copy of the wrapping key to your organization's backup system; local copies on one computer do not provide disaster recovery. The restore drill checks authentication before extracting any archive.

## Monitoring and release gates

Existing OPERATOR-only actuator metrics now include `registry.upload.pending`, `registry.upload.rejected` and `registry.upload.oldest.pending.seconds` (age since the oldest pending job's last update). Integrate these with the organization's monitoring system; alert on sustained backlog, rejected jobs, dependency readiness, capacity, certificate expiry and security failures. No notification destination has been configured.

Before public deployment: complete external credential inventory/revocation and CA trust migration, review [residual security findings](SECURITY_SCAN.md) and advisory applicability, provision real identity/secret/backup infrastructure, implement resilient network/storage topology and prove recovery/failover under the chosen production policy. Billing-blocked GitHub CI must also run successfully. These are explicit external release gates, not claims that a local staging test fulfills them.
