# Security scan record — 11 October 2026

Trivy 0.75.0 was verified against its published release checksum and used for the source dependency graph and API/Keycloak operating-system packages. JSON reports are retained under ignored `.runtime/trivy-*.json`; they are point-in-time evidence, not a guarantee of vulnerability absence. No finding is suppressed by an ignore file.

## Application dependencies

Updated Jackson to 2.21.7, Tomcat to 10.1.60 and Log4j to 2.25.5. All 41 Java tests pass with these versions. The rescanned source graph retains two findings against Spring MVC 6.2.19:

- [CVE-2026-47884](https://spring.io/security/cve-2026-47884/): Spring rates this medium and describes an XsltView/view-rendering prerequisite. This application uses REST controllers and has no XsltView or view-name mapping.
- [CVE-2026-47890](https://spring.io/security/cve-2026-47890/): Spring rates this low and describes SSE view fragments containing attacker-controlled data. This application does not stream SSE view fragments.

Trivy classifies both as critical. Our applicability assessment is that their documented prerequisites are absent in the current application. HTTP regression checks cover unmapped view paths and rejection of SSE content negotiation, but cannot prove every possible exploit absent. Preserve the findings for release review. Spring lists the 6.2.20 fixes as enterprise-only and 7.0.9 as open source. A supported framework migration or licensed patch remains a release decision; replacing Spring 6 with Spring 7 underneath Boot 3 is not a compatible patch.

## Container coverage and limits

The initial API OS scan found 37 package findings: 1 high, 15 medium and 21 low. The high finding was CVE-2026-84782 in `libssl3` 3.0.2-0ubuntu1.29, with a fix listed in 3.0.2-0ubuntu1.30. The Dockerfile explicitly installs that reviewed version on the pinned base; package indexes are removed from the resulting image. The rebuilt image rescanned with 31 remaining OS package findings (16 low, 15 medium). Rebuild and rescan whenever package pins or the base digest change.

The pinned Keycloak 26.8.0 base OS scan reports 64 package findings: 6 high, 29 medium and 29 low. The high findings are CVE-2026-103111, CVE-2026-86145 and CVE-2026-89161, each reported against both `pcre2` and `pcre2-syntax` 10.40-6.el9. The scanner lists no fixed version. These remain unresolved and require vendor tracking and exposure review before public deployment.

Container scans are OS-only. A full Keycloak Java scan was stopped during its large, slow Java-index download; its Java dependency coverage is incomplete. The API source scan covers the Maven graph, but does not replace a complete binary/image scan. A subsequent [runtime OS triage and automated gate](SECURITY_GATE.md) adds Fabric and PostgreSQL findings. IPFS returned no OS targets. Full binary/library, host and external-infrastructure coverage remains incomplete. No claim of a clean overall software supply chain is made.

## Reproduction

Install and independently verify Trivy, then scan the source while excluding generated runtime/vendor/build directories. Scan the actual built API and the pinned Keycloak image with `trivy image --pkg-types os --scanners vuln`. Keep scanner databases current and save JSON results with image digests. Run the broader Java/image/infrastructure scans before a production release. npm audit of the locked chaincode graph reported no advisories at this validation point.

Use the committed [security gate](SECURITY_GATE.md) for reproducible scans and the expanded runtime backlog.

The priority Fabric Node/peer/orderer/CA OS fixes are now applied; see [Runtime patches](RUNTIME_PATCHES.md) for before/after evidence and validation.
