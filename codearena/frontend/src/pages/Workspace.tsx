import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import Editor, { type OnMount } from "@monaco-editor/react";
import { Group, Panel, Separator } from "react-resizable-panels";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeSanitize from "rehype-sanitize";
import confetti from "canvas-confetti";
import { monaco } from "../editor/monaco";
import { api, ApiError } from "../api/client";
import type { Language, ProblemDetail, Submission, Verdict } from "../api/types";
import { useAuth } from "../auth/AuthProvider";
import { Button, DifficultyTag, ErrorNote, Skeleton } from "../components/ui";
import { TestStrip } from "../components/TestStrip";

const VERDICT_TEXT: Record<Verdict, string> = {
  PENDING: "Judging",
  ACCEPTED: "Accepted",
  WRONG_ANSWER: "Wrong answer",
  TIME_LIMIT_EXCEEDED: "Time limit exceeded",
  MEMORY_LIMIT_EXCEEDED: "Memory limit exceeded",
  RUNTIME_ERROR: "Runtime error",
  COMPILATION_ERROR: "Compilation error",
  OUTPUT_LIMIT_EXCEEDED: "Output limit exceeded",
  SYSTEM_ERROR: "Judge error",
};

/** "nums = [1,2], target = 3"  ->  {"nums":[1,2],"target":3}  (same rule as the server) */
function exampleToJson(input: string): string {
  const parts: string[] = [];
  let depth = 0, inStr = false, cur = "";
  for (let i = 0; i < input.length; i++) {
    const c = input[i];
    if (c === '"' && input[i - 1] !== "\\") inStr = !inStr;
    if (!inStr) {
      if (c === "[" || c === "{") depth++;
      if (c === "]" || c === "}") depth--;
      if (c === "," && depth === 0) { parts.push(cur); cur = ""; continue; }
    }
    cur += c;
  }
  if (cur.trim()) parts.push(cur);
  const obj = parts.map((p) => {
    const eq = p.indexOf("=");
    return `"${p.slice(0, eq).trim()}":${p.slice(eq + 1).trim()}`;
  });
  return `{${obj.join(",")}}`;
}

function useDraft(key: string, fallback: string | undefined) {
  const [code, setCode] = useState("");
  useEffect(() => {
    const saved = localStorage.getItem(key);
    setCode(saved ?? fallback ?? "");
  }, [key, fallback]);
  useEffect(() => {
    if (!code) return;
    const id = setTimeout(() => localStorage.setItem(key, code), 800);
    return () => clearTimeout(id);
  }, [key, code]);
  return [code, setCode] as const;
}

