# Operations

## Development lifecycle

Run `./deploy.sh` from the repository root. It creates only `govdoc-dev-*` Fabric containers and the `govdoc-dev-app` Compose project. Existing networks with other names are not used. The isolated Docker CLI configuration in `.runtime/docker` avoids changing your global credential-helper settings. Preserve `DOCKER_HOST` if using a nonstandard daemon. Setup requires available localhost ports and sufficient Docker memory/disk for two peer chaincode builders.

For a host-run Java API, bring up Fabric and IPFS first, then use `./scripts/run-api.sh`; do not run it while the container API already owns port 18081 or the H2 journal. `scripts/network.sh` supports `up`, `deployCC`, and `status`. Deployment uses the upstream automatic chaincode sequence selection; an explicit `CC_SEQUENCE` may be required for a reviewed upgrade. Never reuse this script against a production channel.

Stop without deleting data:

```sh
./scripts/stop-dev.sh
```

Restart with `./deploy.sh`. Do not run the upstream sample `network.sh down` directly: some versions perform broad Docker cleanup. No automatic reset/delete command is supplied. To deliberately erase this development network, inventory the named Compose projects, take any needed backups, then remove only those projects' containers/volumes and their generated runtime state. Do not delete encryption keys while retaining encrypted documents.

## Configuration

| Variable | Purpose |
|---|---|
| `REGISTRY_ISSUER` | Department matching the service certificate attribute |
| `FABRIC_PEER`, `FABRIC_AUTHORITY`, `FABRIC_MSP` | Gateway endpoint, TLS hostname and signing MSP |
| `FABRIC_CHANNEL`, `FABRIC_CHAINCODE` | Defaults `govchannel`, `docregistry` |
| `FABRIC_CERT`, `FABRIC_KEY`, `FABRIC_TLS_CERT` | Readable credential/trust file paths |
| `IPFS_API_BASE` | Private trusted IPFS administrative endpoint |
| `REGISTRY_DATABASE_URL`, `REGISTRY_DATABASE_PASSWORD` | Durable journal configuration |
| `REGISTRY_KEYS_FILE` | Encryption key ring JSON |
| `REGISTRY_AUTH_MODE`, `REGISTRY_USERS_FILE` | Development Basic authentication configuration |
| `REGISTRY_JWT_ISSUER`, `REGISTRY_JWT_AUDIENCE` | HTTPS issuer discovery and required audience |
| `REGISTRY_REQUIRE_HTTPS`, `REGISTRY_BIND_ADDRESS` | Transport and bind policy |
| `REGISTRY_CORS_ORIGINS` | Comma-separated explicitly trusted origins; empty by default |
| `REGISTRY_RATE_LIMIT` | Authenticated requests/account/minute, default 120 |
| `REGISTRY_IP_RATE_LIMIT` | Preauthentication requests/direct IP/minute, default 600 |

Start a production adaptation with `SPRING_PROFILES_ACTIVE=production`, explicitly provisioned JWT issuer, TLS and production identity/storage settings. The supplied Compose file is intentionally a development Basic-auth override and must not be promoted unchanged. JWTs require `department` matching `REGISTRY_ISSUER` and a `roles` list containing relevant `READER`, `ISSUER`, `REVOKER` or `OPERATOR` values. Java verifies issuer signatures through discovery/JWKs. There is no local production identity provider in this repository.

HTTPS can terminate in the application with Spring SSL settings. If terminating at a reverse proxy, establish a private trusted hop and explicitly review Spring forwarded-header handling; default configuration ignores forwarded headers. Put preauthentication rate/connection/body limits and appropriate request timeouts at ingress.

## Readiness, audit and alerts

Readiness checks real Fabric `ping`, IPFS `version` and database connectivity. Liveness only reflects application state; dependency failure should remove readiness, not trigger endless liveness restart cycles. Detailed health is hidden. Metrics require OPERATOR. API logs include request UUID, hashed actor, route template and status, never file bodies or raw Gateway errors. Use restricted external audit retention in production. Development Fabric/enrollment logs can contain generated bootstrap secrets: treat the entire `.runtime` tree as secret.

Alert on sustained readiness failure, HTTP 202 backlog, `REJECTED` jobs, journal capacity, low disk, unavailable pinned content, key/certificate expiry, elevated 401/403/429 rates and ledger policy changes. The local pending ciphertext capacity is 512 MiB; completed journal metadata remains retained. Retention/archival needs an operator policy. The application has no email alert service.

## Upload recovery and pin cleanup

A pending upload is retried on the scheduler with bounded batches and exponential backoff (5 seconds to 5 minutes). The same registration ID and encrypted bytes survive API restarts. Query status through the API; inspect protected local database backups for deeper investigation. Repeating identical upload input also resumes it. Different input cannot replace it. A conflicting ledger record or forbidden/invalid request is rejected for operator review.

Do not unpin after a timeout. First establish final ledger outcome using a healthy peer/channel, correlate registration ID and full intended record with the journal, and identify every record/job referencing the CID. Preserve data while outcome is uncertain. For a conclusively rejected, unreferenced upload, an authorized operator may unpin its exact CID using the private IPFS admin interface, retain an audit decision and archive the journal entry. This deliberate procedure avoids automatic deletion of successfully committed or shared data. No API performs destructive pin cleanup.

## Backup and restore

Stop the API before a filesystem copy of H2; never copy a live H2 file as a consistency guarantee. Back up `.runtime/data`, encrypted IPFS repository/volume, all encryption key versions, service identity and network state/configuration using access-controlled encrypted backups. Keep keys separately controlled from content backups. For real Fabric deployments use the operators' consistent ledger/snapshot/MSP/CA backup process rather than copying live container directories.

Restore keys and journal together, restore/re-pin IPFS content, restore the correct Fabric channel and identity, then start one API instance. Confirm readiness, reconcile pending uploads, and test a known synthetic document's decrypted digest and revocation status. A CID alone does not guarantee that content is still available. Disaster-recovery and restore drills on external infrastructure remain operator work.

## Rotation and migration

For encryption rotation, add a new random 32-byte key under a new ID in the secret key ring and set `activeKeyId`; preserve older entries needed to decrypt existing records, then restart the API. Never edit an existing key's value. This affects new uploads only; re-encryption and new ledger references require a reviewed migration rather than silently rewriting history.

For service identity rotation, enroll a new key/certificate with the same authorized department attributes into a separate secret path, validate it, switch configuration and restart, then revoke the old certificate and distribute its CRL through Fabric governance. `enroll-service.py` refuses to overwrite an existing identity and is a fresh-development enrollment helper, not an external revocation tool. Enrollment revocation alone is insufficient until channel MSPs enforce the CRL. Exposed CA signing keys require replacing trust roots as well.

Legacy records remain historical facts. Do not infer missing `issuerMspId` from caller-supplied text. Inventory old IDs and plaintext CIDs, establish provenance under multi-organization governance, and define an approved migration/new-record linkage before enabling old-record mutation. This implementation intentionally refuses ambiguous legacy revocation/download.

## Release limits

Fixed image digests are in `config/images.json`; Compose/Dockerfile pin the same references. Fabric binary downloads use exact HTTPS release assets; recorded local SHA-256 files are provenance records, not independently authenticated published checksums. Review upstream releases and update the pins together. The one-orderer, one-IPFS, file-journal topology, development secret files and test CA conventions do not constitute a production deployment.
