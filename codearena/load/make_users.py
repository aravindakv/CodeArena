#!/usr/bin/env python3
"""Creates N load-test users through the gateway and writes their access tokens to load/tokens.json.
The gateway limits login/register per IP, so this paces itself (about one user every 2 seconds)."""
import json
import sys
import time
from pathlib import Path

import requests

API = "http://localhost:9080/api/v1"
n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
tokens = []
for i in range(n):
    email, pw = f"load{i}@codearena.local", "load-test-password"
    while True:
        requests.post(f"{API}/auth/register", json={"email": email, "password": pw, "displayName": f"load_{i}"})
        r = requests.post(f"{API}/auth/login", json={"email": email, "password": pw})
        if r.status_code == 429:
            time.sleep(1.1)
            continue
        r.raise_for_status()
        tokens.append(r.json()["accessToken"])
        break
    print(f"\r{i + 1}/{n}", end="", flush=True)
    time.sleep(2.1)   # register + login = 2 requests; the per-IP limit refills 1 per second
out = Path(__file__).parent / "tokens.json"
out.write_text(json.dumps(tokens))
print(f"\nwrote {out} (tokens expire in 15 minutes; rerun before a long test)")