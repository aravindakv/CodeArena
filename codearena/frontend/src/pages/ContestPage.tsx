import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest, Leaderboard, ProblemDetail } from "../api/types";
import { useAuth } from "../auth/AuthProvider";
import { Button, DifficultyTag, ErrorNote, Skeleton } from "../components/ui";

function useNow() {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);
  return now;
}

function clock(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function ContestPage() {
  const { id = "" } = useParams();
  const { user } = useAuth();
  const now = useNow();
  const qc = useQueryClient();
  const contest = useQuery({ queryKey: ["contest", id], queryFn: () => api<Contest>(`/contests/${id}`), refetchInterval: 30_000 });
  const c = contest.data;

  // Requirement: the leaderboard refreshes every 5 seconds (the server rebuilds its snapshot at the same rate).
  const board = useQuery({
    queryKey: ["leaderboard", id],
    queryFn: () => api<Leaderboard>(`/contests/${id}/leaderboard`),
    refetchInterval: c?.phase === "LIVE" ? 5000 : false,
    enabled: !!c && c.phase !== "UPCOMING",
  });
  const me = useQuery({
    queryKey: ["leaderboard-me", id],
    queryFn: () => api<{ rank: number; solved: number; penalty: number }>(`/contests/${id}/leaderboard/me`),
    refetchInterval: c?.phase === "LIVE" ? 5000 : false,
    enabled: !!user && !!c && c.phase !== "UPCOMING",
  });
  const register = useMutation({
    mutationFn: () => api(`/contests/${id}/register`, { method: "POST" }),
    onSuccess: () => { localStorage.setItem(`registered:${id}:${user?.id}`, "1"); qc.invalidateQueries({ queryKey: ["contest", id] }); },
  });
  const registered = !!user && (localStorage.getItem(`registered:${id}:${user.id}`) === "1" || (me.data?.rank ?? 0) > 0);

  if (contest.error) return <div className="mx-auto max-w-3xl p-10"><ErrorNote error={contest.error} /></div>;
  if (!c) return <div className="mx-auto max-w-5xl p-10"><Skeleton className="h-48" /></div>;

  const toStart = new Date(c.startAt).getTime() - now;
  const toEnd = new Date(c.endAt).getTime() - now;
  const live = toStart <= 0 && toEnd > 0;

  return (
    <div className="mx-auto max-w-[1200px] px-4 sm:px-6 py-10">
      <div className="flex flex-wrap items-end gap-6 mb-8">
        <div className="flex-1 min-w-[18rem]">
          <h1 className="font-display font-extrabold text-4xl tracking-tight">{c.title}</h1>
          {c.description && <p className="text-muted mt-2 max-w-[60ch]">{c.description}</p>}
        </div>
        <div className="text-right">
          <p className="text-sm text-muted">{toStart > 0 ? "Starts in" : live ? "Time left" : "Finished"}</p>
          <p className={`font-display font-extrabold text-4xl tabular-nums ${live ? "text-signal" : ""}`}>
            {toStart > 0 ? clock(toStart) : live ? clock(toEnd) : "0:00:00"}
          </p>
        </div>
      </div>

      {user && !registered && toEnd > 0 && (
        <div className="mb-8 flex flex-wrap items-center gap-4 rounded-xl border border-signal/40 bg-signal/10 px-5 py-4">
          <p className="flex-1">Register to submit during the fest and appear on the leaderboard.</p>
          <Button onClick={() => register.mutate()} disabled={register.isPending}>Register for this fest</Button>
          {register.error && <ErrorNote error={register.error} />}
        </div>
      )}
      {!user && toEnd > 0 && (
        <p className="mb-8 text-muted"><Link to="/login" state={{ from: `/contests/${id}` }} className="text-signal hover:underline">Log in</Link> to register.</p>
      )}

      <div className="grid lg:grid-cols-[320px_1fr] gap-8">
        <section>
          <h2 className="font-display font-semibold text-xl mb-3">Problems</h2>
          {c.problems.length === 0 ? <p className="text-muted text-sm">Problems are revealed when the fest starts.</p> : (
            <ol className="space-y-2">
              {c.problems.map((slug, i) => <ContestProblem key={slug} slug={slug} letter={String.fromCharCode(65 + i)} contestId={id} live={live} />)}
            </ol>
          )}
          {me.data && me.data.rank > 0 && (
            <div className="mt-6 rounded-xl bg-panel border border-rule p-4">
              <p className="text-sm text-muted">Your position</p>
              <p className="font-display font-extrabold text-3xl">#{me.data.rank}</p>
              <p className="text-sm text-muted">{me.data.solved} solved, {me.data.penalty} penalty minutes</p>
            </div>
          )}
        </section>

        <section>
          <div className="flex items-baseline justify-between mb-3">
            <h2 className="font-display font-semibold text-xl">Leaderboard</h2>
            {board.data && <p className="text-sm text-muted">
              {board.data.participants} competing{board.data.frozen ? ", frozen for the final stretch" : live ? ", updates every 5 seconds" : ""}
            </p>}
          </div>
          {c.phase === "UPCOMING" ? <p className="text-muted">The leaderboard opens when the fest starts.</p>
            : !board.data ? <Skeleton className="h-60" /> : (
            <div className="overflow-x-auto rounded-xl border border-rule">
              <table className="w-full text-sm">
                <thead className="bg-panel text-muted text-left">
                  <tr>
                    <th className="w-14 px-3 py-2.5 font-medium">Rank</th>
                    <th className="px-3 py-2.5 font-medium">Name</th>
                    <th className="w-20 px-3 py-2.5 font-medium text-right">Solved</th>
                    <th className="w-24 px-3 py-2.5 font-medium text-right">Penalty</th>
                    {c.problems.map((s, i) => <th key={s} className="w-14 px-2 py-2.5 font-medium text-center" title={s}>{String.fromCharCode(65 + i)}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {board.data.rows.length === 0 && (
                    <tr><td colSpan={4 + c.problems.length} className="px-3 py-8 text-center text-muted">No accepted solutions yet. The first one takes the top spot.</td></tr>
                  )}
                  {board.data.rows.map((r) => (
                    <tr key={r.userId} className={`border-t border-rule transition-colors ${r.userId === user?.id ? "bg-signal/10" : ""}`}>
                      <td className={`px-3 py-2 font-display font-semibold tabular-nums ${r.rank <= 3 ? "text-signal" : ""}`}>{r.rank}</td>
                      <td className="px-3 py-2">{r.name}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{r.solved}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-muted">{r.penalty}</td>
                      {c.problems.map((s) => {
                        const cell = r.problems?.[s];
                        if (!cell) return <td key={s} />;
                        const solved = cell.solvedAtMin >= 0;
                        return (
                          <td key={s} className="px-1 py-1.5 text-center">
                            <span className={`inline-block min-w-11 rounded px-1.5 py-0.5 text-xs tabular-nums ${solved ? "bg-pass/20 text-pass" : "bg-fail/15 text-fail"}`}
                              title={solved ? `solved at minute ${cell.solvedAtMin}, ${cell.wrong} wrong attempts` : `${cell.wrong} wrong attempts`}>
                              {solved ? `${cell.solvedAtMin}'` : `−${cell.wrong}`}{solved && cell.wrong > 0 ? ` +${cell.wrong}` : ""}
                            </span>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-xs text-muted mt-3">Ranked by problems solved, then penalty: minutes to each accepted solution plus 20 per wrong attempt before it.</p>
        </section>
      </div>
    </div>
  );
}

function ContestProblem({ slug, letter, contestId, live }: { slug: string; letter: string; contestId: string; live: boolean }) {
  const p = useQuery({ queryKey: ["problem", slug], queryFn: () => api<ProblemDetail>(`/problems/${slug}`), staleTime: 300_000 });
  return (
    <li>
      <Link to={live ? `/problems/${slug}?contest=${contestId}` : `/problems/${slug}`}
        className="flex items-center gap-3 rounded-lg border border-rule px-3 py-2.5 hover:border-signal">
        <span className="font-display font-extrabold text-signal w-5">{letter}</span>
        <span className="flex-1">{p.data?.title ?? slug}</span>
        {p.data && <span className="text-sm"><DifficultyTag value={p.data.difficulty} /></span>}
      </Link>
    </li>
  );
}