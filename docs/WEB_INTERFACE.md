# Local web interface

Open http://127.0.0.1:18081/ after starting Docker and running `python3 scripts/resume-demo.py`. Spring Boot serves the interface and API from the same origin. No frontend build, CDN or third-party browser scripts are required.

Run `python3 scripts/create-demo-files.py` to create two synthetic PDFs under `.runtime/submission/demo-files/`. Register `original.pdf`, then verify it and `modified.pdf` against the same document ID. Both display synthetic content, with different SHA-256 digests.

## Demonstration

1. Connect with an assigned development account. The generated username/password mapping is in private `.runtime/secrets/credentials.json`; open it privately, never on the projector. The `issuer` account supports the full demo, while `reader` cannot issue or revoke.
2. Choose **Issue a document**, enter a unique uppercase/digit/hyphen ID and select a synthetic PDF, PNG or JPEG up to 10 MiB. Use nonsensitive references for metadata.
3. Choose **Encrypt & register**. A pending response is not success. Use **Check registration status** until confirmed. Keep the same ID and content for retries.
4. Under **Find & verify**, retrieve that ID and download the file. Verify the original, then a modified copy, to demonstrate VALID and TAMPERED_OR_MISMATCH.
5. Enter a revocation reason and check the confirmation box. Revoke, then verify the original again to demonstrate REVOKED. Downloads become unavailable and history remains.
6. Sign out. Connect as `reader` to demonstrate restricted controls. The API enforces authorization even if someone modifies the page.

Citizens can use the staging interface on its existing HTTPS origin with a valid, externally obtained bearer access token. The token entry is a staging aid, not an authorization-code/PKCE login implementation. Only the issuer sees the optional citizen subject assignment field in token mode. Ownership and role checks remain on the server. Staging requires rebuilding its API image after source changes.

## Credential and data handling

Credentials stay in page memory, never localStorage/sessionStorage or URLs. Sign-out, reload and page exit clear the page's authentication state. Requests omit ambient browser credentials and send the explicit Authorization header only to the same-origin API. The server describes granted roles at authenticated `GET /api/session`; it returns no tokens, passwords or identity claims.

The content security policy permits only same-origin scripts/styles/connections and denies embedding. The interface renders record values with textContent, not HTML. Spring's existing no-store response headers remain. File downloads are explicit user actions and remain on the user's device after sign-out. Ledger metadata is visible to authorized channel members.

## Validation

`node scripts/test-web.cjs` requires an available Playwright installation with Chromium, a running prepared stack, and the generated local test accounts. Set NODE_PATH to an existing Playwright installation if needed. PLAYWRIGHT_CHROMIUM_EXECUTABLE can select an existing compatible Chromium binary. The test reads credentials in memory and deliberately avoids traces and credential screenshots.

It creates a synthetic ledger record and exercises live issuance, exact download, original/modified/revoked verification, history, reader controls, missing records, safe metadata rendering and sign-out/reload behavior. It also checks the mobile layout. Separately intercepted responses check pending UI rendering and prevent delayed verification results from appearing on a different record. The operational recovery suite covers real IPFS outage and API restart behavior.

The interface provides individual-ID operations. Global record listing, QR codes, real citizen onboarding and production login remain outside the submitted local prototype.
