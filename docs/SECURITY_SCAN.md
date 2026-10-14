# Security scan record — 12 October 2026

Trivy 0.75.0 was checked against its published release checksum. Current source and full OS/library image results are recorded in [Security gate](SECURITY_GATE.md). Restricted raw reports remain under ignored `.runtime/security/`; these are point-in-time package findings, not proof of exploitability or vulnerability absence. No finding is suppressed by an ignore file.

## API framework remediation

The API now uses Spring Boot 4.1.1 / Spring Framework 7.0.9 on Java 17, with Jackson 3.1.7, the Jackson 2 compatibility BOM at 2.21.7, Tomcat 11.0.26 and Log4j 2.25.5. The upgrade includes Boot's modular MVC/security starters, Jackson 3 imports and duplicate-field parsing, the moved health API and the new HTTPS redirect configuration. All 41 Java tests pass; HTTPS test requests set both a secure connection and an HTTPS URL scheme.

The patched API Maven graph reports no vulnerabilities. The broader source scan also covers runtime dependency locks; its findings are recorded separately in the gate table. Boot's default Jackson 3.1.5 and Tomcat 11.0.24 had additional findings, so reviewed compatible patch overrides are explicit in the POM. The executable API image is scanned separately; a clean source graph alone is insufficient.

The earlier Spring MVC 6.2.19 findings, [CVE-2026-47884](https://spring.io/security/cve-2026-47884/) and [CVE-2026-47890](https://spring.io/security/cve-2026-47890/), are addressed through the framework upgrade to the listed open-source fixed line. They are not waived based on the application's lack of view/SSE rendering. Existing negative HTTP tests remain.

## Runtime remediation

- API: the pinned Jammy base receives `libssl3=3.0.2-0ubuntu1.30`.
- Keycloak: the pinned 26.8.0 distribution runs on the pinned Temurin 21 Alpine runtime as UID 1000. Full OS/Java scanning reports only two medium findings.
- PostgreSQL: major version 17 is retained, moving from Debian to a pinned Alpine image through an encrypted logical backup/restore into a separate volume. The original volume is retained; identity/client/credential fingerprints and OIDC checks pass. The derived runtime starts as UID 70 and removes the old `gosu` helper, whose bundled Go code contained one critical and 24 high findings. See [Identity migration](IDENTITY_MIGRATION.md).
- Fabric: peers/orderer/build environment move to 2.5.16 and CAs to 1.5.22, with OS patches still applied. Node uses 22.23.3 on Alpine 3.24.2 and npm 12.2.0 with the reviewed locked dependency rebuild described in `deploy/npm-runtime/README.md`. See [Runtime patches](RUNTIME_PATCHES.md).
- IPFS: direct non-root startup permits removal of the obsolete `/sbin/gosu` binary and its bundled vulnerable Go runtime. A source-pinned Kubo 0.43.1 rebuild with Go 1.26.9, x/net 0.60.0 and gRPC-Go 1.83.2 removes the remaining 13 high findings. GO-2026-5932 (x/crypto, unknown severity, no listed fix) remains blocking in both the binary and source lock. See [IPFS rebuild](IPFS_RUNTIME.md).

## Coverage and remaining work

The Java vulnerability index download completed with its OCI layer SHA-256 verified. Full scans cover OS packages and supported language libraries in each built/pinned runtime image. This does not scan every external service, host, kernel, network configuration or deployed extension. Residual high and unknown findings in the Fabric/IPFS supply chain still require remediation and vendor tracking. A passing functional test is not a vulnerability exemption.

Use `./scripts/security-scan.sh --build` to rebuild and rescan. The release gate remains fail-closed on findings, partial coverage and errors. npm audit of the locked application chaincode graph reports no advisories; the Node runtime's npm dependency graph is covered separately by its full image scan (two medium findings).
