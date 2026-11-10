import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { ProblemPage } from "../api/types";
import { useAuth } from "../auth/AuthProvider";
import { DifficultyTag, ErrorNote, PageTitle, Skeleton } from "../components/ui";
import { Pager } from "../components/Pager";

const SIZE = 100;

function fetchPage(page: number, difficulty: string, tag: string, q: string) {
  const p = new URLSearchParams({ page: String(page), size: String(SIZE) });
  if (difficulty) p.set("difficulty", difficulty);
  if (tag) p.set("tag", tag);
  if (q) p.set("q", q);
  return api<ProblemPage>(`/problems?${p}`);
}

export function Problems() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const page = Math.max(1, Number(params.get("page") ?? 1));
  const difficulty = params.get("difficulty") ?? "";
  const tag = params.get("tag") ?? "";
  const q = params.get("q") ?? "";
  const [search, setSearch] = useState(q);
  const qc = useQueryClient();

  const list = useQuery({
    queryKey: ["problems", page, difficulty, tag, q],
    queryFn: () => fetchPage(page, difficulty, tag, q),
    placeholderData: keepPreviousData,
  });
  const tags = useQuery({ queryKey: ["tags"], queryFn: () => api<string[]>("/problems/tags"), staleTime: 300_000 });
  const slugs = list.data?.items.map((p) => p.slug) ?? [];
  const solved = useQuery({
    queryKey: ["solved", user?.id, slugs.join(",")],
    queryFn: () => api<string[]>(`/me/solved?slugs=${encodeURIComponent(slugs.join(","))}`),
    enabled: !!user && slugs.length > 0,
  });
  const solvedSet = new Set(solved.data ?? []);

  // Prefetch the next page so paging feels instant.
  useEffect(() => {
    if (list.data && page < list.data.totalPages)
      qc.prefetchQuery({ queryKey: ["problems", page + 1, difficulty, tag, q], queryFn: () => fetchPage(page + 1, difficulty, tag, q) });
  }, [list.data, page, difficulty, tag, q, qc]);

  // Debounced search box.
  useEffect(() => {
    const id = setTimeout(() => {
      if (search === q) return;
      const next = new URLSearchParams(params);
      if (search) next.set("q", search); else next.delete("q");
      next.set("page", "1");
      setParams(next);
    }, 300);
    return () => clearTimeout(id);
  }, [search]);

  const setFilter = (key: string, value: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value); else next.delete(key);
    next.set("page", "1");
    setParams(next);
  };

  const goPage = (p: number) => {
    const next = new URLSearchParams(params);
    next.set("page", String(p));
    setParams(next);
    window.scrollTo(0, 0);
  };

  return (
    <div className="mx-auto max-w-[1200px] px-4 sm:px-6 py-10">
      <PageTitle aside={list.data && <p className="text-muted text-sm">{list.data.totalItems} problems</p>}>Problems</PageTitle>

      <div className="flex flex-wrap items-center gap-3 mb-4">
        <input type="search" placeholder="Search by title or number" value={search} onChange={(e) => setSearch(e.target.value)}
          aria-label="Search problems"
          className="h-10 w-full sm:w-72 rounded-lg bg-panel border border-rule px-3 placeholder:text-muted/70 focus:border-signal focus:outline-none" />
        <div className="flex rounded-lg bg-panel border border-rule p-1" role="group" aria-label="Difficulty">
          {[["", "All"], ["EASY", "Easy"], ["MEDIUM", "Medium"], ["HARD", "Hard"]].map(([v, l]) => (
            <button key={v} onClick={() => setFilter("difficulty", v)} aria-pressed={difficulty === v}
              className={`h-8 px-3 rounded-md text-sm ${difficulty === v ? "bg-raised text-paper" : "text-muted hover:text-paper"}`}>{l}</button>
          ))}
        </div>
        <select value={tag} onChange={(e) => setFilter("tag", e.target.value)} aria-label="Topic"
          className="h-10 rounded-lg bg-panel border border-rule px-3 text-sm">
          <option value="">All topics</option>
          {tags.data?.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
      </div>

      {list.error ? <ErrorNote error={list.error} /> : (
        <div className="overflow-x-auto rounded-xl border border-rule">
          <table className="w-full text-left">
            <thead className="bg-panel text-sm text-muted">
              <tr>
                <th className="w-12 sm:w-16 px-3 sm:px-4 py-3 font-medium">#</th>
                <th className="px-4 py-3 font-medium">Title</th>
                <th className="hidden md:table-cell px-4 py-3 font-medium">Topics</th>
                <th className="w-28 px-4 py-3 font-medium">Difficulty</th>
                <th className="hidden sm:table-cell w-28 px-4 py-3 font-medium text-right">Acceptance</th>
              </tr>
            </thead>
            <tbody>
              {list.isLoading && Array.from({ length: 12 }, (_, i) => (
                <tr key={i} className="border-t border-rule"><td colSpan={5} className="px-4 py-3"><Skeleton className="h-5" /></td></tr>
              ))}
              {list.data?.items.map((p) => (
                <tr key={p.slug} className="border-t border-rule hover:bg-panel/60">
                  <td className="px-3 sm:px-4 py-3 tabular-nums text-muted">{p.number}</td>
                  <td className="px-4 py-3">
                    <Link to={`/problems/${p.slug}`} className="font-medium hover:text-signal">
                      {solvedSet.has(p.slug) && <span className="text-pass mr-2" aria-label="solved">✓</span>}
                      {p.title}
                    </Link>
                  </td>
                  <td className="hidden md:table-cell px-4 py-3 text-sm text-muted">{p.tags.join(", ")}</td>
                  <td className="px-4 py-3"><DifficultyTag value={p.difficulty} /></td>
                  <td className="hidden sm:table-cell px-4 py-3 text-right tabular-nums text-muted">{p.acceptance ? `${p.acceptance}%` : "–"}</td>
                </tr>
              ))}
              {list.data && list.data.items.length === 0 && (
                <tr><td colSpan={5} className="px-4 py-10 text-center text-muted">No problems match these filters. Clear the search or pick another topic.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      {list.data && <Pager page={page} total={list.data.totalPages} onChange={goPage} />}
    </div>
  );
}