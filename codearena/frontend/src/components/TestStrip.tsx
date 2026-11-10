/**
 * The test strip: one cell per test case. Green = passed, red = the failing case, grey = not reached.
 * Used for live verdicts in the workspace, the landing demo, and per-problem cells on the leaderboard.
 */
export function TestStrip({ total, passed, failedAt, running = false, max = 40, label, fluid = false }: {
  total: number;
  passed: number;
  failedAt?: number;      // index of the failing test, if any
  running?: boolean;
  max?: number;           // long test sets are scaled down to this many cells
  label?: string;
  fluid?: boolean;        // stretch cells to fill one row instead of wrapping
}) {
  const n = Math.max(1, Math.min(total, max));
  const scale = total / n;
  const cells = Array.from({ length: n }, (_, i) => {
    const t = Math.floor(i * scale);
    if (failedAt !== undefined && t >= failedAt && t < failedAt + scale) return "fail";
    if (t < passed) return "pass";
    if (running && Math.floor(t) === passed) return "run";
    return "";
  });
  return (
    <div className={`flex gap-[3px] ${fluid ? "" : "flex-wrap"}`} role="img"
      aria-label={label ?? `${passed} of ${total} tests passed${failedAt !== undefined ? `, test ${failedAt + 1} failed` : ""}`}>
      {cells.map((c, i) => <span key={i} className={`cell ${c} ${fluid ? "fluid" : ""}`} />)}
    </div>
  );
}