# Project overview

The current implementation and trust boundaries are documented in [README](README.md) and [Architecture](docs/ARCHITECTURE.md). The original implementation combined a Spring API, JavaScript Fabric contract and IPFS but lacked enforced API/issuer authorization, encrypted storage and durable recovery. The hardening work addresses those gaps while retaining the primary architecture and routes.

See [API compatibility](docs/API.md), [security requirements](docs/SECURITY.md) and [validation evidence](TESTING_SUMMARY.md). Proposed research features must not be described as implemented.
