import { lazy, Suspense } from "react";
import { Route, Routes } from "react-router";
import { Shell } from "./components/Shell";
import { RequireRole } from "./auth/RequireRole";
import { Landing } from "./pages/Landing";
import { Login, Register } from "./pages/AuthPages";
import { Problems } from "./pages/Problems";
import { Contests } from "./pages/Contests";
import { ContestPage } from "./pages/ContestPage";
import { Profile } from "./pages/Profile";
import { NotFound } from "./pages/NotFound";

// Monaco (about 2 MB) and the admin console load only when someone opens those routes.
const Workspace = lazy(() => import("./pages/Workspace"));
const Admin = lazy(() => import("./admin/Admin"));

export function App() {
  return (
    <Suspense fallback={<div className="p-10 text-muted">Loading…</div>}>
      <Routes>
        <Route element={<Shell />}>
          <Route path="/" element={<Landing />} />
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/problems" element={<Problems />} />
          <Route path="/contests" element={<Contests />} />
          <Route path="/contests/:id" element={<ContestPage />} />
          <Route path="/me" element={<RequireRole role="USER"><Profile /></RequireRole>} />
          <Route path="/admin/*" element={<RequireRole role="ADMIN"><Admin /></RequireRole>} />
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route path="/problems/:slug" element={<Workspace />} />
      </Routes>
    </Suspense>
  );
}