# October 14 submission and demo guide

This is a working local prototype of a permissioned government-document registry. Present the implemented lifecycle and measured results; production deployment is future work. Use synthetic documents only.

## Before submission day

1. Keep the repository and the prepared Docker images, containers, named volumes and private `.runtime/` directory on your demonstration computer. Do not delete volumes or run Docker cleanup commands.
2. Start Docker. From the repository root, run `python3 scripts/resume-demo.py` and `./demo.sh`. This reuses the prepared local stack; it does not download images or rebuild dependencies.
3. For a fresh computer, clone with `git clone --branch harden-document-registry --recurse-submodules https://github.com/Sumanhm18/blockchain-govdoc.git`, enter the directory and run `./deploy.sh` with internet access. Use the submitted commit recorded in the bundle manifest and run `git submodule update --init` after checking out that revision. Prepare it well before the presentation. A source-review ZIP is not a replacement for this Git checkout/submodule setup.
4. Rehearse `./demo.sh --pause`. Each run creates and revokes a new synthetic record; existing records and keys are preserved.
5. For citizen ownership, separately start `./scripts/staging.sh up`, then run `python3 scripts/wait-staging.py` and `python3 scripts/test-oidc.py`. Staging initialization/builds may require internet. Keep the local realm and CA private.
6. Review [the report draft](PROJECT_REPORT.md), [test evidence](../TESTING_SUMMARY.md) and [security status](SECURITY_GATE.md). Add your name, institution and required formatting to your final report.

Do not perform dependency upgrades, delete the working checkout, or reset identities on the presentation day. Keep an independently controlled backup of local runtime data and keys. Source control alone does not preserve encrypted documents or their decryption keys.

## Six-minute walkthrough

| Time | Demonstrate | Explain |
|---|---|---|
| 0–1 minute | Architecture | Spring Boot coordinates Fabric and IPFS; document bytes stay off-chain |
| 1–2 minutes | Authorization and issuance | Reader cannot issue; issuer registers the actual file digest |
| 2–3 minutes | Storage and download | IPFS contains AES-GCM ciphertext; authorized retrieval decrypts and verifies bytes |
| 3–4 minutes | Original and modified files | Original returns VALID; changed bytes return TAMPERED_OR_MISMATCH |
| 4–5 minutes | Revocation | Reader is denied; issuer revokes; even original matching bytes return REVOKED |
| 5–6 minutes | History and limits | Both lifecycle events remain; summarize recovery tests and production gaps |

Commands:

```sh
python3 scripts/resume-demo.py
./demo.sh --pause
```

A rehearsal stopped and resumed all eight managed development services and reran all 14 checks successfully. The CLI shows safe synthetic evidence, not passwords or tokens. Successful output ends with **PASS: 14 checks** and writes `.runtime/submission/demo-evidence.json`. If the demo fails, do not present a prior evidence file as a live success. Inspect readiness/private logs and rerun. A temporary HTTP 202 is a pending durable upload, not success; the walkthrough waits for ledger confirmation.

For a spoken opening, expanded viva answers and presentation-day troubleshooting, use [Viva notes](VIVA_NOTES.md). Personal report details are deferred to the author.

## Questions you should be able to answer

- **Why Fabric?** Membership and endorsement policies suit a consortium of known departments. The prototype has two organizations; both endorse writes under its policy.
- **Why not store PDFs on-chain?** Large documents increase replicated ledger storage. Only a digest, content reference and limited metadata are recorded; IPFS holds encrypted bytes.
- **Is SHA-256 encryption?** No. SHA-256 is for integrity; AES-256-GCM provides authenticated encryption. The IPFS CID identifies ciphertext and is distinct from the plaintext digest.
- **What does VALID mean?** The record exists, supplied file bytes match its digest, and the record is not revoked. It does not prove that the original claims were truthful.
- **What if IPFS or Fabric fails?** A durable encrypted journal retries and reconciles uncertain outcomes by querying the ledger first. Tests cover restart recovery; there is no cross-system atomic transaction.
- **Who can read?** Department readers may access their department's records. An authenticated citizen may access their own assigned records, identified using a hash of OIDC issuer and subject. Citizens cannot issue, revoke or access history.
- **Can revocation delete every copy?** No. It changes validity and blocks this API's download path; previous downloads and immutable history remain.
- **Is it production-ready?** No. Residual security findings, external credential cleanup, real citizen proofing, managed keys, off-site backups and resilient infrastructure remain. One orderer and a single API journal are not highly available.
- **What was measured?** Local functional/security/recovery tests. The small concurrent-read smoke is not a throughput benchmark or government certification.

## What to submit

- Repository URL and exact submitted commit; include the Fabric submodule pin.
- Source-review archive and its SHA-256, if your institution requests an upload.
- Project report adapted from `docs/PROJECT_REPORT.md`.
- The successful synthetic demo evidence and `TESTING_SUMMARY.md`.
- An honest limitations/security section. Hosted CI is blocked by the account billing lock; local results must not be presented as hosted CI passes.

After committing the final source, run `./demo.sh` and `python3 scripts/package-submission.py`. The package contains committed source for review, the report draft, guide, validation/security records, a limited successful demo record and SHA-256 checksums. It rejects a dirty checkout or evidence from a different commit.

Never submit `.runtime/` wholesale, `.git/`, keys, CA/bootstrap logs, credentials, raw database dumps or real citizen documents. The packaging script includes tracked source and a validated, limited demo-evidence record only.

## Remaining production priorities

1. Remediate residual Fabric/IPFS findings and pass the complete release gate.
2. Have the account owner resolve GitHub billing, then run all required CI checks.
3. Inventory and revoke externally used historical credentials; coordinate old clone/cache cleanup.
4. Provision real identity proofing, PKCE login, domain/TLS, managed secrets/KMS and monitored infrastructure.
5. Establish off-site backups and prove full Fabric ledger restoration and resilient failover.
6. Review and merge the draft PR only after required gates pass.
