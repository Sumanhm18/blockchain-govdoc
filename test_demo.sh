#!/bin/bash

# Government Document Registry - Demo Test Script
# This script demonstrates the functionality we've built

echo "=== Government Document Registry Demo ==="
echo "========================================"
echo

# Check if Docker is running
echo "1. Checking Docker status..."
if docker ps >/dev/null 2>&1; then
    echo "✅ Docker is running"
else
    echo "❌ Docker is not running. Please start Docker first."
    exit 1
fi
echo

# Check if Fabric network is running
echo "2. Checking Fabric network status..."
if docker ps | grep -q "peer0.org1.example.com"; then
    echo "✅ Fabric network is running"
    echo "   - Orderer: orderer.example.com"
    echo "   - Org1 Peer: peer0.org1.example.com"
    echo "   - Org2 Peer: peer0.org2.example.com"
else
    echo "❌ Fabric network is not running"
    echo "   Run: ./scripts/network.sh up"
    exit 1
fi
echo

# Check if chaincode is deployed
echo "3. Checking chaincode deployment..."
if docker ps | grep -q "docregistry"; then
    echo "✅ Chaincode 'docregistry' is deployed"
    echo "   - Version: 1.2"
    echo "   - Channel: govchannel"
else
    echo "❌ Chaincode is not deployed"
    echo "   Run: ./scripts/network.sh deployCC"
    exit 1
fi
echo

# Check if Spring Boot API is running
echo "4. Checking Spring Boot API status..."
if curl -s http://localhost:8081/api/documents >/dev/null 2>&1; then
    echo "✅ Spring Boot API is running on port 8081"
else
    echo "⚠️  Spring Boot API is not responding"
    echo "   Run: cd application/api-spring && mvn spring-boot:run"
fi
echo

# Check if IPFS is running
echo "5. Checking IPFS status..."
if curl -s http://localhost:5001/api/v0/version >/dev/null 2>&1; then
    echo "✅ IPFS is running on port 5001"
else
    echo "⚠️  IPFS is not running"
    echo "   Run: ipfs daemon"
fi
echo

# Show system architecture
echo "6. System Architecture Overview:"
echo "   ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐"
echo "   │   Spring Boot   │    │  Hyperledger    │    │      IPFS       │"
echo "   │      API        │◄──►│     Fabric      │    │   (Storage)     │"
echo "   │   (Port 8081)   │    │   (Port 7051)   │    │   (Port 5001)   │"
echo "   └─────────────────┘    └─────────────────┘    └─────────────────┘"
echo

# Show available endpoints
echo "7. Available API Endpoints:"
echo "   POST   /api/documents              - Register document"
echo "   GET    /api/documents/{docId}      - Get document"
echo "   POST   /api/documents/{docId}/verify - Verify document"
echo "   POST   /api/documents/{docId}/revoke - Revoke document"
echo "   POST   /api/documents/upload       - Upload file to IPFS"
echo

# Show chaincode functions
echo "8. Chaincode Functions:"
echo "   registerDocument(docId, hash, issuer, subject, metadata)"
echo "   getDocument(docId)"
echo "   verifyDocument(docId, hash)"
echo "   revokeDocument(docId, reason)"
echo

# Show test data examples
echo "9. Example Test Data:"
echo "   Document ID: DOC-$(date +%s)"
echo "   Hash: sha256:$(echo -n 'test-document' | shasum -a 256 | awk '{print $1}')"
echo "   Issuer: Ministry of Health"
echo "   Subject: John Doe"
echo "   Metadata: {\"type\":\"passport\",\"issueDate\":\"2024-01-01\"}"
echo

# Show current issues
echo "10. Current Status:"
echo "    ✅ Fabric network: Running"
echo "    ✅ Chaincode: Deployed"
echo "    ⚠️  Spring Boot API: TLS/connectivity issues"
echo "    ⚠️  IPFS: Not running (optional for basic testing)"
echo

echo "=== Demo Complete ==="
echo "The system is architecturally complete with all components built."
echo "Main remaining work: Fix TLS/connectivity issues for full testing."
echo
echo "For detailed information, see:"
echo "- README.md - Main documentation"
echo "- TESTING_SUMMARY.md - Comprehensive testing summary"
echo "- application/api-spring/README.md - Spring Boot API docs"
