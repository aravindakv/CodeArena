import { useState } from "react";
import { Link } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest } from "../api/types";
import { Button, ErrorNote, Field, PageTitle } from "../components/ui";

const local = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);

export function AdminContests() {
  const qc = useQueryClient();
  const contests = useQuery({ queryKey: ["contests"], queryFn: () => api<Contest[]>("/contests") });
  const inTen = new Date(Date.now() + 10 * 60_000);
  const [form, setForm] = useState({
    title: "", description: "", start: local(inTen), end: local(new Date(inTen.getTime() + 90 * 60_000)),
    problems: "pair-with-target-sum, balanced-brackets, max-subarray-sum",
  });
  const create = useMutation({
    mutationFn: () => api<Contest>("/admin/contests", { method: "POST", body: {
      title: form.title, description: form.description,
      startAt: new Date(form.start).toISOString(), endAt: new Date(form.end).toISOString(),
      problems: form.problems.split(",").map((s) => s.trim()).filter(Boolean),
    } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["contests"] }),
  });
  const act = useMutation({
    mutationFn: ({ id, action }: { id: string; action: string }) => api(`/admin/contests/${id}/${action}`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["contests"] }),
  });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  return (
    <div>
      <PageTitle>Fests</PageTitle>
      <form className="rounded-xl border border-rule p-5 grid sm:grid-cols-2 gap-4 max-w-3xl"
        onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
        <div className="sm:col-span-2"><Field label="Title" required value={form.title} onChange={set("title")} placeholder="October Sprint" /></div>
        <div className="sm:col-span-2"><Field label="Description" value={form.description} onChange={set("description")} /></div>
        <Field label="Starts (your local time)" type="datetime-local" required value={form.start} onChange={set("start")} />
        <Field label="Ends" type="datetime-local" required value={form.end} onChange={set("end")} />
        <div className="sm:col-span-2"><Field label="Problem slugs, in order (A, B, C…)" required value={form.problems} onChange={set("problems")} /></div>
        {create.error && <div className="sm:col-span-2"><ErrorNote error={create.error} /></div>}
        <div><Button disabled={create.isPending}>Create fest</Button></div>
      </form>

      <table className="w-full text-sm mt-10">
        <thead className="text-left text-muted"><tr><th className="py-2 font-medium">Fest</th><th className="font-medium">Phase</th><th className="font-medium">Actions</th></tr></thead>
        <tbody>
          {contests.data?.map((c) => (
            <tr key={c.id} className="border-t border-rule">
              <td className="py-2"><Link className="hover:text-signal" to={`/contests/${c.id}`}>{c.title}</Link></td>
              <td className="text-muted">{c.phase.toLowerCase()}{c.frozen ? ", frozen" : ""}{c.finalized ? ", final" : ""}</td>
              <td className="space-x-3">
                {c.phase === "LIVE" && !c.frozen && <button className="text-signal hover:underline" onClick={() => act.mutate({ id: c.id, action: "freeze" })}>Freeze board</button>}
                {c.frozen && <button className="text-signal hover:underline" onClick={() => act.mutate({ id: c.id, action: "unfreeze" })}>Unfreeze</button>}
                {c.phase === "ENDED" && !c.finalized && <button className="text-signal hover:underline" onClick={() => act.mutate({ id: c.id, action: "finalize" })}>Finalize now</button>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}