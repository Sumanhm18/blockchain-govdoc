# Published history cleanup — 11 October 2026

The owner-authorized cleanup was published atomically to `main` and `harden-document-registry`, using exact force-with-lease guards for both previously observed remote commits. The feature branch's source tree was checked byte-for-byte through its Git tree hash and remained unchanged by filtering.

| Branch | Before rewrite | Immediately after rewrite |
|---|---|---|
| main | 6553d54fd1143f84609bcca5d30671700dce177a | 056c72e9f16fce3a8ea6db3884c8dfb520cfd8be |
| harden-document-registry | 52bf8d4 (citizen access and operational validation commit) | 2d1e50d35b24c3ec521f1d1d8bb6cc8a2c0796de |

The filter removed tracked dependency/build artifacts, generated networks, wallets, uploads and private-key file patterns from reachable history. The filtered mirror's limited private-key-marker scan examined 142 blobs and found zero matches. This does not prove complete sensitive-data removal or revoke any credentials. The local working branches were aligned to the sanitized remote history without changing current files.

## Collaborator action

Use a fresh clone. Preserve any unpublished work privately, inspect it for credentials/documents and selectively reapply reviewed changes. Do not merge or push old branches into the cleaned repository; that can restore the exposed history. No messages were sent to collaborators.

A restricted, ignored local rollback bundle was retained at `.runtime/history-before-rewrite.bundle` with mode 0600. It contains the old exposed history and must be treated as sensitive incident evidence, not uploaded or used as an ordinary backup. The operator must decide its retention/disposal under their incident policy. Existing local reflogs/mirrors may also retain the old objects.

## Still required outside the repository

Inventory every environment that used the old Fabric identities or CA roots. Replace affected identities/trust roots and publish CRLs through that network's governance. The successful disposable-identity revocation drill applies only to the fresh local sample network; no unknown external deployment was revoked.

GitHub cached commits, pull-request refs, forks and third-party copies are outside this branch rewrite. An administrator must request hosting-provider sensitive-data removal and coordinate fork cleanup. No support request was sent. Suggested request scope: repository `Sumanhm18/blockchain-govdoc`, exposed Fabric key/wallet/generated identity and uploaded-document history, rewritten branch refs above, and retained PR/cached commit references. Provide the provider's requested incident details privately; never attach credentials to a public issue.
