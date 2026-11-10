import { Navigate, useLocation } from "react-router";
import { useAuth } from "./AuthProvider";
import type { Role } from "../api/types";

/** Hides a route from people without the role. The API enforces the same rule on every call. */
export function RequireRole({ role, children }: { role: Role; children: React.ReactNode }) {
  const { user, ready } = useAuth();
  const location = useLocation();
  if (!ready) return null;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  if (!user.roles.includes(role)) return <Navigate to="/problems" replace />;
  return <>{children}</>;
}