export default function Workspace() {
  const { slug = "" } = useParams();
  const [params] = useSearchParams();
  const contestId = params.get("contest") ?? undefined;
  const { user } = useAuth();
  const qc = useQueryClient();

  const problem = useQuery({ queryKey: ["problem", slug], queryFn: () => api<ProblemDetail>(`/problems/${slug}`) });
  const languages = useQuery({ queryKey: ["languages"], queryFn: () => api<Language[]>("/languages"), staleTime: Infinity });
  const [lang, setLang] = useState(() => localStorage.getItem("pref-lang") ?? "python");
  const starter = useQuery({
    queryKey: ["starter", slug, lang],
    queryFn: () => api<string>(`/problems/${slug}/starter/${lang}`),
    staleTime: Infinity,
  });
  const [code, setCode] = useDraft(`draft:${user?.id ?? "anon"}:${slug}:${lang}`, starter.data);

  const [tab, setTab] = useState<"description" | "submissions">("description");
  const [consoleTab, setConsoleTab] = useState<"result" | "input">("result");
  const [customInput, setCustomInput] = useState("");
  const [result, setResult] = useState<Submission | null>(null);
  const [busy, setBusy] = useState<"RUN" | "SUBMIT" | null>(null);
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    if (problem.data && !customInput && problem.data.examples[0]) setCustomInput(exampleToJson(problem.data.examples[0].input));
  }, [problem.data, customInput]);

  const chooseLang = (id: string) => {
    setLang(id);
    localStorage.setItem("pref-lang", id);
    if (user) api("/me/preferences", { method: "PUT", body: { language: id, theme: "dark" } }).catch(() => {});
  };

  const run = useCallback(async (mode: "RUN" | "SUBMIT") => {
    if (!user || busy) return;
    setBusy(mode); setError(undefined); setConsoleTab("result");
    setResult({ status: "PENDING", passed: 0, total: 0 } as unknown as Submission);
    try {
      const wasSolved = mode === "SUBMIT" ? (await api<string[]>(`/me/solved?slugs=${slug}`)).includes(slug) : true;
      const { submissionId } = await api<{ submissionId: string }>("/submissions", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: { slug, lang, code, mode, contestId: mode === "SUBMIT" ? contestId : undefined,
                customInput: mode === "RUN" && customInput.trim() ? customInput.trim() : undefined },
      });
      for (let delay = 400; ; delay = Math.min(delay * 1.4, 2500)) {
        await new Promise((r) => setTimeout(r, delay));
        const s = await api<Submission>(`/submissions/${submissionId}`);
        if (s.status !== "PENDING") {
          setResult(s);
          if (mode === "SUBMIT") qc.invalidateQueries({ queryKey: ["my-submissions", slug] });
          if (s.status === "ACCEPTED" && mode === "SUBMIT" && !wasSolved
              && !window.matchMedia("(prefers-reduced-motion: reduce)").matches)
            confetti({ particleCount: 90, spread: 70, origin: { y: 0.8 }, colors: ["#41D69A", "#FFB627", "#EDEFFF"] });
          break;
        }
      }
    } catch (e) {
      setResult(null);
      setError(e);
    } finally {
      setBusy(null);
    }
  }, [user, busy, slug, lang, code, contestId, customInput, qc]);

  const runRef = useRef(run);
  runRef.current = run;
  const onMount: OnMount = (editor) => {
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => runRef.current("RUN"));
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.Enter, () => runRef.current("SUBMIT"));
  };

  const monacoId = languages.data?.find((l) => l.id === lang)?.monacoId ?? lang;
  const p = problem.data;

  return (
    <div className="h-full flex flex-col">
      <header className="h-12 shrink-0 border-b border-rule flex items-center gap-4 px-4">
        <Link to={contestId ? `/contests/${contestId}` : "/problems"} className="text-muted hover:text-paper text-sm">
          {contestId ? "Back to the fest" : "All problems"}
        </Link>
        {p && <h1 className="font-display font-semibold truncate">{p.number}. {p.title}</h1>}
        {contestId && <span className="text-xs rounded-full bg-signal/15 text-signal px-2 py-0.5">Fest submission</span>}
        <span className="ml-auto text-sm text-muted hidden sm:inline">Ctrl+Enter to run, Ctrl+Shift+Enter to submit</span>
      </header>

      <Group orientation="horizontal" className="flex-1 min-h-0">
        <Panel defaultSize="42%" minSize="25%" className="flex flex-col min-w-0">
          <div className="flex gap-1 px-3 pt-2 border-b border-rule" role="tablist">
            {(["description", "submissions"] as const).map((t) => (
              <button key={t} role="tab" aria-selected={tab === t} onClick={() => setTab(t)}
                className={`h-9 px-3 text-sm rounded-t-md ${tab === t ? "bg-panel text-paper" : "text-muted hover:text-paper"}`}>
                {t === "description" ? "Description" : "My submissions"}
              </button>
            ))}
          </div>
          <div className="flex-1 overflow-y-auto px-6 py-5">
            {problem.error ? <ErrorNote error={problem.error} /> : !p ? <Skeleton className="h-64" /> :
              tab === "description" ? <Description p={p} /> : <MySubmissions slug={slug} />}
          </div>
        </Panel>

        <Separator className="w-1.5 bg-rule/60 hover:bg-signal transition-colors" />

        <Panel minSize="30%" className="flex flex-col min-w-0">
          <div className="h-12 shrink-0 flex items-center gap-2 px-3 border-b border-rule">
            <select value={lang} onChange={(e) => chooseLang(e.target.value)} aria-label="Language"
              className="h-9 rounded-md bg-panel border border-rule px-2 text-sm">
              {languages.data?.map((l) => <option key={l.id} value={l.id}>{l.label}</option>)}
            </select>
            <button className="text-sm text-muted hover:text-paper px-2"
              onClick={() => { if (confirm("Replace your code with the starter code?")) setCode(starter.data ?? ""); }}>
              Reset code
            </button>
            <div className="ml-auto flex gap-2">
              {user ? (
                <>
                  <Button variant="quiet" disabled={!!busy} onClick={() => run("RUN")}>{busy === "RUN" ? "Running…" : "Run"}</Button>
                  <Button disabled={!!busy} onClick={() => run("SUBMIT")}>{busy === "SUBMIT" ? "Judging…" : "Submit"}</Button>
                </>
              ) : (
                <Link to="/login" state={{ from: `/problems/${slug}` }}
                  className="h-10 px-4 inline-flex items-center rounded-lg bg-signal text-signal-ink text-sm font-semibold">
                  Log in to run your code
                </Link>
              )}
            </div>
          </div>

          <Group orientation="vertical" className="flex-1 min-h-0">
            <Panel defaultSize="64%" minSize="20%">
              <Editor theme="codearena" language={monacoId} value={code} onChange={(v) => setCode(v ?? "")} onMount={onMount}
                loading={<div className="p-4 text-muted">Loading editor…</div>}
                options={{ minimap: { enabled: false }, fontSize: 14, fontFamily: "JetBrains Mono", fontLigatures: false,
                  automaticLayout: true, scrollBeyondLastLine: false, tabSize: 4, padding: { top: 12 } }} />
            </Panel>
            <Separator className="h-1.5 bg-rule/60 hover:bg-signal transition-colors" />
            <Panel minSize="15%" className="flex flex-col">
              <div className="flex gap-1 px-3 pt-2 border-b border-rule" role="tablist">
                {(["result", "input"] as const).map((t) => (
                  <button key={t} role="tab" aria-selected={consoleTab === t} onClick={() => setConsoleTab(t)}
                    className={`h-8 px-3 text-sm rounded-t-md ${consoleTab === t ? "bg-panel text-paper" : "text-muted hover:text-paper"}`}>
                    {t === "result" ? "Result" : "Custom input"}
                  </button>
                ))}
              </div>
              <div className="flex-1 overflow-y-auto p-4">
                {consoleTab === "input" ? (
                  <div>
                    <p className="text-sm text-muted mb-2">Run uses the examples plus this input. Write it as JSON with one key per parameter.</p>
                    <textarea value={customInput} onChange={(e) => setCustomInput(e.target.value)} spellCheck={false}
                      aria-label="Custom input"
                      className="w-full h-28 font-mono text-sm rounded-lg bg-night border border-rule p-3 focus:border-signal focus:outline-none" />
                  </div>
                ) : error !== undefined ? <ErrorNote error={error instanceof ApiError ? error : new Error("Could not reach the judge. Try again.")} />
                  : result ? <ResultView r={result} /> : (
                  <p className="text-muted text-sm">Run checks your code against the examples. Submit runs every hidden test and records the result.</p>
                )}
              </div>
            </Panel>
          </Group>
        </Panel>
      </Group>
    </div>
  );
}

