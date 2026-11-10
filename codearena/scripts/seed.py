
#!/usr/bin/env python3
"""
Seed the local stack with the original problems in seed/problems/<slug>/:
  problem.json   the problem (same JSON as the admin API's ProblemDoc)
  reference.py   solve(**inputs) -> expected output   (and optionally brute(**inputs) for cross-checks)
  gen.py         gen(rng, size) -> inputs dict, size in {"edge", "small", "large"}

For each problem: generate deterministic hidden tests, compute outputs with the reference solution,
check the statement's examples against the reference, upload tests to S3 and publish to DynamoDB.

    scripts/py.sh scripts/seed.py              # seed (skips problems that already exist)
    scripts/py.sh scripts/seed.py --check      # verify only: examples + reference vs brute force; no AWS needed
    scripts/py.sh scripts/seed.py --force      # re-upload tests and docs of existing problems
    scripts/py.sh scripts/seed.py --demo 250   # also add 250 demo problems to try pagination (100 per page)
"""
import argparse
import importlib.util
import json
import os
import random
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROBLEMS = ROOT / "seed" / "problems"
CASES = [("edge", 6), ("small", 20), ("large", 4)]


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def split_top_level(s: str):
    """Same rules as ExampleInputs.java: split on commas outside brackets and quotes."""
    out, depth, in_str, cur = [], 0, False, []
    for i, c in enumerate(s):
        if c == '"' and (i == 0 or s[i - 1] != "\\"):
            in_str = not in_str
        if not in_str:
            if c in "[{":
                depth += 1
            elif c in "]}":
                depth -= 1
            elif c == "," and depth == 0:
                out.append("".join(cur))
                cur = []
                continue
        cur.append(c)
    if "".join(cur).strip():
        out.append("".join(cur))
    return out


def example_to_input(text: str, params):
    parts = split_top_level(text)
    if len(parts) != len(params):
        raise ValueError(f"example needs {len(params)} values: {text}")
    values = {}
    for part, p in zip(parts, params):
        name, eq, value = part.partition("=")
        values[p["name"]] = json.loads(value.strip() if eq else part.strip())
    return values


def build_cases(slug: str, problem: dict, ref, gen_mod):
    rng = random.Random(slug)                         # deterministic per problem
    params = problem["signature"]["params"]
    inputs = [example_to_input(e["input"], params) for e in problem["examples"]]
    for size, count in CASES:
        inputs += [gen_mod.gen(rng, size) for _ in range(count)]
    cases = [{"input": i, "output": ref.solve(**json.loads(json.dumps(i)))} for i in inputs]
    for e, c in zip(problem["examples"], cases):
        if json.loads(e["output"]) != c["output"]:
            raise ValueError(f"{slug}: example {e['input']} says {e['output']}, reference gives {c['output']}")
    return cases


def check(slug, problem, ref, gen_mod):
    cases = build_cases(slug, problem, ref, gen_mod)
    msg = f"{slug}: {len(cases)} cases, examples ok"
    if hasattr(ref, "brute"):
        rng = random.Random(slug + "-brute")
        for _ in range(300):
            i = gen_mod.gen(rng, rng.choice(["edge", "small"]))
            a, b = ref.solve(**json.loads(json.dumps(i))), ref.brute(**json.loads(json.dumps(i)))
            if a != b:
                raise ValueError(f"{slug}: reference {a} != brute {b} for {i}")
        msg += ", 300 random cases match brute force"
    print(msg)
    return cases


