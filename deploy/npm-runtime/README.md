# Patched npm runtime dependencies

The Fabric build/runtime image retains the npm 12.2.0 CLI code from the official npm package but installs a separately reviewed, locked production dependency graph. The package manifest is based on that distribution, with development/workspace/bundling fields removed and compatible overrides for `brace-expansion` 5.0.11, `http-cache-semantics` 4.3.0 and `undici` 6.28.1. All resolved lockfile artifacts use registry.npmjs.org and SHA-512 integrity values.

A separate build stage performs `npm ci --omit=dev --ignore-scripts`; it does not modify the bootstrap npm while that process is running. The final image replaces the original bundled npm directory and preserves npm's CLI launch paths, upstream license and distribution files. `config/node-runtime.json` pins the CLI version, and the Dockerfile rejects a manifest/version mismatch.

This is a locally maintained dependency rebuild, not an unmodified upstream npm distribution. It requires full image scanning and Fabric chaincode installation/deployment tests after changes. Do not update this lockfile just to hide scanner findings; review dependency compatibility and retain the complete scan results. Do not automatically edit nested vendor files in a live container.

The candidate full scan on October 11, 2026 reported two medium findings, with no high/critical/unknown findings; prior npm-bundled dependencies reported four high findings. Current complete-stack results are in `docs/SECURITY_GATE.md`.
