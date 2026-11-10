import { useState } from "react";
import { Link, useParams } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { Button, ErrorNote, Field, PageTitle, Skeleton } from "../components/ui";

interface RunRow { runId: string; createdAt: string; total: string }
interface RunView {
  runId: string; createdAt: string; total: number; stages: Record<string, number>;
  seeds: { id: string; topic: string; difficulty: string; stage: string; slug?: string; error?: string; updatedAt: string }[];
}

const STAGE_COLOR: Record<string, string> = {
  QUEUED: "text-muted", GENERATING: "text-signal", VERIFYING: "text-signal",
  READY_FOR_REVIEW: "text-pass", FAILED: "text-fail", DUPLICATE: "text-muted",
};

export function Ingestion() {
  const [topics, setTopics] = useState("");
  const [count, setCount] = useState(10);
  const [diffs, setDiffs] = useState<string[]>(["EASY", "MEDIUM"]);
  const runs = useQuery({ queryKey: ["ingestion-runs"], queryFn: () => api<RunRow[]>("/admin/ingestion/runs") });
  const start = useMutation({
    mutationFn: () => api<{ runId: string; seeds: number }>("/admin/ingestion/runs", {
      method: "POST",
      body: { topics: topics.split(",").map((t) => t.trim()).filter(Boolean), difficulties: diffs, count },
    }),
    onSuccess: () => runs.refetch(),
  });

  return (
    <div>
      <PageTitle>AI ingestion</PageTitle>
      <p className="text-muted mb-6 max-w-[68ch]">
        Each seed asks the model for an original problem on a topic, compares it with similar problems already in the bank
        (retrieval), generates tests from a reference solution, verifies it in the sandbox, and puts it in the review queue.
        Nothing is published without your approval.
      </p>
      <form className="rounded-xl border border-rule p-5 space-y-4 max-w-2xl" onSubmit={(e) => { e.preventDefault(); start.mutate(); }}>
        <Field label="Topics, comma separated (leave empty for the built-in list of 30)" value={topics}
          onChange={(e) => setTopics(e.target.value)} placeholder="sliding window, binary search, graphs BFS" />
        <fieldset>
          <legend className="text-sm text-muted mb-1.5">Difficulties</legend>
          <div className="flex gap-4">
            {["EASY", "MEDIUM", "HARD"].map((d) => (
              <label key={d} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={diffs.includes(d)}
                  onChange={(e) => setDiffs(e.target.checked ? [...diffs, d] : diffs.filter((x) => x !== d))} />
                {d[0] + d.slice(1).toLowerCase()}
              </label>
            ))}
          </div>
        </fieldset>
        <Field label="Number of problems (1 to 200 per run)" type="number" min={1} max={200} value={count}
          onChange={(e) => setCount(Number(e.target.value))} />
        {start.error && <ErrorNote error={start.error} />}
        {start.data && <p className="text-pass text-sm">Started run {start.data.runId} with {start.data.seeds} seeds.</p>}
        <Button disabled={start.isPending || diffs.length === 0}>Start ingestion run</Button>
      </form>

      <h2 className="font-display font-semibold text-xl mt-10 mb-3">Runs</h2>
      {runs.isLoading && <Skeleton className="h-24" />}
      {runs.data?.length === 0 && <p className="text-muted">No runs yet.</p>}
      <ul className="divide-y divide-rule border-y border-rule">
        {runs.data?.map((r) => (
          <li key={r.runId}>
            <Link to={`/admin/ingestion/${r.runId}`} className="flex gap-4 py-3 hover:bg-panel/50 px-2 -mx-2 rounded">
              <span className="font-mono text-sm flex-1">{r.runId}</span>
              <span className="text-sm text-muted">{new Date(r.createdAt).toLocaleString()}</span>
              <span className="text-sm w-24 text-right">{r.total} seeds</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function IngestionRun() {
  const { runId = "" } = useParams();
  const q = useQuery({ queryKey: ["ingestion-run", runId], queryFn: () => api<RunView>(`/admin/ingestion/runs/${runId}`), refetchInterval: 4000 });
  if (q.error) return <ErrorNote error={q.error} />;
  if (!q.data) return <Skeleton className="h-60" />;
  return (
    <div>
      <PageTitle>Run {runId.slice(-8)}</PageTitle>
      <p className="text-sm text-muted mb-4">
        {Object.entries(q.data.stages).map(([k, v]) => `${v} ${k.replace(/_/g, " ").toLowerCase()}`).join(", ")}
      </p>
      <table className="w-full text-sm">
        <thead className="text-left text-muted"><tr><th className="py-2 font-medium">Seed</th><th className="font-medium">Topic</th><th className="font-medium">Difficulty</th><th className="font-medium">Stage</th><th className="font-medium">Result</th></tr></thead>
        <tbody>
          {q.data.seeds.map((s) => (
            <tr key={s.id} className="border-t border-rule align-top">
              <td className="py-2 tabular-nums">{s.id}</td>
              <td>{s.topic}</td>
              <td>{s.difficulty.toLowerCase()}</td>
              <td className={STAGE_COLOR[s.stage] ?? ""}>{s.stage.replace(/_/g, " ").toLowerCase()}</td>
              <td className="max-w-[28rem]">
                {s.slug && <Link className="text-signal hover:underline" to={`/admin/review/${s.slug}`}>{s.slug}</Link>}
                {s.error && <span className="text-fail break-words">{s.error}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}