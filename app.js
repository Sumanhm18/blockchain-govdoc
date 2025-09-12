'use strict';
process.on("uncaughtException", (err) => {
  console.error("❌ Uncaught Exception:", err);
});

process.on("unhandledRejection", (reason, promise) => {
  console.error("❌ Unhandled Rejection at:", promise, "reason:", reason);
});

const express = require('express');
const bodyParser = require('body-parser');
const cors = require('cors');
const { Gateway, Wallets } = require('fabric-network');
const path = require('path');
const fs = require('fs');

const app = express();
app.use(cors());
app.use(bodyParser.json());

// 🚨 Use a relative path to the connection profile
const ccpPath = path.resolve(
  __dirname,
  'organizations',
  'peerOrganizations',
  'org1.example.com',
  'connection-org1.json'
);
const ccp = JSON.parse(fs.readFileSync(ccpPath, 'utf8'));

// Utility: Get Contract
async function getContract() {
  const walletPath = path.join(process.cwd(), 'wallet');
  const wallet = await Wallets.newFileSystemWallet(walletPath);

  // 👉 Use appUser2 instead of admin
  const identityName = 'appUser2';
  const identity = await wallet.get(identityName);

  if (!identity) {
    throw new Error(`❌ Identity "${identityName}" not found in wallet. Run registerUser.js first.`);
  }

  const gateway = new Gateway();
  await gateway.connect(ccp, {
    wallet,
    identity: identityName,
    discovery: { enabled: false }
});

  const network = await gateway.getNetwork('mychannel');
  return network.getContract('document'); // chaincode name
}

// 🟢 Issue new document
app.post('/documents', async (req, res) => {
  try {
    const { docID, owner, issuer, docType, hash, issueDate } = req.body;
    const contract = await getContract();
    const result = await contract.submitTransaction(
      'IssueDocument',
      docID,
      owner,
      issuer,
      docType,
      hash,
      issueDate
    );
    res.json({ success: true, data: JSON.parse(result.toString()) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// 🟢 Get document by ID
app.get('/documents/:id', async (req, res) => {
  try {
    const contract = await getContract();
    const result = await contract.evaluateTransaction('ReadDocument', req.params.id);
    res.json(JSON.parse(result.toString()));
  } catch (error) {
    res.status(404).json({ error: error.message });
  }
});

// 🟢 Get all documents
app.get('/documents', async (req, res) => {
  try {
    const contract = await getContract();
    const result = await contract.evaluateTransaction('GetAllDocuments');
    res.json(JSON.parse(result.toString()));
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// 🟢 Revoke document
app.put('/documents/:id/revoke', async (req, res) => {
  try {
    const contract = await getContract();
    const result = await contract.submitTransaction('RevokeDocument', req.params.id);
    res.json({ success: true, data: JSON.parse(result.toString()) });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Start server
const PORT = 8080;
app.listen(PORT, () => {
  console.log(`✅ Gov Docs API running at http://localhost:${PORT}`);
});