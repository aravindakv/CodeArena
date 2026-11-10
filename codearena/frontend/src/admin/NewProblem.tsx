import { useState } from "react";
import { useNavigate } from "react-router";
import { api } from "../api/client";
import { Button, ErrorNote, PageTitle } from "../components/ui";

const TEMPLATE = JSON.stringify({
  slug: "sum-of-two",
  title: "Sum of Two",
  difficulty: "EASY",
  tags: ["math"],
  statement: "Return the sum of `a` and `b`.",
  constraints: ["-10^9 <= a, b <= 10^9"],
  examples: [{ input: "a = 2, b = 3", output: "5" }],
  signature: { function: "sumOfTwo", params: [{ name: "a", type: "int" }, { name: "b", type: "int" }], returns: "long", compare: "JSON" },
  limits: { timeMs: 2000, memoryMb: 256 },
  hints: [],
}, null, 2);

const TESTS = `{"input":{"a":2,"b":3},"output":5}
{"input":{"a":-1000000000,"b":-1000000000},"output":-2000000000}`;

/** Manual authoring: the problem JSON (same shape the AI pipeline produces) plus hidden tests as JSON Lines. */
export function NewProblem() {
  const navigate = useNavigate();
  const [doc, setDoc] = useState(TEMPLATE);
  const [tests, setTests] = useState(TESTS);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);

  const save = async () => {
    setBusy(true); setError(undefined);
    try {
      const parsed = JSON.parse(doc);
      await api("/admin/problems", { method: "POST", body: parsed });
      await api(`/admin/problems/${parsed.slug}/tests`, { method: "PUT", body: tests, headers: { "Content-Type": "application/x-ndjson" } });
      navigate(`/admin/review/${parsed.slug}`);
    } catch (e) {
      setError(e instanceof SyntaxError ? new Error(`The problem JSON is invalid: ${e.message}`) : e);
    } finally {
      setBusy(false);
    }
  };

  const box = "w-full font-mono text-[13px] rounded-lg bg-night border border-rule p-3 focus:border-signal focus:outline-none";
  return (
    <div>
      <PageTitle>New problem</PageTitle>
      <div className="grid xl:grid-cols-2 gap-6">
        <label className="block"><span className="block text-sm text-muted mb-1.5">Problem (JSON)</span>
          <textarea className={`${box} h-[30rem]`} value={doc} onChange={(e) => setDoc(e.target.value)} spellCheck={false} /></label>
        <label className="block"><span className="block text-sm text-muted mb-1.5">Hidden tests, one JSON object per line</span>
          <textarea className={`${box} h-[30rem]`} value={tests} onChange={(e) => setTests(e.target.value)} spellCheck={false} /></label>
      </div>
      {error !== undefined && <div className="mt-4"><ErrorNote error={error} /></div>}
      <Button className="mt-4" onClick={save} disabled={busy}>{busy ? "Saving…" : "Save as draft"}</Button>
      <p className="text-sm text-muted mt-2">Types: int, long, double, bool, string, int[], long[], double[], string[], int[][]. The draft opens in review, where you publish it.</p>
    </div>
  );
}