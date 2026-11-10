import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Stats } from "../api/types";
import { useAuth } from "../auth/AuthProvider";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

/** 53 weeks x 7 days, Monday first, ending today. */
function Heatmap({ data }: { data: Record<string, number> }) {
  const today = new Date();
  const days: { date: string; n: number }[] = [];
  const start = new Date(today);
  start.setDate(start.getDate() - 7 * 52 - ((today.getDay() + 6) % 7));
  for (const d = new Date(start); d <= today; d.setDate(d.getDate() + 1)) {
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    days.push({ date: key, n: data[key] ?? 0 });
  }
  const shade = (n: number) => n === 0 ? "bg-raised" : n < 3 ? "bg-pass/35" : n < 6 ? "bg-pass/65" : "bg-pass";
  return (
    <div className="overflow-x-auto">
      <div className="grid grid-rows-7 grid-flow-col gap-[3px] w-max" role="img" aria-label="Submissions per day over the last year">
        {days.map((d) => <span key={d.date} title={`${d.date}: ${d.n} submissions`} className={`w-3 h-3 rounded-[3px] ${shade(d.n)}`} />)}
      </div>
    </div>
  );
}

function Bars({ data, total }: { data: Record<string, number>; total?: number }) {
  const entries = Object.entries(data).sort((a, b) => b[1] - a[1]).slice(0, 8);
  const max = total ?? Math.max(1, ...entries.map((e) => e[1]));
  if (!entries.length) return <p className="text-sm text-muted">Nothing yet.</p>;
  return (
    <ul className="space-y-2">
      {entries.map(([k, v]) => (
        <li key={k} className="grid grid-cols-[8rem_1fr_3rem] items-center gap-3 text-sm">
          <span className="truncate">{k}</span>
          <span className="h-2 rounded-full bg-raised overflow-hidden"><span className="block h-full bg-signal" style={{ width: `${(100 * v) / max}%` }} /></span>
          <span className="text-right tabular-nums text-muted">{v}</span>
        </li>
      ))}
    </ul>
  );
}

export function Profile() {
  const { user } = useAuth();
  const q = useQuery({ queryKey: ["stats", user?.id], queryFn: () => api<Stats>("/me/stats") });
  const s = q.data;
  return (
    <div className="mx-auto max-w-[1100px] px-4 sm:px-6 py-10">
      <PageTitle>{user?.name}'s progress</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {!s ? <Skeleton className="h-64" /> : (
        <div className="space-y-10">
          <dl className="grid grid-cols-2 md:grid-cols-4 gap-x-8 gap-y-6">
            {[["Problems solved", s.solvedTotal], ["Current streak", `${s.currentStreak} days`],
              ["Longest streak", `${s.longestStreak} days`],
              ["Acceptance", s.submissions ? `${Math.round((100 * s.accepted) / s.submissions)}%` : "–"]].map(([k, v]) => (
              <div key={k}><dt className="text-sm text-muted">{k}</dt><dd className="font-display font-extrabold text-4xl">{v}</dd></div>
            ))}
          </dl>
          <section>
            <h2 className="font-display font-semibold text-xl mb-3">Activity</h2>
            <Heatmap data={s.heatmap} />
          </section>
          <div className="grid md:grid-cols-3 gap-10">
            <section><h2 className="font-display font-semibold text-xl mb-3">By difficulty</h2>
              <Bars data={{ Easy: s.solvedByDifficulty.EASY ?? 0, Medium: s.solvedByDifficulty.MEDIUM ?? 0, Hard: s.solvedByDifficulty.HARD ?? 0 }} /></section>
            <section><h2 className="font-display font-semibold text-xl mb-3">Languages</h2><Bars data={s.byLanguage} /></section>
            <section><h2 className="font-display font-semibold text-xl mb-3">Topics</h2><Bars data={s.byTag} /></section>
          </div>
          <section>
            <h2 className="font-display font-semibold text-xl mb-3">Recently solved</h2>
            {s.recentSolved.length === 0 ? <p className="text-muted">Solve your first problem and it shows up here. <Link className="text-signal hover:underline" to="/problems">Pick one</Link></p> : (
              <ul className="divide-y divide-rule border-y border-rule">
                {s.recentSolved.map((r) => (
                  <li key={r.slug} className="flex justify-between py-2.5">
                    <Link to={`/problems/${r.slug}`} className="hover:text-signal">{r.slug.replace(/-/g, " ")}</Link>
                    <span className="text-sm text-muted">{new Date(r.at).toLocaleDateString()}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}