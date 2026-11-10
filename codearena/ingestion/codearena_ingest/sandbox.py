"""
Runs LLM-written code (test generators, reference solutions, translations) in the SAME sandbox as user
submissions: the runner images from sandbox-images/, no network, gVisor, hard limits. LLM output is untrusted
code and is never executed on the host.
"""
import json
import os
import shutil
import subprocess
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path

import requests

from .config import CONFIG

LANG_FILES = {  # user file, harness file (must match sandbox-images/<lang>/run.sh and Lang.java)
    "python": ("solution.py", "harness.py"), "java": ("Solution.java", "Main.java"), "cpp": ("solution.cpp", "main.cpp"),
    "javascript": ("solution.js", "harness.js"), "go": ("solution.go", "harness.go"),
}
LANG_TMPFS = {"python": "rw,noexec,nosuid,size=64m", "javascript": "rw,noexec,nosuid,size=64m",
              "java": "rw,noexec,nosuid,size=128m", "cpp": "rw,exec,nosuid,size=128m", "go": "rw,exec,nosuid,size=512m"}
LANG_EXTRA_MB = {"python": 0, "javascript": 64, "java": 320, "cpp": 512, "go": 768}

# Harness for test-input generators: reads {"seed": n, "size": "..."} lines, writes gen() output as JSON lines.
GENERATOR_HARNESS = """import sys as ca_sys, json as ca_json, random as ca_random
ca_res = open("/out/results.jsonl", "w", buffering=1)
exec(compile(open("/work/solution.py").read(), "generator.py", "exec"))
for ca_line in ca_sys.stdin:
    if ca_line.strip():
        ca_a = ca_json.loads(ca_line)
        ca_res.write(ca_json.dumps(gen(ca_random.Random(ca_a["seed"]), ca_a["size"]), separators=(",", ":")) + "\\n")
"""


@dataclass
class Run:
    ok: bool
    results: list[str]
    error: str


class Sandbox:
    def __init__(self, runtime: str = CONFIG.judge_runtime, work_root: str = CONFIG.work_root):
        self.runtime = runtime
        self.work_root = Path(work_root)
        self.work_root.mkdir(parents=True, exist_ok=True)

    def codegen(self, signature: dict, lang: str) -> dict:
        """Starter + harness from problem-service, so verification uses exactly the code the judge uses."""
        r = requests.post(f"{CONFIG.problem_url}/internal/codegen", json={"signature": signature, "lang": lang}, timeout=10)
        r.raise_for_status()
        return r.json()

    def run(self, lang: str, code: str, harness: str, inputs: list[str], time_ms: int = 20000, memory_mb: int = 512) -> Run:
        user_file, harness_file = LANG_FILES[lang]
        job = Path(tempfile.mkdtemp(prefix="ing-", dir=self.work_root))
        work, out = job / "work", job / "out"
        work.mkdir()
        out.mkdir()
        try:
            (work / user_file).write_text(code)
            (work / harness_file).write_text(harness)
            (work / "inputs.jsonl").write_text("\n".join(inputs) + "\n")
            for p in (job, work):
                os.chmod(p, 0o755)
            for f in work.iterdir():
                os.chmod(f, 0o644)
            os.chmod(out, 0o777)
            name = f"ca-ing-{uuid.uuid4().hex[:12]}"
            mem = memory_mb + LANG_EXTRA_MB[lang]
            cmd = ["docker", "run", "--rm", "--name", name, f"--runtime={self.runtime}", "--network=none", "--read-only",
                   "--tmpfs", f"/tmp:{LANG_TMPFS[lang]}", f"--memory={mem}m", f"--memory-swap={mem}m", "--cpus=1",
                   "--pids-limit=128", "--cap-drop=ALL", "--security-opt=no-new-privileges", "--user", "65534:65534",
                   "--ulimit", "nofile=64:64", "--ulimit", "fsize=104857600:104857600",
                   "-e", f"TIME_LIMIT_MS={time_ms}", "-e", "MAX_OUT_BYTES=65536", "-e", f"MEMORY_MB={memory_mb}",
                   "-v", f"{work}:/work:ro", "-v", f"{out}:/out:rw", f"codearena/runner-{lang}:1"]
            try:
                subprocess.run(cmd, capture_output=True, timeout=time_ms / 1000 + 60, check=False)
            except subprocess.TimeoutExpired:
                subprocess.run(["docker", "kill", name], capture_output=True)
                return Run(False, [], "wall-clock timeout")
            meta = (out / "meta").read_text().split() if (out / "meta").exists() else []
            results = [l for l in (out / "results.jsonl").read_text().splitlines() if l.strip()] \
                if (out / "results.jsonl").exists() else []
            if meta[:1] == ["COMPILE"]:
                return Run(False, [], "compile error: " + (out / "compile.err").read_text()[:1500])
            if not meta or meta[1] != "0" or len(results) != len(inputs):
                err = (out / "stderr").read_text()[:1500] if (out / "stderr").exists() else ""
                return Run(False, results, f"exit {meta[1] if len(meta) > 1 else '?'}, {len(results)}/{len(inputs)} results. {err}")
            return Run(True, results, "")
        finally:
            shutil.rmtree(job, ignore_errors=True)

    def generate_inputs(self, generator_py: str, plan: list[tuple[int, str]]) -> Run:
        lines = [json.dumps({"seed": s, "size": size}) for s, size in plan]
        return self.run("python", generator_py, GENERATOR_HARNESS, lines)