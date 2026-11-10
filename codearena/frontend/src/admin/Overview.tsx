import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

interface OverviewData {
  activeUsers: number;
  submissions: number;
  acceptanceRate: number;
  byLanguage: Record<string, number>;
  hardestProblems: { slug: string; title: string; submits: number; acceptance: number }[];
}

export function Overview() {
  const q = useQuery({ queryKey: ["admin-overview"], queryFn: () => api<OverviewData>("/admin/analytics/overview") });
  return (
    <div>
      <PageTitle>Overview</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {!q.data ? <Skeleton className="h-40" /> : (
        <div className="space-y-8">
          <dl className="grid grid-cols-3 gap-6">
            {[["People with submissions", q.data.activeUsers], ["Submissions", q.data.submissions], ["Acceptance", `${q.data.acceptanceRate}%`]].map(([k, v]) => (
              <div key={k}><dt className="text-sm text-muted">{k}</dt><dd className="font-display font-extrabold text-3xl">{v}</dd></div>
            ))}
          </dl>
          <section>
            <h2 className="font-display font-semibold text-lg mb-2">Lowest acceptance</h2>
            <table className="w-full text-sm">
              <tbody>
                {q.data.hardestProblems.map((p) => (
                  <tr key={p.slug} className="border-t border-rule">
                    <td className="py-2">{p.title}</td>
                    <td className="text-right text-muted tabular-nums">{p.submits} submissions</td>
                    <td className="text-right tabular-nums w-24">{p.acceptance}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </div>
      )}
    </div>
  );
}