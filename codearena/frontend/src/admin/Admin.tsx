import { NavLink, Route, Routes } from "react-router";
import { Overview } from "./Overview";
import { ReviewQueue, ReviewDetail } from "./Review";
import { Ingestion, IngestionRun } from "./Ingestion";
import { NewProblem } from "./NewProblem";
import { AdminContests } from "./AdminContests";
import { Users } from "./Users";

const item = ({ isActive }: { isActive: boolean }) =>
  `block rounded-md px-3 py-2 text-sm ${isActive ? "bg-raised text-paper" : "text-muted hover:text-paper"}`;

/** ADMIN-only console. The route is guarded in App.tsx and every API call is checked again on the server. */
export default function Admin() {
  return (
    <div className="mx-auto max-w-[1300px] px-4 sm:px-6 py-8 grid md:grid-cols-[200px_1fr] gap-8">
      <nav aria-label="Admin" className="space-y-1">
        <NavLink end to="/admin" className={item}>Overview</NavLink>
        <NavLink to="/admin/review" className={item}>Review queue</NavLink>
        <NavLink to="/admin/ingestion" className={item}>AI ingestion</NavLink>
        <NavLink to="/admin/new" className={item}>New problem</NavLink>
        <NavLink to="/admin/contests" className={item}>Fests</NavLink>
        <NavLink to="/admin/users" className={item}>Users</NavLink>
      </nav>
      <div className="min-w-0">
        <Routes>
          <Route index element={<Overview />} />
          <Route path="review" element={<ReviewQueue />} />
          <Route path="review/:slug" element={<ReviewDetail />} />
          <Route path="ingestion" element={<Ingestion />} />
          <Route path="ingestion/:runId" element={<IngestionRun />} />
          <Route path="new" element={<NewProblem />} />
          <Route path="contests" element={<AdminContests />} />
          <Route path="users" element={<Users />} />
        </Routes>
      </div>
    </div>
  );
}