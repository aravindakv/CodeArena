/** First / previous / numbered pages / next / last. Keeps at most 7 numbers visible. */
export function Pager({ page, total, onChange }: { page: number; total: number; onChange: (p: number) => void }) {
  if (total <= 1) return null;
  const from = Math.max(1, Math.min(page - 3, total - 6));
  const nums = Array.from({ length: Math.min(7, total) }, (_, i) => from + i);
  const btn = "h-9 min-w-9 px-2 rounded-md text-sm disabled:opacity-40";
  return (
    <nav className="flex flex-wrap items-center gap-1 mt-6" aria-label="Pages">
      <button className={`${btn} text-muted hover:text-paper`} disabled={page === 1} onClick={() => onChange(1)}>First</button>
      <button className={`${btn} text-muted hover:text-paper`} disabled={page === 1} onClick={() => onChange(page - 1)}>Previous</button>
      {nums.map((n) => (
        <button key={n} aria-current={n === page ? "page" : undefined} onClick={() => onChange(n)}
          className={`${btn} ${n === page ? "bg-signal text-signal-ink font-semibold" : "text-paper hover:bg-raised"}`}>{n}</button>
      ))}
      <button className={`${btn} text-muted hover:text-paper`} disabled={page === total} onClick={() => onChange(page + 1)}>Next</button>
      <button className={`${btn} text-muted hover:text-paper`} disabled={page === total} onClick={() => onChange(total)}>Last</button>
    </nav>
  );
}