def aws_clients():
    import boto3
    region = os.environ.get("AWS_REGION", "ap-south-1")
    creds = dict(aws_access_key_id=os.environ.get("AWS_ACCESS_KEY_ID", "local"),
                 aws_secret_access_key=os.environ.get("AWS_SECRET_ACCESS_KEY", "local"), region_name=region)
    return (boto3.client("dynamodb", endpoint_url=os.environ.get("DYNAMO_ENDPOINT", "http://localhost:9700"), **creds),
            boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT", "http://localhost:9703"), **creds))


def doc_of(problem: dict) -> dict:
    keys = ["slug", "title", "difficulty", "tags", "statement", "constraints", "examples", "signature", "limits", "hints"]
    return {k: problem.get(k) for k in keys if k in problem}


def publish(ddb, s3, problem: dict, cases, force: bool, source="seed"):
    slug = problem["slug"]
    key = {"PK": {"S": f"PROBLEM#{slug}"}, "SK": {"S": "META"}}
    existing = ddb.get_item(TableName="Problems", Key=key).get("Item")
    if existing and not force:
        print(f"  {slug}: exists, skipped (use --force to overwrite)")
        return
    version = int(existing["testsVersion"]["N"]) + 1 if existing else 1
    body = "\n".join(json.dumps(c, separators=(",", ":")) for c in cases) + "\n"
    s3.put_object(Bucket="codearena-tests", Key=f"{slug}/v{version}/cases.jsonl", Body=body.encode())
    now = datetime.now(timezone.utc).isoformat()
    if existing:
        ddb.update_item(TableName="Problems", Key=key,
                        UpdateExpression="SET #d = :d, title = :t, difficulty = :f, tags = :g, testsVersion = :v, updatedAt = :u",
                        ExpressionAttributeNames={"#d": "doc"},
                        ExpressionAttributeValues={":d": {"S": json.dumps(doc_of(problem))}, ":t": {"S": problem["title"]},
                                                   ":f": {"S": problem["difficulty"]}, ":g": {"S": ",".join(problem["tags"])},
                                                   ":v": {"N": str(version)}, ":u": {"S": now}})
        print(f"  {slug}: updated, tests v{version} ({len(cases)} cases)")
        return
    number = int(ddb.update_item(TableName="Problems", Key={"PK": {"S": "COUNTER"}, "SK": {"S": "PROBLEM_NUMBER"}},
                                 UpdateExpression="ADD #v :one", ExpressionAttributeNames={"#v": "value"},
                                 ExpressionAttributeValues={":one": {"N": "1"}},
                                 ReturnValues="UPDATED_NEW")["Attributes"]["value"]["N"])
    ddb.put_item(TableName="Problems", Item={
        **key, "slug": {"S": slug}, "title": {"S": problem["title"]}, "difficulty": {"S": problem["difficulty"]},
        "tags": {"S": ",".join(problem["tags"])}, "doc": {"S": json.dumps(doc_of(problem))},
        "status": {"S": "PUBLISHED"}, "testsVersion": {"N": str(version)}, "source": {"S": source},
        "number": {"S": f"{number:05d}"}, "listPk": {"S": "LIST#PUBLISHED"},
        "createdAt": {"S": now}, "updatedAt": {"S": now}})
    print(f"  {slug}: published as #{number} ({len(cases)} cases)")


def bump_cache_version():
    """Tell problem-service its cached list is stale (it reads problems:list:version)."""
    try:
        import socket
        host, port = os.environ.get("REDIS_HOST", "localhost"), int(os.environ.get("REDIS_PORT", "9701"))
        with socket.create_connection((host, port), timeout=2) as s:
            s.sendall(b"*2\r\n$4\r\nINCR\r\n$21\r\nproblems:list:version\r\n")
            s.recv(64)
    except OSError as e:
        print(f"(could not reach Redis to refresh the list cache: {e}; it refreshes within 10 minutes)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="verify only, write nothing")
    ap.add_argument("--force", action="store_true", help="overwrite existing problems")
    ap.add_argument("--demo", type=int, default=0, help="add N demo problems for pagination tests")
    args = ap.parse_args()

    loaded = []
    for d in sorted(p for p in PROBLEMS.iterdir() if p.is_dir()):
        problem = json.loads((d / "problem.json").read_text())
        ref = load_module(d / "reference.py", f"ref_{d.name.replace('-', '_')}")
        gen_mod = load_module(d / "gen.py", f"gen_{d.name.replace('-', '_')}")
        t0 = time.time()
        cases = check(d.name, problem, ref, gen_mod)
        loaded.append((problem, cases))
        if time.time() - t0 > 30:
            print(f"  (slow: {time.time() - t0:.0f}s)")
    if args.check:
        print("check passed")
        return

    ddb, s3 = aws_clients()
    for problem, cases in loaded:
        publish(ddb, s3, problem, cases, args.force)
    if args.demo:
        base, base_cases = next((p, c) for p, c in loaded if p["slug"] == "pair-with-target-sum")
        for i in range(1, args.demo + 1):
            demo = dict(base, slug=f"demo-problem-{i:04d}", title=f"Demo Problem {i:04d}", tags=["demo"],
                        difficulty=["EASY", "MEDIUM", "HARD"][i % 3])
            publish(ddb, s3, demo, base_cases, args.force, source="demo")
    bump_cache_version()
    print("seed complete")


if __name__ == "__main__":
    sys.exit(main())