#!/usr/bin/env python3
"""Creates N load-test users through the gateway and writes their logins to load/users.json.
k6 logs them in itself at the start of each test (load/fest.js setup), so tokens are always fresh; access tokens
expire after 15 minutes. The gateway limits register/login per IP, so this paces itself (about one user a second).

    scripts/py.sh load/make_users.py 100
"""
import json
import sys
import time
from pathlib import Path

import requests

API = "http://localhost:9080/api/v1"
PASSWORD = "load-test-password"
n = int(sys.argv[1]) if len(sys.argv) > 1 else 100
users = []
for i in range(n):
    email = f"load{i}@codearena.local"
    while True:
        r = requests.post(f"{API}/auth/register", json={"email": email, "password": PASSWORD, "displayName": f"load_{i}"})
        if r.status_code == 429:          # rate limited: wait for the bucket to refill
            time.sleep(1.1)
            continue
        if r.status_code not in (200, 201, 409):   # 409 = already exists from an earlier run, which is fine
            sys.exit(f"\nregister {email} failed: {r.status_code} {r.text[:200]}")
        break
    users.append({"email": email, "password": PASSWORD})
    print(f"\r{i + 1}/{n}", end="", flush=True)
    time.sleep(1.05)   # the per-IP limit refills one request per second
out = Path(__file__).parent / "users.json"
out.write_text(json.dumps(users))
print(f"\nwrote {out}")
