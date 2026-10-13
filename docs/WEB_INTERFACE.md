# Local web interface

Open http://127.0.0.1:18081/ after starting Docker and running `python3 scripts/resume-demo.py`. Spring Boot serves the interface and API from the same origin. No frontend build or CDN is required to run it. Pinned Keycloak and QR libraries are served locally.

Run `python3 scripts/create-demo-files.py` to create two synthetic PDFs under `.runtime/submission/demo-files/`. Register `original.pdf`, then verify it and `modified.pdf` against the same document ID. Both display synthetic content, with different SHA-256 digests.

## Demonstration

1. Connect with an assigned development account. The generated username/password mapping is in private `.runtime/secrets/credentials.json`; open it privately, never on the projector. The `issuer` account supports the full demo, while `reader` cannot issue or revoke.
2. Choose **Issue a document**, enter a unique uppercase/digit/hyphen ID and select a synthetic PDF, PNG or JPEG up to 10 MiB. Use nonsensitive references for metadata.
3. Choose **Encrypt & register**. A pending response is not success. The page checks pending registrations automatically with at most 15 follow-up requests, then pauses. Use **Track a registration** to check again by ID, including after a reload or interrupted request. Keep the same ID and content for retries.
4. After confirmation, choose **Open registered document**, or use **Find & verify** to enter its ID, then download the file. Verify the original, then a modified copy, to demonstrate VALID and TAMPERED_OR_MISMATCH.
5. Enter a revocation reason and check the confirmation box. Revoke, then verify the original again to demonstrate REVOKED. Downloads become unavailable and history remains.
6. Sign out. Connect as `reader` to demonstrate restricted controls. The API enforces authorization even if someone modifies the page.

Citizens use the Keycloak sign-in button in HTTPS staging. See the PKCE setup below. Ownership and role checks remain on the server.

## Credential and data handling

Passwords and access tokens stay in page memory. The redirect flow temporarily stores callback state and an optional record ID as described below. Sign-out, reload and page exit clear the page's authentication state. Requests omit ambient browser credentials and send the explicit Authorization header only to the same-origin API. The server describes granted roles at authenticated `GET /api/session`; it returns no tokens or passwords. Authenticated JWT citizens also receive their own subject identifier as `citizenReference`; staff do not receive this field.

A rejected/expired authenticated session clears the workspace and asks you to connect again. Requests time out after 20 seconds. For an interrupted upload, check the same ID before retrying because the server may still complete it. Automatic registration checks stop on sign-out, a changed tracking ID, errors or a terminal state.

The content security policy permits only same-origin scripts/styles/connections and denies embedding. The interface renders record values with textContent, not HTML. Spring's existing no-store response headers remain. File downloads are explicit user actions and remain on the user's device after sign-out. Ledger metadata is visible to authorized channel members.

## Validation

`node scripts/test-web.cjs` requires the locked browser tools, Chromium, a running prepared stack, and generated local test accounts. Run `npm ci --prefix application/web-assets --ignore-scripts`, then `./application/web-assets/node_modules/.bin/playwright install chromium` to prepare the test tools. PLAYWRIGHT_CHROMIUM_EXECUTABLE can select an existing compatible Chromium binary. The test reads credentials in memory and deliberately avoids traces and credential screenshots.

It creates a synthetic ledger record and exercises live issuance, exact download, original/modified/revoked verification, history, reader controls, missing records, safe metadata rendering and sign-out/reload behavior. It also checks the mobile layout. Separately intercepted responses check automatic pending status, expired credentials, request timeouts and prevention of delayed verification results appearing on a different record. The operational recovery suite covers real IPFS outage and API restart behavior.

The interface supports individual-ID operations and access-controlled document browsing. Real citizen onboarding and production infrastructure remain outside the submitted local prototype.

## Documents dashboard and history

Readers and citizens can browse records through **Documents**, filter by ID prefix or active/revoked state, and open a result. The server derives citizen ownership from the validated token, not a browser field. Chaincode filters against the service department/MSP and owner hash, and the API checks each result again.

