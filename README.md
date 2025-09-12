# Hyperledger Fabric: Government Document Registry

A minimal Hyperledger Fabric test-network setup to register, verify, and revoke government documents on-chain using Node.js chaincode. Uses the official `fabric-samples/test-network` under the hood and deploys custom chaincode from this workspace.

## Prerequisites
- Docker Desktop (8+ GB RAM for Docker recommended)
- Node.js 18+
- curl, tar, jq

## Quick Start
```bash
cd /Users/sumanhm/Downloads/fabric

# 1) Download Fabric binaries, docker images, and samples (one-time)
./scripts/bootstrap.sh

# 2) Start the network and create the channel
./scripts/network.sh up
./scripts/network.sh createChannel

# 3) Deploy the document registry chaincode
./scripts/network.sh deployCC

# 4) Run the sample client
cd application/client
npm install
node index.js
```

## Chaincode: `docregistry`
- registerDocument(docId, hash, issuer, subject, metadataJson)
- getDocument(docId)
- verifyDocument(docId, hash)
- revokeDocument(docId, reason)

Example document model:
```json
{
  "docType": "document",
  "docId": "<string>",
  "hash": "<sha256 or other>",
  "issuer": "<ministry/agency>",
  "subject": "<citizen/company>",
  "metadata": {"...": "..."},
  "revoked": false,
  "revocationReason": ""
}
```

## Useful
```bash
# Stop the network
./scripts/network.sh down

# Remove test-network artifacts from fabric-samples
./scripts/network.sh clean
```

## Notes
- Binaries and samples will be placed under `./fabric-bin` and `./fabric-samples`.
- The connection profile used by the client will be copied to `./application/gateway/connection-org1.json` after `up`.
- This is a dev/test setup; production needs multiple orgs/orderers, proper CAs, and secure ops.
