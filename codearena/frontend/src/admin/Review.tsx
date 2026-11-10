import { useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import { api } from "../api/client";
import type { Difficulty, Example } from "../api/types";
import { Button, DifficultyTag, ErrorNote, PageTitle, Skeleton } from "../components/ui";

interface Row { slug: string; title: string; difficulty: Difficulty; status: string; number: number; testsVersion: number; source: string; updatedAt: string }
interface Detail {
  record: { doc: { slug: string; title: string; difficulty: Difficulty; tags: string[]; statement: string; constraints: string[];
    examples: Example[]; signature: { function: string; params: { name: string; type: string }[]; returns: string; compare: string } };
    status: string; number: number; testsVersion: number; source: string };
  starters: Record<string, string>;
}

const STATUSES = ["IN_REVIEW", "DRAFT", "PUBLISHED", "REJECTED", "RETIRED"];

export function ReviewQueue() {
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? "IN_REVIEW";
  const q = useQuery({ queryKey: ["admin-problems", status], queryFn: () => api<Row[]>(`/admin/problems?status=${status}`) });
  return (
    <div>
      <PageTitle aside={<select value={status} onChange={(e) => setParams({ status: e.target.value })} aria-label="Status"
        className="h-9 rounded-md bg-panel border border-rule px-2 text-sm">
        {STATUSES.map((s) => <option key={s} value={s}>{s.replace("_", " ").toLowerCase()}</option>)}
      </select>}>Problems</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {q.isLoading && <Skeleton className="h-40" />}
      {q.data?.length === 0 && <p className="text-muted">Nothing here. Generated problems land in “in review” after an AI ingestion run.</p>}
      <ul className="divide-y divide-rule border-y border-rule">
        {q.data?.map((r) => (
          <li key={r.slug}>
            <Link to={`/admin/review/${r.slug}`} className="flex items-center gap-4 py-3 hover:bg-panel/50 px-2 -mx-2 rounded">
              <span className="flex-1 font-medium">{r.title}</span>
              <DifficultyTag value={r.difficulty} />
              <span className="text-sm text-muted w-40 truncate">{r.source}</span>
              <span className="text-sm text-muted w-20">tests v{r.testsVersion}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ReviewDetail() {
  const { slug = "" } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [lang, setLang] = useState("java");
  const q = useQuery({ queryKey: ["admin-problem", slug], queryFn: () => api<Detail>(`/admin/problems/${slug}`) });
  const act = useMutation({
    mutationFn: (action: "publish" | "reject" | "retire") => api(`/admin/problems/${slug}/${action}`, { method: "POST" }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["admin-problems"] }); navigate("/admin/review"); },
  });
  if (q.error) return <ErrorNote error={q.error} />;
  if (!q.data) return <Skeleton className="h-80" />;
  const d = q.data.record.doc;
  const status = q.data.record.status;

  return (
    <div>
      <PageTitle aside={
        <div className="flex gap-2">
          {status !== "PUBLISHED" && status !== "RETIRED" && <Button onClick={() => act.mutate("publish")} disabled={act.isPending}>Publish</Button>}
          {status === "IN_REVIEW" && <Button variant="danger" onClick={() => act.mutate("reject")} disabled={act.isPending}>Reject</Button>}
          {status === "PUBLISHED" && <Button variant="danger" onClick={() => act.mutate("retire")} disabled={act.isPending}>Retire</Button>}
        </div>
      }>{d.title}</PageTitle>
      {act.error && <ErrorNote error={act.error} />}
      <p className="text-sm text-muted mb-6">
        <DifficultyTag value={d.difficulty} /> <span className="mx-2" />{d.tags.join(", ")}<span className="mx-2" />
        status {status.toLowerCase()}, source {q.data.record.source}, hidden tests v{q.data.record.testsVersion}
      </p>
      <div className="grid xl:grid-cols-2 gap-8">
        <section>
          <div className="statement"><ReactMarkdown rehypePlugins={[rehypeSanitize]}>{d.statement}</ReactMarkdown></div>
          {d.examples.map((e, i) => (
            <pre key={i} className="mt-3 rounded-lg bg-panel border border-rule p-3 font-mono text-[13px] whitespace-pre-wrap">
              Input: {e.input}{"\n"}Output: {e.output}{e.explanation ? `\n${e.explanation}` : ""}
            </pre>
          ))}
          <ul className="mt-4 list-disc pl-5 font-mono text-[13px] text-muted">{d.constraints.map((c) => <li key={c}>{c}</li>)}</ul>
        </section>
        <section>
          <h2 className="font-display font-semibold mb-2">Signature</h2>
          <p className="font-mono text-sm mb-4">
            {d.signature.function}({d.signature.params.map((p) => `${p.name}: ${p.type}`).join(", ")}) → {d.signature.returns}
            <span className="text-muted"> compared as {d.signature.compare}</span>
          </p>
          <div className="flex gap-1 mb-2">
            {Object.keys(q.data.starters).map((l) => (
              <button key={l} onClick={() => setLang(l)}
                className={`h-8 px-3 rounded-md text-sm ${lang === l ? "bg-raised" : "text-muted hover:text-paper"}`}>{l}</button>
            ))}
          </div>
          <pre className="rounded-lg bg-panel border border-rule p-3 font-mono text-[13px] overflow-x-auto">{q.data.starters[lang]}</pre>
          <p className="text-sm text-muted mt-4">
            Before publishing: read the statement for clarity and originality, check the examples by hand, and open the problem
            after publishing to submit a solution in at least one language.
          </p>
        </section>
      </div>
    </div>
  );
}