Each page returns up to 20 records after scanning at most 250 ledger entries. A page may be empty but still offer **Next page**. Results use document-ID order, not a global newest-first index. Encrypted, authenticated cursors expire after 15 minutes and are bound to the query, status and citizen ownership scope. Restarting the API preserves cursors while the encryption key remains available. The **Recently opened** section keeps up to six IDs in page memory and clears on sign-out.

**View history** displays issuance/revocation events with dates and transaction references. Technical JSON remains available under a collapsed disclosure. The interface reports the existing 100-event limit if the history is truncated.

## Citizen sign-in with PKCE

The staging browser client uses Keycloak's authorization-code flow with S256 PKCE, exact redirect/origin configuration, and no client secret. It does not enable password grants or implicit flow. The separate synthetic API test client remains for the existing automated tests.

After `./scripts/staging.sh up`, the startup script configures the dedicated browser client idempotently. Existing installations can run `python3 scripts/configure-browser-login.py`. This modifies only `govdoc-browser` in the isolated `govdoc-lab` realm.

On this prepared macOS machine, run `python3 scripts/open-citizen-browser.py`. It opens a separate Google Chrome profile at `https://localhost:18444/`, maps the local identity hostname only inside that browser, and trusts the exact public keys from the two generated staging certificates. It does not change the system hosts file or trust store, or disable certificate checks globally. Close this browser after the demo. Its profile and IdP cookies are private `.runtime/` state.

Choose **Sign in with government identity**, enter the synthetic citizen credentials on Keycloak, and return to the app. Use the existing private staging credentials file locally; never display it on the projector. Sign-out clears app state and ends the identity-provider session. For a real deployment, replace the lab hostname/certificates and localhost redirect/origin with a trusted, accessible HTTPS deployment. The launcher and local trust pins are demonstration setup, not production identity proofing.

The app keeps tokens in memory. The adapter temporarily stores callback state/nonce/PKCE verifier, and the app temporarily stores an optional return-record fragment during redirect. Passwords and access/refresh tokens are not persisted by the app. Reloading requires reconnecting. The official adapter is pinned and served locally under `static/vendor`; update it through the locked `application/web-assets` package and `scripts/sync-web-assets.py`.

## Verification links and QR codes

Open an authorized document and choose **Verification link & QR**. Copy its link or download the QR PNG. The code contains only the same-origin page URL and a document-ID fragment, never tokens, file content or a verification verdict. A recipient must authenticate and pass the existing access checks. They still need to submit the actual file to verify its integrity. Revocation is checked when reading/verifying the record.

Localhost QR links work only on the computer serving the app. Cross-device scanning requires an accessible deployment with trusted HTTPS; this change does not publish the local registry to the internet.

`node scripts/test-browser-oidc.cjs` validates real PKCE sign-in, callback cleanup, owned search, another citizen's denial when following a record link, and provider logout against local staging. It uses only the generated fixture public-key pins for browser TLS. `scripts/test-web.cjs` additionally decodes the rendered QR pixels with an independent decoder and checks the exact expected link.

### Issue a document to a citizen

1. In the HTTPS citizen browser, sign in as a citizen and copy **Your citizen reference**.
2. Sign out, then sign in as an issuer. Open **Issue a document**, paste that reference into **Citizen reference**, select the file and register it.
3. Sign out and return as the citizen. Find the document in **Documents**, download it and verify the file. Another citizen cannot read this record.

The reference is an account identifier, not a password or independent proof of identity. It is displayed only for the currently authenticated citizen and cleared on sign-out. Leaving the assignment blank grants department access only. Development Basic accounts do not support citizen assignment; use the prepared HTTPS identity-provider flow.

### Start the prepared project

On macOS, double-click `Open GovDoc.command` in the repository folder. Choose **1** for department development accounts or **2** for HTTPS citizen/issuer sign-in. Docker must be running. The citizen choice checks ownership of all eleven prepared containers before starting any stopped service, then checks both the identity provider and API. It opens the isolated Chrome profile with the existing local certificate pins. It does not generate passwords, rebuild images or reset data.

From a terminal use `python3 scripts/start-project.py` or `python3 scripts/start-project.py --citizen`. Add `--no-open` to resume/check services without opening a browser. Initial setup remains `./deploy.sh` and, for citizen mode, `./scripts/staging.sh up`; this launcher requires the prepared containers.