function Description({ p }: { p: ProblemDetail }) {
  return (
    <article>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 mb-4 text-sm">
        <DifficultyTag value={p.difficulty} />
        <span className="text-muted">{p.tags.join(", ")}</span>
        <span className="text-muted">Limits: {p.limits.timeMs / 1000} s, {p.limits.memoryMb} MB</span>
      </div>
      <div className="statement">
        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeSanitize]}>{p.statement}</ReactMarkdown>
      </div>
      {p.examples.map((ex, i) => (
        <section key={i} className="mt-5">
          <h2 className="font-semibold mb-2">Example {i + 1}</h2>
          <div className="rounded-lg bg-panel border border-rule p-4 font-mono text-[13px] space-y-1 overflow-x-auto">
            <div><span className="text-muted">Input: </span>{ex.input}</div>
            <div><span className="text-muted">Output: </span>{ex.output}</div>
            {ex.explanation && <div className="font-sans text-sm text-muted pt-1">{ex.explanation}</div>}
          </div>
        </section>
      ))}
      <h2 className="font-semibold mt-6 mb-2">Constraints</h2>
      <ul className="list-disc pl-5 font-mono text-[13px] space-y-1 text-muted">
        {p.constraints.map((c) => <li key={c}>{c}</li>)}
      </ul>
      {p.hints.length > 0 && (
        <div className="mt-6 space-y-2">
          {p.hints.map((h, i) => (
            <details key={i} className="rounded-lg border border-rule px-4 py-2">
              <summary className="cursor-pointer text-sm">Hint {i + 1}</summary>
              <p className="text-sm text-muted mt-2">{h}</p>
            </details>
          ))}
        </div>
      )}
    </article>
  );
}

