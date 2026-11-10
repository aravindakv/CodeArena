import { Link, NavLink, Outlet, useNavigate } from "react-router";
import { useAuth } from "../auth/AuthProvider";

function Logo() {
  return (
    <Link to="/" className="flex items-center gap-2.5 font-display font-extrabold text-lg tracking-tight">
      <span className="flex gap-[3px]" aria-hidden>
        <span className="cell pass" /><span className="cell pass" /><span className="cell run" />
      </span>
      CodeArena
    </Link>
  );
}

const linkClass = ({ isActive }: { isActive: boolean }) =>
  `px-3 h-9 inline-flex items-center rounded-md text-sm ${isActive ? "text-paper bg-raised" : "text-muted hover:text-paper"}`;

export function Shell() {
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();
  return (
    <div className="min-h-full flex flex-col">
      <header className="sticky top-0 z-20 border-b border-rule bg-night/90 backdrop-blur">
        <div className="mx-auto max-w-[1440px] min-h-14 px-4 sm:px-6 py-2 flex flex-wrap items-center gap-x-6 gap-y-1">
          <Logo />
          <nav className="order-last sm:order-none w-full sm:w-auto -mx-3 sm:mx-0 flex items-center gap-1 overflow-x-auto" aria-label="Main">
            <NavLink to="/problems" className={linkClass}>Problems</NavLink>
            <NavLink to="/contests" className={linkClass}>Contests</NavLink>
            {user && <NavLink to="/me" className={linkClass}>Progress</NavLink>}
            {isAdmin && <NavLink to="/admin" className={linkClass}>Admin</NavLink>}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            {user ? (
              <>
                <span className="hidden sm:inline text-muted">{user.name}</span>
                <button className="text-muted hover:text-paper"
                  onClick={async () => { await logout(); navigate("/"); }}>Log out</button>
              </>
            ) : (
              <>
                <Link to="/login" className="text-muted hover:text-paper">Log in</Link>
                <Link to="/register" className="h-9 px-3 inline-flex items-center rounded-md bg-signal text-signal-ink font-semibold">
                  Create account
                </Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="flex-1">
        <Outlet />
      </main>
    </div>
  );
}