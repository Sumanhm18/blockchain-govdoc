# HTTP API

Base URL in development: `http://127.0.0.1:18081`. Every `/api/**` request requires authentication. Development uses generated HTTP Basic accounts over loopback. Production profile requires HTTPS and an external JWT issuer. Roles are explicit; an issuer account is not implicitly a reader or revoker unless it has those roles.

| Method and path | Role | Input / result |
|---|---|---|
| POST `/api/documents` | ISSUER | JSON metadata registration; no file storage |
| POST `/api/documents/upload` | ISSUER | multipart `file`, `docId`; optional `issuer`, `subject`, `type`, `ownerSubject` |
| GET `/api/documents/{id}` | READER or owning CITIZEN | Document record |
| POST `/api/documents/{id}/verify` | READER or owning CITIZEN | JSON `{ "hash": "sha256:<64 lowercase hex>" }` |
| POST `/api/documents/{id}/verify-file` | READER or owning CITIZEN | multipart `file`; digest computed by API |
| POST `/api/documents/{id}/revoke` | REVOKER | JSON `{ "reason": "nonempty reason" }` |
| GET `/api/documents/{id}/file` | READER or owning CITIZEN | Decrypted verified attachment; revoked records rejected |
| GET `/api/documents/{id}/history` | READER | `{docId, events, truncated}` |
| GET `/api/documents/{id}/registration` | ISSUER | Local upload state, attempts, safe code and timestamp |
| GET `/actuator/health/readiness` | Public | Real dependency readiness, no internal detail |
| GET `/actuator/health/liveness` | Public | Application liveness |
| GET `/actuator/metrics` | OPERATOR | Application metrics |

Metadata registration retains the original request shape:

```json
{
  "docId": "GOV-2026-001",
  "hash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "issuer": "Org1",
  "subject": "opaque-reference",
  "metadataJson": "{\"type\":\"certificate\"}"
}
```

`issuer` may be omitted/empty and is derived from configured department. A conflicting issuer is forbidden. `metadataJson` must contain one JSON object, at most 16,384 characters; duplicate keys, trailing JSON and reserved upload fields (`registrationId`, `encryption`, `ipfsCid`) are rejected. Subject length is at most 128. Upload type length is at most 64; original filenames are ignored. Revoke reason length is 1–500 with nonblank content. Use nonsensitive references in all ledger metadata.

Upload permits PDF, PNG or JPEG signatures and a 10 MiB maximum. Signature checks do not prove a file is valid, safe or malware-free. Verification accepts arbitrary nonempty file bytes up to the same limit. A supplied content-type does not override the upload signature check.

Successful operations retain HTTP 200 and JSON object responses. Verification example:

```json
{"docId":"GOV-2026-001","matches":true,"revoked":false,"revocationReason":"","valid":true,"status":"VALID"}
```

Other verification statuses are `TAMPERED_OR_MISMATCH` and `REVOKED`. A revoked record has `valid:false` even when `matches:true`. Missing records produce 404 `NOT_FOUND`.

Errors use `{ "code": "...", "message": "..." }` without raw dependency diagnostics. Typical statuses: 400 validation, 401 unauthenticated, 403 forbidden, 404 missing, 409 duplicate/revoked/unsupported legacy storage, 413 file too large, 429 rate limited, 502 integrity failure and 503 dependency/capacity unavailable. Unsupported methods/media types preserve their HTTP status.

HTTP **202** `REGISTRATION_PENDING` means the upload is durably staged, but confirmation is not yet established. Poll `/{id}/registration`, then fetch the document after `CONFIRMED`. States: `STAGING`, `PINNED`, `SUBMITTING`, `CONFIRMED`, `REJECTED`. Retry the same ID, file and fields to resume. Different content or metadata for the same ID is a conflict. Do not create a different ID merely because a request timed out.

Compatibility changes are intentional security boundaries: authentication is required; issuer spoofing and unauthorized mutation are rejected; legacy identities without department attributes cannot write; a revocation reason is mandatory. The chaincode retains version-1 records, but REST read/verification requires recorded department and issuer MSP provenance; ambiguous legacy records and plaintext-storage downloads require reviewed migration. Invalid old IDs need a separately reviewed compatibility migration, not relaxed global validation.

Citizen access requires an issuer-assigned `ownerSubject` at registration/upload; the API stores an issuer-scoped hash, checks the JWT subject and hides inaccessible records behind 404. See [the ownership policy](DEPLOYMENT_VALIDATION.md). Legacy records without a verified issuing MSP are denied by the departmental REST access policy even when chaincode can still query them.