function ResultView({ r }: { r: Submission }) {
  if (r.status === "PENDING") {
    return (
      <div aria-live="polite">
        <p className="font-display font-semibold text-xl text-signal mb-3">Judging…</p>
        <TestStrip total={20} passed={0} running label="Judging" fluid />
      </div>
    );
  }
  const ok = r.status === "ACCEPTED";
  const failedAt = !ok && r.total > 0 && r.status !== "COMPILATION_ERROR" ? r.passed : undefined;
  return (
    <div aria-live="polite" className="space-y-4">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <p className={`font-display font-extrabold text-2xl ${ok ? "text-pass" : "text-fail"}`}>{VERDICT_TEXT[r.status]}</p>
        {r.total > 0 && <p className="text-sm text-muted">{r.passed} of {r.total} tests passed</p>}
        {ok && <p className="text-sm text-muted">{r.runtimeMs} ms, {(r.memoryKb / 1024).toFixed(1)} MB</p>}
        {r.mode === "RUN" && <p className="text-sm text-muted">Examples only, not recorded</p>}
      </div>
      {r.total > 0 && <TestStrip total={r.total} passed={r.passed} failedAt={failedAt} fluid />}
      {r.status === "COMPILATION_ERROR" && <Pre title="Compiler output" text={r.message} />}
      {r.status === "RUNTIME_ERROR" && <Pre title="Error" text={r.message} />}
      {r.failedInput && <Pre title={`Input of test ${r.passed + 1}`} text={r.failedInput} />}
      {r.expected && <Pre title="Expected" text={r.expected} />}
      {r.actual && <Pre title="Your output" text={r.actual} />}
      {r.customOutput && <Pre title="Output for your custom input" text={r.customOutput} />}
      {r.stdout && <Pre title="Your prints (stdout)" text={r.stdout} />}
    </div>
  );
}

function Pre({ title, text }: { title: string; text?: string }) {
  if (!text) return null;
  return (
    <div>
      <p className="text-sm text-muted mb-1">{title}</p>
      <pre className="rounded-lg bg-panel border border-rule p-3 font-mono text-[13px] whitespace-pre-wrap break-all max-h-56 overflow-y-auto">{text}</pre>
    </div>
  );
}

function MySubmissions({ slug }: { slug: string }) {
  const { user } = useAuth();
  const list = useQuery({
    queryKey: ["my-submissions", slug],
    queryFn: () => api<Submission[]>(`/submissions?slug=${slug}`),
    enabled: !!user,
  });
  if (!user) return <p className="text-muted">Log in to see your submissions.</p>;
  if (list.isLoading) return <Skeleton className="h-40" />;
  if (!list.data?.length) return <p className="text-muted">No submissions yet. Press Submit to run every hidden test.</p>;
  return (
    <table className="w-full text-sm">
      <thead className="text-muted text-left">
        <tr><th className="py-2 font-medium">When</th><th className="font-medium">Result</th><th className="font-medium">Language</th><th className="font-medium text-right">Time</th></tr>
      </thead>
      <tbody>
        {list.data.map((s) => (
          <tr key={s.id} className="border-t border-rule">
            <td className="py-2 text-muted">{new Date(s.createdAt).toLocaleString()}</td>
            <td className={s.status === "ACCEPTED" ? "text-pass" : s.status === "PENDING" ? "text-signal" : "text-fail"}>{VERDICT_TEXT[s.status]}</td>
            <td>{s.lang}</td>
            <td className="text-right tabular-nums text-muted">{s.status === "ACCEPTED" ? `${s.runtimeMs} ms` : "–"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}