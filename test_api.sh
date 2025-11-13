#!/bin/bash

# Login and get token
echo "Logging in..."
RESPONSE=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')

TOKEN=$(echo $RESPONSE | python3 -c "import json, sys; print(json.load(sys.stdin)['accessToken'])")

echo "Token: ${TOKEN:0:50}..."
echo ""

# Test data sources endpoint
echo "Testing /api/data-sources endpoint..."
curl -v -s "http://localhost:8080/api/data-sources" \
  -H "Authorization: Bearer $TOKEN" \
  2>&1 | grep -E "Authorization:|HTTP/|{" | head -10
