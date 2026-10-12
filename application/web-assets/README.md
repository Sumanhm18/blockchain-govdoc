# Browser dependencies

The public UI serves vendored, pinned Keycloak JS 26.2.4 and qrcode-generator 2.0.4 without a CDN or runtime package download. `package-lock.json` records npm SHA-512 archive integrity. Run `npm ci --prefix application/web-assets --ignore-scripts` then `python3 scripts/sync-web-assets.py` to refresh the copies deliberately. Review and retain the included upstream license notices. The QR MIT notice is from the upstream repository LICENSE and applies to the copyright header in the distributed source.

Keycloak's official adapter implements authorization-code/PKCE S256 with state and nonce handling. Tokens stay in memory. The adapter temporarily stores callback state and the PKCE verifier for the redirect. The app stores only an optional pending record fragment in sessionStorage while signing in.

References: https://www.keycloak.org/securing-apps/javascript-adapter and https://github.com/kazuhikoarase/qrcode-generator.
