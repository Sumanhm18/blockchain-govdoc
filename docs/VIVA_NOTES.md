# Presentation and viva notes

Use these notes with the seven-slide presentation and the live CLI. Personal report details are deferred. Rehearse the explanations in your own words.

## Opening explanation

“Our project records authorized document issuance on a permissioned blockchain. It stores encrypted document files in IPFS and a digest plus limited metadata in Fabric. A reader can check whether a file matches the registered bytes and whether the issuer has revoked it. Department access and citizen ownership restrict retrieval. We also implemented durable recovery for interrupted uploads.”

## Essential answers

**Why use blockchain instead of a database?**
Fabric lets known organizations share a ledger and require endorsements before writes. A conventional database is simpler when one organization controls all trust and operations. This project explores the consortium case. It does not prove blockchain is necessary for every document registry.

**Why Fabric rather than a public chain?**
The prototype uses known members, certificates and endorsement policies. Both organizations endorse writes. There is no cryptocurrency or mining in this implementation.

**What is the difference between hashing and encryption?**
SHA-256 produces an integrity digest of the exact original bytes. AES-256-GCM protects confidentiality and detects ciphertext modification. Decryption requires the key. The IPFS CID identifies stored ciphertext and differs from the plaintext digest.

**How do you detect a forged document?**
The file-verification endpoint hashes the submitted bytes and compares them with the ledger digest. Changed bytes give TAMPERED_OR_MISMATCH. A matching, unrevoked record gives VALID. This detects changes relative to the issuance, not false statements made by an authorized issuer.

**What does revocation do?**
It changes ledger status and blocks downloads through this API. Verification reports REVOKED even for matching bytes. Revocation cannot erase copies already downloaded, and the ledger keeps history.

**Who can access documents?**
Issuers register and revoke within their authority. Department readers access their department's records. Citizens access documents assigned to their validated OIDC issuer/subject hash. Citizens cannot issue, revoke or access history. Authorized channel members can still read ledger metadata at the Fabric layer.

**Is authorization only in the API?**
The API validates accounts or JWTs and enforces role, department and ownership rules. Chaincode independently checks issuer certificate attributes and MSP authority for mutations. The API uses a department service identity, not one Fabric certificate per citizen.

**What if storage or the network fails?**
A durable H2 journal keeps encrypted upload state. A pending operation returns HTTP 202. Recovery queries the ledger before resubmitting to avoid duplicate registration after uncertain outcomes. IPFS and Fabric do not share an atomic transaction.

**What did you test?**
The dated record contains 41 Java tests, 15 contract tests, 16 live API checks, two direct Fabric authorization checks, 37 HTTPS/OIDC checks and a 14-check presentation walkthrough. Recovery and encrypted application/IPFS restore drills passed. These suites overlap in coverage, so their sum is not a count of distinct security guarantees.

**What is your contribution?**
Integrating the document lifecycle with encrypted storage, department/citizen access, revocation-aware verification, chaincode authorization and durable recovery, then validating those behaviors. Fabric, IPFS and the cryptographic algorithms are existing technologies.

**What remains unfinished?**
Residual dependency findings block release. GitHub hosted CI cannot start because of the account billing lock. Real citizen proofing, managed keys, off-site backups, resilient infrastructure and full Fabric ledger restoration remain. A frontend and QR verification are future work.

**Is the system highly available or production-ready?**
No. This local topology has one orderer and a single API journal. Losing a required endorser prevents writes. Recovery tests demonstrate specific fault handling, not uninterrupted service or production certification.

## Six-minute delivery plan

Spend about two minutes on the problem, data placement and permissions. Use about three minutes for `./demo.sh --pause`. Close with the measured results and limitations. Keep the detailed questions above for discussion afterward.

## Presentation-day commands

Start Docker Desktop, then use a terminal:

```sh
cd /Users/sumanhm/Documents/Codex/blockchain-govdoc
python3 scripts/resume-demo.py
./demo.sh --pause
```

The expected ending is `PASS: 14 checks`. Each run creates and revokes a new synthetic record. A pause waits for Enter. Do not show credential files or private logs on the projector.

## If something fails

- Docker unavailable: open Docker Desktop, wait for its engine, then retry the resume command.
- Readiness failure: allow dependencies to recover and rerun resume. Do not delete volumes, regenerate keys or rebuild the network as a last-minute workaround.
- HTTP 202: the upload remains pending until confirmed. Let the supplied walkthrough poll; do not describe pending state as a committed issuance.
- Walkthrough failure: stop the live demonstration and use the saved, dated evidence to explain the previous rehearsal. Say explicitly that it is recorded evidence. Do not relabel it as a current pass.
- Fresh computer: the source archive alone does not include prepared images, submodule checkout, identities or data. Follow the clone/setup instructions in SUBMISSION.md well before the demonstration.

Sources: PROJECT_REPORT.md, SUBMISSION.md, ../TESTING_SUMMARY.md and SECURITY_GATE.md. The statements describe the local prototype and its October 11–12 validation record.
