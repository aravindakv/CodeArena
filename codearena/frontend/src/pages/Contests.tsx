import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest } from "../api/types";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

const fmt = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
const PHASE = { LIVE: "Live now", UPCOMING: "Upcoming", ENDED: "Ended" } as const;

export function Contests() {
  const q = useQuery({ queryKey: ["contests"], queryFn: () => api<Contest[]>("/contests"), refetchInterval: 30_000 });
  const order = { LIVE: 0, UPCOMING: 1, ENDED: 2 };
  const contests = [...(q.data ?? [])].sort((a, b) => order[a.phase] - order[b.phase] || b.startAt.localeCompare(a.startAt));

  return (
    <div className="mx-auto max-w-[1000px] px-4 sm:px-6 py-10">
      <PageTitle>Coding fests</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {q.isLoading && <Skeleton className="h-40" />}
      {q.data && contests.length === 0 && <p className="text-muted">No fests are scheduled yet. Check back soon.</p>}
      <ul className="divide-y divide-rule border-y border-rule">
        {contests.map((c) => (
          <li key={c.id}>
            <Link to={`/contests/${c.id}`} className="flex flex-wrap items-center gap-4 py-5 hover:bg-panel/50 px-2 -mx-2 rounded-lg">
              <span className={`w-24 text-sm font-medium ${c.phase === "LIVE" ? "text-signal" : "text-muted"}`}>
                {c.phase === "LIVE" && <span className="inline-block w-2 h-2 rounded-full bg-signal mr-2 animate-pulse" />}
                {PHASE[c.phase]}
              </span>
              <span className="flex-1 font-display font-semibold text-lg">{c.title}</span>
              <span className="text-sm text-muted">{fmt(c.startAt)} to {fmt(c.endAt)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}