import { useEffect, useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest, ProblemPage } from "../api/types";
import { TestStrip } from "../components/TestStrip";
import { useAuth } from "../auth/AuthProvider";

const DEMO_TOTAL = 30;
const DEMO_CODE = [
  ["def", " longestUniqueRun", "(self, s):"],
  ["    ", "last, left, best = {}, 0, 0", ""],
  ["    for", " right, ch ", "in enumerate(s):"],
  ["        if", " last.get(ch, -1) >= left:", ""],
  ["            ", "left = last[ch] + 1", ""],
  ["        ", "last[ch] = right", ""],
  ["        ", "best = max(best, right - left + 1)", ""],
  ["    return", " best", ""],
];

/** The one orchestrated motion on the site: a submission being judged, test by test. */
function JudgingDemo() {
  const [passed, setPassed] = useState(0);
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { setPassed(DEMO_TOTAL); return; }
    let n = 0;
    const id = setInterval(() => { n += 1; setPassed(n); if (n >= DEMO_TOTAL) clearInterval(id); }, 70);
    return () => clearInterval(id);
  }, []);
  const done = passed >= DEMO_TOTAL;
  return (
    <div className="rounded-2xl bg-panel border border-rule overflow-hidden shadow-[0_30px_80px_-30px_rgba(0,0,0,0.6)]">
      <div className="flex items-center justify-between px-5 h-11 border-b border-rule text-sm">
        <span className="text-muted">Longest Run Without Repeats</span>
        <span className="text-muted">Python 3.13</span>
      </div>
      <pre className="font-mono text-[13px] leading-6 px-5 py-4 overflow-x-auto" aria-label="Example solution">
        {DEMO_CODE.map((l, i) => (
          <div key={i}><span className="text-signal">{l[0]}</span><span>{l[1]}</span><span className="text-muted">{l[2]}</span></div>
        ))}
      </pre>
      <div className="border-t border-rule px-5 py-4 bg-night/40">
        <TestStrip total={DEMO_TOTAL} passed={passed} running={!done} fluid />
        <p className="mt-3 text-sm h-5" aria-live="polite">
          {done
            ? <span className="text-pass font-semibold">Accepted in 41 ms, all 30 tests passed</span>
            : <span className="text-muted">Running test {passed + 1} of {DEMO_TOTAL}</span>}
        </p>
      </div>
    </div>
  );
}

function useCountdown(to?: string) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);
  if (!to) return null;
  const ms = Math.max(0, new Date(to).getTime() - now);
  const d = Math.floor(ms / 86_400_000), h = Math.floor(ms / 3_600_000) % 24, m = Math.floor(ms / 60_000) % 60, s = Math.floor(ms / 1000) % 60;
  return d > 0 ? `${d}d ${h}h ${m}m` : `${h}h ${String(m).padStart(2, "0")}m ${String(s).padStart(2, "0")}s`;
}

export function Landing() {
  const { user } = useAuth();
  const contests = useQuery({ queryKey: ["contests"], queryFn: () => api<Contest[]>("/contests") });
  const problems = useQuery({ queryKey: ["problems", 1, "", "", ""], queryFn: () => api<ProblemPage>("/problems?page=1&size=100") });
  const next = contests.data?.filter((c) => c.phase !== "ENDED").sort((a, b) => a.startAt.localeCompare(b.startAt))[0];
  const countdown = useCountdown(next ? (next.phase === "LIVE" ? next.endAt : next.startAt) : undefined);

  return (
    <div className="mx-auto max-w-[1200px] px-4 sm:px-6">
      <section className="grid lg:grid-cols-[1.05fr_1fr] gap-12 items-center py-16 lg:py-24">
        <div>
          <h1 className="font-display font-extrabold tracking-tight text-[clamp(2.6rem,6vw,4.6rem)] leading-[0.98]">
            Solve it. Then watch every test decide.
          </h1>
          <p className="mt-6 text-lg text-muted max-w-[46ch]">
            Practise algorithms in Java, Python, C++, JavaScript or Go. Your code runs against hidden tests in an
            isolated sandbox, and coding fests rank everyone live.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link to="/problems" className="h-12 px-6 inline-flex items-center rounded-xl bg-signal text-signal-ink font-semibold">
              Browse {problems.data ? problems.data.totalItems : ""} problems
            </Link>
            {!user && (
              <Link to="/register" className="h-12 px-6 inline-flex items-center rounded-xl bg-raised hover:bg-rule">
                Create a free account
              </Link>
            )}
          </div>
        </div>
        <JudgingDemo />
      </section>

      {next && (
        <section className="mb-20 rounded-2xl border border-rule px-6 py-6 flex flex-wrap items-center gap-6">
          <div className="flex-1 min-w-[16rem]">
            <p className="text-sm text-muted">{next.phase === "LIVE" ? "Fest in progress, ends in" : "Next fest starts in"}</p>
            <p className="font-display font-extrabold text-3xl tabular-nums">{countdown}</p>
            <p className="mt-1">{next.title}</p>
          </div>
          <Link to={`/contests/${next.id}`} className="h-11 px-5 inline-flex items-center rounded-xl bg-raised hover:bg-rule">
            {next.phase === "LIVE" ? "Open the live leaderboard" : "See the fest and register"}
          </Link>
        </section>
      )}
    </div>
  );
}