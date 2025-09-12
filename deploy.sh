#!/bin/bash

# Government Document Registry - Production Deployment Script

set -e

echo "🚀 Starting Government Document Registry Deployment..."

# Check prerequisites
echo "📋 Checking prerequisites..."
command -v docker >/dev/null 2>&1 || { echo "❌ Docker is required but not installed. Aborting." >&2; exit 1; }
command -v docker-compose >/dev/null 2>&1 || { echo "❌ Docker Compose is required but not installed. Aborting." >&2; exit 1; }

# Create necessary directories
echo "📁 Creating directories..."
mkdir -p logs
mkdir -p data/ipfs

# Build and start services
echo "🔨 Building and starting services..."
docker-compose down --remove-orphans
docker-compose build --no-cache
docker-compose up -d

# Wait for services to be ready
echo "⏳ Waiting for services to start..."
sleep 30

# Check service health
echo "🏥 Checking service health..."
echo "Fabric Network:"
docker-compose ps

echo "API Health:"
curl -s http://localhost:8081/actuator/health | jq . || echo "API not ready yet"

echo "IPFS Health:"
curl -s -X POST http://localhost:5001/api/v0/version || echo "IPFS not ready yet"

echo "✅ Deployment complete!"
echo "🌐 API available at: http://localhost:8081"
echo "📊 Health check: http://localhost:8081/actuator/health"
echo "📁 IPFS gateway: http://localhost:8080"
echo "📝 Logs: tail -f logs/application.log"
