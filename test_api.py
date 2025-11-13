#!/usr/bin/env python3
import requests
import json

# Login
print("Logging in...")
login_response = requests.post(
    "http://localhost:8080/api/auth/login",
    json={"username": "admin", "password": "admin123"}
)

if login_response.status_code != 200:
    print(f"Login failed: {login_response.status_code}")
    print(login_response.text)
    exit(1)

token = login_response.json()["accessToken"]
print(f"Token: {token[:50]}...")
print()

# Test data sources endpoint
print("Testing /api/data-sources endpoint...")
headers = {"Authorization": f"Bearer {token}"}
response = requests.get("http://localhost:8080/api/data-sources", headers=headers)

print(f"Status: {response.status_code}")
print(f"Response: {json.dumps(response.json(), indent=2)}")
