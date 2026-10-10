# Security notes and incident actions

## Historical credentials

The original repository tracked Fabric private keys, wallets, generated organizations and uploaded files. Treat those credentials and any documents in public history as exposed. Removing files from the current tree does **not** revoke credentials, remove old commits, erase forks/caches or make previously published documents confidential.

The new development setup enrolls fresh identities and generates independent account passwords, CA enrollment secrets and encryption keys. It does not reuse or revoke identities on an existing external network. Operators must inventory every deployment using the exposed material, revoke affected enrollment certificates, publish CRLs to channel MSP configuration, replace compromised CA/TLS trust roots where applicable, rotate dependent credentials, and verify old identities can no longer transact. A compromised CA signing key requires a trust-root migration, not merely reenrolling a client. Preserve required incident evidence in restricted storage.

`./scripts/prepare-history-cleanup.sh` creates a **local sanitized mirror** after changes have been committed and `git-filter-repo` is installed. It does not alter the working repository or push. Review its report, coordinate with repository administrators, rotate affected credentials first, then agree an explicit force-push window and require fresh clones. Contact the Git hosting provider about cached commits and sensitive-data removal. Existing forks/backups are outside Git rewrite guarantees.

## Enforced controls

- Fail-closed API authentication; explicit read/issue/revoke/operator roles.
- Production JWT signature verification via HTTPS issuer discovery, issuer/time/audience/department claim validation.
- Chaincode issuer attributes and same-MSP/same-department revocation.
- TLS to Fabric, bounded IPFS calls, actual-byte hashes, authenticated encryption with document-ID binding.
- Request validation, bounded uploads/downloads, structured safe errors, redacted account-level audit and per-account and preauthentication IP request limits.
- No public IPFS gateway port; local-only development administrative ports; read-only secrets mounts and non-root API container.

## Boundaries and remaining deployment controls

The development Basic mode is loopback-only and is unsuitable for internet exposure. In-memory account/IP rate limits are not global across instances and do not replace distributed brute-force/connection/body limits at the gateway. IP limits use the direct socket address, so a shared reverse proxy needs correctly designed ingress limits. Configure those at a trusted ingress. Do not trust arbitrary forwarded headers; the application defaults to ignoring them.

One service certificate signs a department's API transactions. Individual API actors appear as hashes in application logs, not as independent Fabric signing identities. Protect and export audit logs to an access-controlled append-only service. Readers can access all documents exposed by that departmental API; citizen ownership rules are not implemented.

Certificate files, JSON encryption keys and the H2 journal are development storage mechanisms. Production needs a managed secret store/HSM/KMS strategy, explicit rotation, backups, access reviews, monitoring, storage quotas and recovery drills. Ledger hashes/metadata are not encrypted. Encryption does not remove previously published plaintext IPFS copies. Revocation prevents this API's download, but cannot recall files or keys already held by a recipient.

Signature checks are not malware scanning. Add scanning/content validation before issuing real records. Test governance, endorsement requirements, root revocation, availability, load, dependency advisories and incident handling before real deployment. QR, DID, VC, selective disclosure and zero-knowledge proofs are outside this implementation.

The source checker detects obvious private-key material and generated artifact paths; it is not a comprehensive secret detector and does not scan Git history. Do not attach `.runtime`, network/enrollment logs or real document fixtures to issues or CI artifacts.
