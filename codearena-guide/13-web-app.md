# 13 · The web app

React 19, TypeScript 7, Vite 8, Tailwind CSS 4.3, TanStack Query 5, React Router 8, Monaco 0.57 (the editor inside VS Code), react-resizable-panels 4. Needs Node 22.12 or newer (Node 24 LTS recommended).

Two API changes in these versions show up in the code: react-resizable-panels 4 renamed `PanelGroup` / `PanelResizeHandle` to `Group` / `Separator` (and plain numbers now mean pixels, so sizes are written as `"42%"`), and Monaco 0.56+ exposes its workers through the package's export map (`monaco-editor/editor/editor.worker?worker`).

## Design

The audience is people practising algorithms and competing in timed fests, so the visual identity comes from that
world rather than a generic dashboard:

| Choice | Value |
| --- | --- |
| Palette | night `#1A1D3A` (deep indigo, not black), panel `#232750`, rule `#373D6E`, paper `#EDEFFF`, muted `#9EA4D2`, signal amber `#FFB627` (a scoreboard bulb), pass `#41D69A`, fail `#FF6B6B` |
| Meaning of colour | green and red only ever mean pass and fail; amber marks the one thing to act on |
| Type | Bricolage Grotesque for headings, IBM Plex Sans for the interface, JetBrains Mono only for code |
| Signature element | the **test strip**: one cell per test case filling green, with the failing one red. It is the landing page's single animation (a submission being judged), the real verdict display in the workspace, and the logo |
| Layout | a dense, left-aligned problem table; a split-pane workspace with draggable dividers; no card grids |
| Quality floor | keyboard focus visible, reduced-motion respected, phone layouts down to 360 px, Monaco loaded only on the problem page |

| Page | Route | Who |
| --- | --- | --- |
| Landing with live judging demo and next-fest countdown | `/` | everyone |
| Problem list: 100 per page, search, difficulty, topic, solved ticks, acceptance | `/problems?page=2` | everyone |
| Workspace: statement, examples, hints; editor; Run, Submit, custom input; verdict with test strip; history | `/problems/:slug` | everyone reads; logged-in users run |
| Fests | `/contests`, `/contests/:id` | everyone; register and submit when logged in |
| Progress: totals, streaks, heatmap, difficulty, languages, topics | `/me` | USER |
| Admin console: overview, review queue, AI ingestion, new problem, fests, users | `/admin/*` | ADMIN only (route guard + the API's own checks) |

Monaco is bundled locally instead of loading from a CDN, so the editor works offline and under a strict
Content-Security-Policy. The access token lives only in memory; the refresh cookie restores the session on reload.

## Setup

**File:** `frontend/package.json`

```json
{
  "name": "codearena-web",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "typecheck": "tsc -b"
  },
  "dependencies": {
    "@fontsource/bricolage-grotesque": "^5.3.0",
    "@fontsource/ibm-plex-sans": "^5.3.0",
    "@fontsource/jetbrains-mono": "^5.3.0",
    "@monaco-editor/react": "^4.7.0",
    "@tanstack/react-query": "^5.107.1",
    "canvas-confetti": "^1.9.4",
    "monaco-editor": "^0.57.0",
    "react": "^19.3.1",
    "react-dom": "^19.3.1",
    "react-markdown": "^10.1.0",
    "react-resizable-panels": "^4.19.1",
    "react-router": "^8.5.1",
    "rehype-sanitize": "^6.0.0",
    "remark-gfm": "^4.0.1"
  },
  "devDependencies": {
    "@tailwindcss/vite": "^4.3.5",
    "@types/canvas-confetti": "^1.9.0",
    "@types/react": "^19.3.0",
    "@types/react-dom": "^19.3.0",
    "@vitejs/plugin-react": "^6.2.1",
    "tailwindcss": "^4.3.5",
    "typescript": "^7.0.2",
    "vite": "^8.3.6"
  },
  "engines": {
    "node": ">=22.12"
  }
}
```

**File:** `frontend/vite.config.ts`

```ts
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// The dev server proxies /api to the gateway, so the browser sees one origin (no CORS, cookies just work).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { "/api": { target: "http://localhost:9080", changeOrigin: false } },
  },
  build: {
    chunkSizeWarningLimit: 4000, // Monaco is large; it is only loaded on the problem page
  },
});
```

**File:** `frontend/tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "skipLibCheck": true,
    "isolatedModules": true,
    "noEmit": true,
    "types": ["vite/client"]
  },
  "include": ["src", "vite.config.ts"]
}
```

**File:** `frontend/index.html`

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <meta name="theme-color" content="#1A1D3A" />
    <link rel="icon" href="/favicon.svg" type="image/svg+xml" />
    <title>CodeArena</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

**File:** `frontend/public/favicon.svg`

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="7" fill="#1A1D3A"/><rect x="6" y="13" width="5" height="6" rx="1.5" fill="#41D69A"/><rect x="13.5" y="13" width="5" height="6" rx="1.5" fill="#41D69A"/><rect x="21" y="13" width="5" height="6" rx="1.5" fill="#FFB627"/></svg>
```

**File:** `frontend/src/styles.css`

```css
@import "tailwindcss";

/* CodeArena tokens. Indigo night + scoreboard amber; green/red only ever mean pass/fail. */
@theme {
  --color-night: #1a1d3a;
  --color-panel: #232750;
  --color-raised: #2c3160;
  --color-rule: #373d6e;
  --color-paper: #edefff;
  --color-muted: #9ea4d2;
  --color-signal: #ffb627;
  --color-signal-ink: #2a1c00;
  --color-pass: #41d69a;
  --color-fail: #ff6b6b;
  --color-easy: #41d69a;
  --color-medium: #ffb627;
  --color-hard: #ff6b6b;

  --font-display: "Bricolage Grotesque", "IBM Plex Sans", system-ui, sans-serif;
  --font-sans: "IBM Plex Sans", system-ui, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, monospace;
}

html, body, #root { height: 100%; }
body {
  background: var(--color-night);
  color: var(--color-paper);
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.55;
  -webkit-font-smoothing: antialiased;
}

:focus-visible { outline: 2px solid var(--color-signal); outline-offset: 2px; border-radius: 4px; }
::selection { background: color-mix(in srgb, var(--color-signal) 35%, transparent); }

/* Markdown problem statements */
.statement { max-width: 68ch; }
.statement p { margin: 0 0 0.9em; }
.statement ul { list-style: disc; padding-left: 1.3em; margin: 0 0 0.9em; }
.statement code { font-family: var(--font-mono); font-size: 0.88em; background: var(--color-raised);
  padding: 0.1em 0.35em; border-radius: 4px; }
.statement strong { color: #fff; }

/* The test strip: the product's signature element */
.cell { width: 14px; height: 18px; border-radius: 3px; background: var(--color-rule); }
.cell.pass { background: var(--color-pass); }
.cell.fail { background: var(--color-fail); }
.cell.run { background: var(--color-signal); }
.cell.fluid { width: auto; flex: 1 1 0; min-width: 4px; }
.font-mono, pre, code { font-variant-ligatures: none; }
@keyframes fill-in { from { transform: scaleY(0.3); opacity: 0.4; } to { transform: scaleY(1); opacity: 1; } }
.cell.pass, .cell.fail { animation: fill-in 180ms ease-out both; }

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: 0.01ms !important; transition-duration: 0.01ms !important; }
}
```

**File:** `frontend/src/main.tsx`

```tsx
import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@fontsource/bricolage-grotesque/600.css";
import "@fontsource/bricolage-grotesque/800.css";
import "@fontsource/ibm-plex-sans/400.css";
import "@fontsource/ibm-plex-sans/500.css";
import "@fontsource/ibm-plex-sans/600.css";
import "@fontsource/jetbrains-mono/400.css";
import "./styles.css";
import { AuthProvider } from "./auth/AuthProvider";
import { App } from "./App";

const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, retry: 1, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
```

**File:** `frontend/src/App.tsx`

```tsx
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
```

## API client and auth

**File:** `frontend/src/api/types.ts`

```ts
// Response shapes of the /api/v1 endpoints (kept by hand; generate them from OpenAPI once the API settles).

export type Difficulty = "EASY" | "MEDIUM" | "HARD";
export type Role = "USER" | "ADMIN";

export interface ProblemSummary {
  number: number;
  slug: string;
  title: string;
  difficulty: Difficulty;
  tags: string[];
  acceptance: number;
}

export interface ProblemPage {
  items: ProblemSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Example {
  input: string;
  output: string;
  explanation?: string;
}

export interface ProblemDetail {
  number: number;
  slug: string;
  title: string;
  difficulty: Difficulty;
  tags: string[];
  statement: string;
  constraints: string[];
  examples: Example[];
  hints: string[];
  limits: { timeMs: number; memoryMb: number };
  acceptance: number;
  languages: string[];
}

export interface Language {
  id: string;
  label: string;
  monacoId: string;
}

export type Verdict =
  | "PENDING" | "ACCEPTED" | "WRONG_ANSWER" | "TIME_LIMIT_EXCEEDED" | "MEMORY_LIMIT_EXCEEDED"
  | "RUNTIME_ERROR" | "COMPILATION_ERROR" | "OUTPUT_LIMIT_EXCEEDED" | "SYSTEM_ERROR";

export interface Submission {
  id: string;
  slug: string;
  lang: string;
  mode: "RUN" | "SUBMIT";
  contestId?: string;
  status: Verdict;
  createdAt: string;
  runtimeMs: number;
  memoryKb: number;
  passed: number;
  total: number;
  message?: string;
  failedInput?: string;
  expected?: string;
  actual?: string;
  stdout?: string;
  customOutput?: string;
}

export interface Contest {
  id: string;
  title: string;
  description?: string;
  startAt: string;
  endAt: string;
  phase: "UPCOMING" | "LIVE" | "ENDED";
  problems: string[];
  frozen: boolean;
  finalized: boolean;
}

export interface LeaderboardRow {
  rank: number;
  userId: string;
  name: string;
  solved: number;
  penalty: number;
  problems: Record<string, { solvedAtMin: number; wrong: number }>;
}

export interface Leaderboard {
  contestId: string;
  generatedAt?: string;
  frozen?: boolean;
  problems?: string[];
  participants: number;
  rows: LeaderboardRow[];
}

export interface Stats {
  submissions: number;
  accepted: number;
  solvedTotal: number;
  solvedByDifficulty: Record<string, number>;
  byLanguage: Record<string, number>;
  byTag: Record<string, number>;
  currentStreak: number;
  longestStreak: number;
  heatmap: Record<string, number>;
  recentSolved: { slug: string; at: string; difficulty: string }[];
}

export interface Me {
  id: string;
  email: string;
  displayName: string;
  roles: Role[];
  createdAt: string;
}
```

**File:** `frontend/src/api/client.ts`

```ts
// One fetch wrapper for the whole app: bearer token, silent refresh on 401, Problem Details errors.
// The access token lives only in memory; the refresh token is an HttpOnly cookie the browser sends itself.

let accessToken: string | null = null;
let onAuthLost: () => void = () => {};

export function setAccessToken(t: string | null) {
  accessToken = t;
}
export function setOnAuthLost(fn: () => void) {
  onAuthLost = fn;
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public body?: unknown) {
    super(message);
  }
}

let refreshing: Promise<string | null> | null = null;

export async function refreshAccessToken(): Promise<string | null> {
  refreshing ??= fetch("/api/v1/auth/refresh", { method: "POST", credentials: "include" })
    .then(async (r) => (r.ok ? ((await r.json()).accessToken as string) : null))
    .catch(() => null)
    .finally(() => setTimeout(() => (refreshing = null), 0));
  const t = await refreshing;
  accessToken = t;
  return t;
}

type Body = Record<string, unknown> | unknown[] | string | undefined;

export async function api<T>(path: string, init: { method?: string; body?: Body; headers?: Record<string, string> } = {}, retried = false): Promise<T> {
  const headers: Record<string, string> = { ...(init.headers ?? {}) };
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  let body: string | undefined;
  if (typeof init.body === "string") body = init.body;
  else if (init.body !== undefined) {
    body = JSON.stringify(init.body);
    headers["Content-Type"] = "application/json";
  }
  const res = await fetch(`/api/v1${path}`, { method: init.method ?? "GET", headers, body, credentials: "include" });

  if (res.status === 401 && !retried && !path.startsWith("/auth/")) {
    const t = await refreshAccessToken();
    if (t) return api<T>(path, init, true);
    onAuthLost();
  }
  if (!res.ok) {
    let data: { code?: string; detail?: string; errors?: string[] } = {};
    try {
      data = await res.json();
    } catch {
      /* not JSON */
    }
    const message =
      res.status === 429 ? "You're going too fast. Wait a moment and try again."
      : data.errors?.join("; ") || data.detail || `Request failed (${res.status})`;
    throw new ApiError(res.status, data.code ?? String(res.status), message, data);
  }
  if (res.status === 204) return undefined as T;
  const type = res.headers.get("content-type") ?? "";
  return (type.includes("json") ? await res.json() : await res.text()) as T;
}
```

**File:** `frontend/src/auth/AuthProvider.tsx`

```tsx
import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { api, refreshAccessToken, setAccessToken, setOnAuthLost } from "../api/client";
import type { Role } from "../api/types";

export interface User {
  id: string;
  name: string;
  roles: Role[];
}

interface AuthState {
  user: User | null;
  ready: boolean;
  isAdmin: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState>(null!);

/** Reads display data from the token. The server never trusts this; it verifies the signature. */
function userFromToken(token: string): User {
  const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
  return { id: payload.sub, name: payload.name, roles: payload.roles ?? [] };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [ready, setReady] = useState(false);

  const apply = useCallback((token: string | null) => {
    setAccessToken(token);
    setUser(token ? userFromToken(token) : null);
  }, []);

  useEffect(() => {
    setOnAuthLost(() => apply(null));
    // A refresh cookie from an earlier visit logs the user straight in.
    refreshAccessToken().then((t) => apply(t)).finally(() => setReady(true));
  }, [apply]);

  // Renew the access token shortly before it expires (5 min for admins, 15 for users).
  useEffect(() => {
    if (!user) return;
    const every = user.roles.includes("ADMIN") ? 4 * 60_000 : 13 * 60_000;
    const id = setInterval(() => refreshAccessToken().then(apply), every);
    return () => clearInterval(id);
  }, [user, apply]);

  const login = useCallback(async (email: string, password: string) => {
    const t = await api<{ accessToken: string }>("/auth/login", { method: "POST", body: { email, password } });
    apply(t.accessToken);
  }, [apply]);

  const register = useCallback(async (email: string, password: string, displayName: string) => {
    await api("/auth/register", { method: "POST", body: { email, password, displayName } });
    await login(email, password);
  }, [login]);

  const logout = useCallback(async () => {
    try {
      await api("/auth/logout", { method: "POST" });
    } finally {
      apply(null);
    }
  }, [apply]);

  const value = useMemo(
    () => ({ user, ready, isAdmin: !!user?.roles.includes("ADMIN"), login, register, logout }),
    [user, ready, login, register, logout],
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
```

**File:** `frontend/src/auth/RequireRole.tsx`

```tsx
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
```

## Components

**File:** `frontend/src/components/Pager.tsx`

```tsx
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
```

**File:** `frontend/src/components/Shell.tsx`

```tsx
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
```

**File:** `frontend/src/components/TestStrip.tsx`

```tsx
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
```

**File:** `frontend/src/components/ui.tsx`

```tsx
import { forwardRef } from "react";
import type { Difficulty } from "../api/types";

type ButtonProps = React.ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "primary" | "quiet" | "danger" };

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = "primary", className = "", ...props }, ref) {
  const look = {
    primary: "bg-signal text-signal-ink hover:brightness-105 font-semibold",
    quiet: "bg-raised text-paper hover:bg-rule",
    danger: "bg-fail/15 text-fail hover:bg-fail/25",
  }[variant];
  return (
    <button ref={ref} {...props}
      className={`inline-flex items-center justify-center gap-2 rounded-lg px-4 h-10 text-sm transition-colors
        disabled:opacity-50 disabled:cursor-not-allowed ${look} ${className}`} />
  );
});

export function Field({ label, error, ...props }: React.InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string }) {
  return (
    <label className="block">
      <span className="block text-sm text-muted mb-1.5">{label}</span>
      <input {...props}
        className="w-full h-11 rounded-lg bg-night border border-rule px-3 text-paper placeholder:text-muted/60
          focus:border-signal focus:outline-none" />
      {error && <span className="block text-sm text-fail mt-1">{error}</span>}
    </label>
  );
}

const DIFF: Record<Difficulty, string> = { EASY: "text-easy", MEDIUM: "text-medium", HARD: "text-hard" };
const DIFF_LABEL: Record<Difficulty, string> = { EASY: "Easy", MEDIUM: "Medium", HARD: "Hard" };

export function DifficultyTag({ value }: { value: Difficulty }) {
  return <span className={`font-medium ${DIFF[value]}`}>{DIFF_LABEL[value]}</span>;
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div className={`animate-pulse rounded-md bg-raised ${className}`} />;
}

export function ErrorNote({ error }: { error: unknown }) {
  const msg = error instanceof Error ? error.message : "Something went wrong.";
  return <p role="alert" className="rounded-lg bg-fail/10 text-fail px-4 py-3 text-sm">{msg}</p>;
}

export function PageTitle({ children, aside }: { children: React.ReactNode; aside?: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4 mb-6">
      <h1 className="font-display font-extrabold text-3xl sm:text-4xl tracking-tight">{children}</h1>
      {aside}
    </div>
  );
}
```

**File:** `frontend/src/editor/monaco.ts`

```ts
// Bundle Monaco locally (no CDN), so the editor works offline and under a strict Content-Security-Policy.
// Paths use the package's exports map (monaco-editor 0.56+ reorganized its ESM entry points).
import { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import TsWorker from "monaco-editor/languages/features/typescript/ts.worker?worker";

self.MonacoEnvironment = {
  getWorker(_id: string, label: string) {
    if (label === "typescript" || label === "javascript") return new TsWorker();
    return new EditorWorker();
  },
};

monaco.editor.defineTheme("codearena", {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "keyword", foreground: "FFB627" },
    { token: "comment", foreground: "7C82B4", fontStyle: "italic" },
    { token: "string", foreground: "8EE6C0" },
    { token: "number", foreground: "F7A1C4" },
  ],
  colors: {
    "editor.background": "#1A1D3A",
    "editor.lineHighlightBackground": "#232750",
    "editorLineNumber.foreground": "#5A6098",
    "editorLineNumber.activeForeground": "#EDEFFF",
    "editorCursor.foreground": "#FFB627",
    "editor.selectionBackground": "#3B4380",
    "editorIndentGuide.background1": "#2C3160",
  },
});

loader.config({ monaco });

export { monaco };
```

## Pages

**File:** `frontend/src/pages/AuthPages.tsx`

```tsx
import { useState } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router";
import { useAuth } from "../auth/AuthProvider";
import { Button, ErrorNote, Field } from "../components/ui";

function Frame({ title, children, foot }: { title: string; children: React.ReactNode; foot: React.ReactNode }) {
  return (
    <div className="mx-auto max-w-md px-4 py-16">
      <h1 className="font-display font-extrabold text-3xl mb-8">{title}</h1>
      {children}
      <p className="mt-6 text-sm text-muted">{foot}</p>
    </div>
  );
}

export function Login() {
  const { login, user } = useAuth();
  const navigate = useNavigate();
  const from = (useLocation().state as { from?: string } | null)?.from ?? "/problems";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  if (user) return <Navigate to={from} replace />;

  return (
    <Frame title="Log in" foot={<>New here? <Link className="text-signal hover:underline" to="/register">Create an account</Link></>}>
      <form className="space-y-4" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setError(undefined);
        try { await login(email, password); navigate(from, { replace: true }); } catch (err) { setError(err); } finally { setBusy(false); }
      }}>
        {error !== undefined && <ErrorNote error={error} />}
        <Field label="Email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field label="Password" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <Button className="w-full h-11" disabled={busy}>{busy ? "Logging in…" : "Log in"}</Button>
      </form>
    </Frame>
  );
}

export function Register() {
  const { register, user } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ email: "", password: "", displayName: "" });
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  if (user) return <Navigate to="/problems" replace />;
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value });

  return (
    <Frame title="Create your account" foot={<>Already registered? <Link className="text-signal hover:underline" to="/login">Log in</Link></>}>
      <form className="space-y-4" onSubmit={async (e) => {
        e.preventDefault(); setBusy(true); setError(undefined);
        try { await register(form.email, form.password, form.displayName); navigate("/problems"); } catch (err) { setError(err); } finally { setBusy(false); }
      }}>
        {error !== undefined && <ErrorNote error={error} />}
        <Field label="Display name (shown on leaderboards)" required minLength={3} maxLength={24} pattern="[A-Za-z0-9_.\-]{3,24}"
          title="3 to 24 letters, digits, _ . -" value={form.displayName} onChange={set("displayName")} />
        <Field label="Email" type="email" autoComplete="email" required value={form.email} onChange={set("email")} />
        <Field label="Password (at least 8 characters)" type="password" autoComplete="new-password" required minLength={8}
          value={form.password} onChange={set("password")} />
        <Button className="w-full h-11" disabled={busy}>{busy ? "Creating…" : "Create account"}</Button>
      </form>
    </Frame>
  );
}
```

**File:** `frontend/src/pages/ContestPage.tsx`

```tsx
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest, Leaderboard, ProblemDetail } from "../api/types";
import { useAuth } from "../auth/AuthProvider";
import { Button, DifficultyTag, ErrorNote, Skeleton } from "../components/ui";

function useNow() {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);
  return now;
}

function clock(ms: number) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function ContestPage() {
  const { id = "" } = useParams();
  const { user } = useAuth();
  const now = useNow();
  const qc = useQueryClient();
  const contest = useQuery({ queryKey: ["contest", id], queryFn: () => api<Contest>(`/contests/${id}`), refetchInterval: 30_000 });
  const c = contest.data;

  // Requirement: the leaderboard refreshes every 5 seconds (the server rebuilds its snapshot at the same rate).
  const board = useQuery({
    queryKey: ["leaderboard", id],
    queryFn: () => api<Leaderboard>(`/contests/${id}/leaderboard`),
    refetchInterval: c?.phase === "LIVE" ? 5000 : false,
    enabled: !!c && c.phase !== "UPCOMING",
  });
  const me = useQuery({
    queryKey: ["leaderboard-me", id],
    queryFn: () => api<{ rank: number; solved: number; penalty: number }>(`/contests/${id}/leaderboard/me`),
    refetchInterval: c?.phase === "LIVE" ? 5000 : false,
    enabled: !!user && !!c && c.phase !== "UPCOMING",
  });
  const register = useMutation({
    mutationFn: () => api(`/contests/${id}/register`, { method: "POST" }),
    onSuccess: () => { localStorage.setItem(`registered:${id}:${user?.id}`, "1"); qc.invalidateQueries({ queryKey: ["contest", id] }); },
  });
  const registered = !!user && (localStorage.getItem(`registered:${id}:${user.id}`) === "1" || (me.data?.rank ?? 0) > 0);

  if (contest.error) return <div className="mx-auto max-w-3xl p-10"><ErrorNote error={contest.error} /></div>;
  if (!c) return <div className="mx-auto max-w-5xl p-10"><Skeleton className="h-48" /></div>;

  const toStart = new Date(c.startAt).getTime() - now;
  const toEnd = new Date(c.endAt).getTime() - now;
  const live = toStart <= 0 && toEnd > 0;

  return (
    <div className="mx-auto max-w-[1200px] px-4 sm:px-6 py-10">
      <div className="flex flex-wrap items-end gap-6 mb-8">
        <div className="flex-1 min-w-[18rem]">
          <h1 className="font-display font-extrabold text-4xl tracking-tight">{c.title}</h1>
          {c.description && <p className="text-muted mt-2 max-w-[60ch]">{c.description}</p>}
        </div>
        <div className="text-right">
          <p className="text-sm text-muted">{toStart > 0 ? "Starts in" : live ? "Time left" : "Finished"}</p>
          <p className={`font-display font-extrabold text-4xl tabular-nums ${live ? "text-signal" : ""}`}>
            {toStart > 0 ? clock(toStart) : live ? clock(toEnd) : "0:00:00"}
          </p>
        </div>
      </div>

      {user && !registered && toEnd > 0 && (
        <div className="mb-8 flex flex-wrap items-center gap-4 rounded-xl border border-signal/40 bg-signal/10 px-5 py-4">
          <p className="flex-1">Register to submit during the fest and appear on the leaderboard.</p>
          <Button onClick={() => register.mutate()} disabled={register.isPending}>Register for this fest</Button>
          {register.error && <ErrorNote error={register.error} />}
        </div>
      )}
      {!user && toEnd > 0 && (
        <p className="mb-8 text-muted"><Link to="/login" state={{ from: `/contests/${id}` }} className="text-signal hover:underline">Log in</Link> to register.</p>
      )}

      <div className="grid lg:grid-cols-[320px_1fr] gap-8">
        <section>
          <h2 className="font-display font-semibold text-xl mb-3">Problems</h2>
          {c.problems.length === 0 ? <p className="text-muted text-sm">Problems are revealed when the fest starts.</p> : (
            <ol className="space-y-2">
              {c.problems.map((slug, i) => <ContestProblem key={slug} slug={slug} letter={String.fromCharCode(65 + i)} contestId={id} live={live} />)}
            </ol>
          )}
          {me.data && me.data.rank > 0 && (
            <div className="mt-6 rounded-xl bg-panel border border-rule p-4">
              <p className="text-sm text-muted">Your position</p>
              <p className="font-display font-extrabold text-3xl">#{me.data.rank}</p>
              <p className="text-sm text-muted">{me.data.solved} solved, {me.data.penalty} penalty minutes</p>
            </div>
          )}
        </section>

        <section>
          <div className="flex items-baseline justify-between mb-3">
            <h2 className="font-display font-semibold text-xl">Leaderboard</h2>
            {board.data && <p className="text-sm text-muted">
              {board.data.participants} competing{board.data.frozen ? ", frozen for the final stretch" : live ? ", updates every 5 seconds" : ""}
            </p>}
          </div>
          {c.phase === "UPCOMING" ? <p className="text-muted">The leaderboard opens when the fest starts.</p>
            : !board.data ? <Skeleton className="h-60" /> : (
            <div className="overflow-x-auto rounded-xl border border-rule">
              <table className="w-full text-sm">
                <thead className="bg-panel text-muted text-left">
                  <tr>
                    <th className="w-14 px-3 py-2.5 font-medium">Rank</th>
                    <th className="px-3 py-2.5 font-medium">Name</th>
                    <th className="w-20 px-3 py-2.5 font-medium text-right">Solved</th>
                    <th className="w-24 px-3 py-2.5 font-medium text-right">Penalty</th>
                    {c.problems.map((s, i) => <th key={s} className="w-14 px-2 py-2.5 font-medium text-center" title={s}>{String.fromCharCode(65 + i)}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {board.data.rows.length === 0 && (
                    <tr><td colSpan={4 + c.problems.length} className="px-3 py-8 text-center text-muted">No accepted solutions yet. The first one takes the top spot.</td></tr>
                  )}
                  {board.data.rows.map((r) => (
                    <tr key={r.userId} className={`border-t border-rule transition-colors ${r.userId === user?.id ? "bg-signal/10" : ""}`}>
                      <td className={`px-3 py-2 font-display font-semibold tabular-nums ${r.rank <= 3 ? "text-signal" : ""}`}>{r.rank}</td>
                      <td className="px-3 py-2">{r.name}</td>
                      <td className="px-3 py-2 text-right tabular-nums">{r.solved}</td>
                      <td className="px-3 py-2 text-right tabular-nums text-muted">{r.penalty}</td>
                      {c.problems.map((s) => {
                        const cell = r.problems?.[s];
                        if (!cell) return <td key={s} />;
                        const solved = cell.solvedAtMin >= 0;
                        return (
                          <td key={s} className="px-1 py-1.5 text-center">
                            <span className={`inline-block min-w-11 rounded px-1.5 py-0.5 text-xs tabular-nums ${solved ? "bg-pass/20 text-pass" : "bg-fail/15 text-fail"}`}
                              title={solved ? `solved at minute ${cell.solvedAtMin}, ${cell.wrong} wrong attempts` : `${cell.wrong} wrong attempts`}>
                              {solved ? `${cell.solvedAtMin}'` : `−${cell.wrong}`}{solved && cell.wrong > 0 ? ` +${cell.wrong}` : ""}
                            </span>
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <p className="text-xs text-muted mt-3">Ranked by problems solved, then penalty: minutes to each accepted solution plus 20 per wrong attempt before it.</p>
        </section>
      </div>
    </div>
  );
}

function ContestProblem({ slug, letter, contestId, live }: { slug: string; letter: string; contestId: string; live: boolean }) {
  const p = useQuery({ queryKey: ["problem", slug], queryFn: () => api<ProblemDetail>(`/problems/${slug}`), staleTime: 300_000 });
  return (
    <li>
      <Link to={live ? `/problems/${slug}?contest=${contestId}` : `/problems/${slug}`}
        className="flex items-center gap-3 rounded-lg border border-rule px-3 py-2.5 hover:border-signal">
        <span className="font-display font-extrabold text-signal w-5">{letter}</span>
        <span className="flex-1">{p.data?.title ?? slug}</span>
        {p.data && <span className="text-sm"><DifficultyTag value={p.data.difficulty} /></span>}
      </Link>
    </li>
  );
}
```

**File:** `frontend/src/pages/Contests.tsx`

```tsx
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest } from "../api/types";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

const fmt = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
const PHASE = { LIVE: "Live now", UPCOMING: "Upcoming", ENDED: "Ended" } as const;

export function Contests() {
  const q = useQuery({ queryKey: ["contests"], queryFn: () => api<Contest[]>("/contests"), refetchInterval: 30_000 });
  const order = { LIVE: 0, UPCOMING: 1, ENDED: 2 };
  const contests = [...(q.data ?? [])].sort((a, b) => order[a.phase] - order[b.phase] || b.startAt.localeCompare(a.startAt));

  return (
    <div className="mx-auto max-w-[1000px] px-4 sm:px-6 py-10">
      <PageTitle>Coding fests</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {q.isLoading && <Skeleton className="h-40" />}
      {q.data && contests.length === 0 && <p className="text-muted">No fests are scheduled yet. Check back soon.</p>}
      <ul className="divide-y divide-rule border-y border-rule">
        {contests.map((c) => (
          <li key={c.id}>
            <Link to={`/contests/${c.id}`} className="flex flex-wrap items-center gap-4 py-5 hover:bg-panel/50 px-2 -mx-2 rounded-lg">
              <span className={`w-24 text-sm font-medium ${c.phase === "LIVE" ? "text-signal" : "text-muted"}`}>
                {c.phase === "LIVE" && <span className="inline-block w-2 h-2 rounded-full bg-signal mr-2 animate-pulse" />}
                {PHASE[c.phase]}
              </span>
              <span className="flex-1 font-display font-semibold text-lg">{c.title}</span>
              <span className="text-sm text-muted">{fmt(c.startAt)} to {fmt(c.endAt)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
```

**File:** `frontend/src/pages/Landing.tsx`

```tsx
import { useEffect, useState } from "react";
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Contest, ProblemPage } from "../api/types";
import { TestStrip } from "../components/TestStrip";
import { useAuth } from "../auth/AuthProvider";

const DEMO_TOTAL = 30;
const DEMO_CODE = [
  ["def", " longestUniqueRun", "(self, s):"],
  ["    ", "last, left, best = {}, 0, 0", ""],
  ["    for", " right, ch ", "in enumerate(s):"],
  ["        if", " last.get(ch, -1) >= left:", ""],
  ["            ", "left = last[ch] + 1", ""],
  ["        ", "last[ch] = right", ""],
  ["        ", "best = max(best, right - left + 1)", ""],
  ["    return", " best", ""],
];

/** The one orchestrated motion on the site: a submission being judged, test by test. */
function JudgingDemo() {
  const [passed, setPassed] = useState(0);
  useEffect(() => {
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { setPassed(DEMO_TOTAL); return; }
    let n = 0;
    const id = setInterval(() => { n += 1; setPassed(n); if (n >= DEMO_TOTAL) clearInterval(id); }, 70);
    return () => clearInterval(id);
  }, []);
  const done = passed >= DEMO_TOTAL;
  return (
    <div className="rounded-2xl bg-panel border border-rule overflow-hidden shadow-[0_30px_80px_-30px_rgba(0,0,0,0.6)]">
      <div className="flex items-center justify-between px-5 h-11 border-b border-rule text-sm">
        <span className="text-muted">Longest Run Without Repeats</span>
        <span className="text-muted">Python 3.13</span>
      </div>
      <pre className="font-mono text-[13px] leading-6 px-5 py-4 overflow-x-auto" aria-label="Example solution">
        {DEMO_CODE.map((l, i) => (
          <div key={i}><span className="text-signal">{l[0]}</span><span>{l[1]}</span><span className="text-muted">{l[2]}</span></div>
        ))}
      </pre>
      <div className="border-t border-rule px-5 py-4 bg-night/40">
        <TestStrip total={DEMO_TOTAL} passed={passed} running={!done} fluid />
        <p className="mt-3 text-sm h-5" aria-live="polite">
          {done
            ? <span className="text-pass font-semibold">Accepted in 41 ms, all 30 tests passed</span>
            : <span className="text-muted">Running test {passed + 1} of {DEMO_TOTAL}</span>}
        </p>
      </div>
    </div>
  );
}

function useCountdown(to?: string) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []);
  if (!to) return null;
  const ms = Math.max(0, new Date(to).getTime() - now);
  const d = Math.floor(ms / 86_400_000), h = Math.floor(ms / 3_600_000) % 24, m = Math.floor(ms / 60_000) % 60, s = Math.floor(ms / 1000) % 60;
  return d > 0 ? `${d}d ${h}h ${m}m` : `${h}h ${String(m).padStart(2, "0")}m ${String(s).padStart(2, "0")}s`;
}

export function Landing() {
  const { user } = useAuth();
  const contests = useQuery({ queryKey: ["contests"], queryFn: () => api<Contest[]>("/contests") });
  const problems = useQuery({ queryKey: ["problems", 1, "", "", ""], queryFn: () => api<ProblemPage>("/problems?page=1&size=100") });
  const next = contests.data?.filter((c) => c.phase !== "ENDED").sort((a, b) => a.startAt.localeCompare(b.startAt))[0];
  const countdown = useCountdown(next ? (next.phase === "LIVE" ? next.endAt : next.startAt) : undefined);

  return (
    <div className="mx-auto max-w-[1200px] px-4 sm:px-6">
      <section className="grid lg:grid-cols-[1.05fr_1fr] gap-12 items-center py-16 lg:py-24">
        <div>
          <h1 className="font-display font-extrabold tracking-tight text-[clamp(2.6rem,6vw,4.6rem)] leading-[0.98]">
            Solve it. Then watch every test decide.
          </h1>
          <p className="mt-6 text-lg text-muted max-w-[46ch]">
            Practise algorithms in Java, Python, C++, JavaScript or Go. Your code runs against hidden tests in an
            isolated sandbox, and coding fests rank everyone live.
          </p>
          <div className="mt-8 flex flex-wrap gap-3">
            <Link to="/problems" className="h-12 px-6 inline-flex items-center rounded-xl bg-signal text-signal-ink font-semibold">
              Browse {problems.data ? problems.data.totalItems : ""} problems
            </Link>
            {!user && (
              <Link to="/register" className="h-12 px-6 inline-flex items-center rounded-xl bg-raised hover:bg-rule">
                Create a free account
              </Link>
            )}
          </div>
        </div>
        <JudgingDemo />
      </section>

      {next && (
        <section className="mb-20 rounded-2xl border border-rule px-6 py-6 flex flex-wrap items-center gap-6">
          <div className="flex-1 min-w-[16rem]">
            <p className="text-sm text-muted">{next.phase === "LIVE" ? "Fest in progress, ends in" : "Next fest starts in"}</p>
            <p className="font-display font-extrabold text-3xl tabular-nums">{countdown}</p>
            <p className="mt-1">{next.title}</p>
          </div>
          <Link to={`/contests/${next.id}`} className="h-11 px-5 inline-flex items-center rounded-xl bg-raised hover:bg-rule">
            {next.phase === "LIVE" ? "Open the live leaderboard" : "See the fest and register"}
          </Link>
        </section>
      )}
    </div>
  );
}
```

**File:** `frontend/src/pages/NotFound.tsx`

```tsx
import { Link } from "react-router";

export function NotFound() {
  return (
    <div className="mx-auto max-w-xl px-6 py-24">
      <h1 className="font-display font-extrabold text-4xl mb-3">Nothing at this address</h1>
      <p className="text-muted mb-6">The link may be old, or the problem may have been retired.</p>
      <Link to="/problems" className="text-signal hover:underline">Go to the problem list</Link>
    </div>
  );
}
```

**File:** `frontend/src/pages/Problems.tsx`

```tsx
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
```

**File:** `frontend/src/pages/Profile.tsx`

```tsx
import { Link } from "react-router";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { Stats } from "../api/types";
import { useAuth } from "../auth/AuthProvider";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

/** 53 weeks x 7 days, Monday first, ending today. */
function Heatmap({ data }: { data: Record<string, number> }) {
  const today = new Date();
  const days: { date: string; n: number }[] = [];
  const start = new Date(today);
  start.setDate(start.getDate() - 7 * 52 - ((today.getDay() + 6) % 7));
  for (const d = new Date(start); d <= today; d.setDate(d.getDate() + 1)) {
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
    days.push({ date: key, n: data[key] ?? 0 });
  }
  const shade = (n: number) => n === 0 ? "bg-raised" : n < 3 ? "bg-pass/35" : n < 6 ? "bg-pass/65" : "bg-pass";
  return (
    <div className="overflow-x-auto">
      <div className="grid grid-rows-7 grid-flow-col gap-[3px] w-max" role="img" aria-label="Submissions per day over the last year">
        {days.map((d) => <span key={d.date} title={`${d.date}: ${d.n} submissions`} className={`w-3 h-3 rounded-[3px] ${shade(d.n)}`} />)}
      </div>
    </div>
  );
}

function Bars({ data, total }: { data: Record<string, number>; total?: number }) {
  const entries = Object.entries(data).sort((a, b) => b[1] - a[1]).slice(0, 8);
  const max = total ?? Math.max(1, ...entries.map((e) => e[1]));
  if (!entries.length) return <p className="text-sm text-muted">Nothing yet.</p>;
  return (
    <ul className="space-y-2">
      {entries.map(([k, v]) => (
        <li key={k} className="grid grid-cols-[8rem_1fr_3rem] items-center gap-3 text-sm">
          <span className="truncate">{k}</span>
          <span className="h-2 rounded-full bg-raised overflow-hidden"><span className="block h-full bg-signal" style={{ width: `${(100 * v) / max}%` }} /></span>
          <span className="text-right tabular-nums text-muted">{v}</span>
        </li>
      ))}
    </ul>
  );
}

export function Profile() {
  const { user } = useAuth();
  const q = useQuery({ queryKey: ["stats", user?.id], queryFn: () => api<Stats>("/me/stats") });
  const s = q.data;
  return (
    <div className="mx-auto max-w-[1100px] px-4 sm:px-6 py-10">
      <PageTitle>{user?.name}'s progress</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {!s ? <Skeleton className="h-64" /> : (
        <div className="space-y-10">
          <dl className="grid grid-cols-2 md:grid-cols-4 gap-x-8 gap-y-6">
            {[["Problems solved", s.solvedTotal], ["Current streak", `${s.currentStreak} days`],
              ["Longest streak", `${s.longestStreak} days`],
              ["Acceptance", s.submissions ? `${Math.round((100 * s.accepted) / s.submissions)}%` : "–"]].map(([k, v]) => (
              <div key={k}><dt className="text-sm text-muted">{k}</dt><dd className="font-display font-extrabold text-4xl">{v}</dd></div>
            ))}
          </dl>
          <section>
            <h2 className="font-display font-semibold text-xl mb-3">Activity</h2>
            <Heatmap data={s.heatmap} />
          </section>
          <div className="grid md:grid-cols-3 gap-10">
            <section><h2 className="font-display font-semibold text-xl mb-3">By difficulty</h2>
              <Bars data={{ Easy: s.solvedByDifficulty.EASY ?? 0, Medium: s.solvedByDifficulty.MEDIUM ?? 0, Hard: s.solvedByDifficulty.HARD ?? 0 }} /></section>
            <section><h2 className="font-display font-semibold text-xl mb-3">Languages</h2><Bars data={s.byLanguage} /></section>
            <section><h2 className="font-display font-semibold text-xl mb-3">Topics</h2><Bars data={s.byTag} /></section>
          </div>
          <section>
            <h2 className="font-display font-semibold text-xl mb-3">Recently solved</h2>
            {s.recentSolved.length === 0 ? <p className="text-muted">Solve your first problem and it shows up here. <Link className="text-signal hover:underline" to="/problems">Pick one</Link></p> : (
              <ul className="divide-y divide-rule border-y border-rule">
                {s.recentSolved.map((r) => (
                  <li key={r.slug} className="flex justify-between py-2.5">
                    <Link to={`/problems/${r.slug}`} className="hover:text-signal">{r.slug.replace(/-/g, " ")}</Link>
                    <span className="text-sm text-muted">{new Date(r.at).toLocaleDateString()}</span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
```

**File:** `frontend/src/pages/Workspace.tsx`

```tsx
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
```

## Admin console

**File:** `frontend/src/admin/Admin.tsx`

```tsx
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
```

**File:** `frontend/src/admin/AdminContests.tsx`

```tsx
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
```

**File:** `frontend/src/admin/Ingestion.tsx`

```tsx
import { useState } from "react";
import { Link, useParams } from "react-router";
import { useMutation, useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { Button, ErrorNote, Field, PageTitle, Skeleton } from "../components/ui";

interface RunRow { runId: string; createdAt: string; total: string }
interface RunView {
  runId: string; createdAt: string; total: number; stages: Record<string, number>;
  seeds: { id: string; topic: string; difficulty: string; stage: string; slug?: string; error?: string; updatedAt: string }[];
}

const STAGE_COLOR: Record<string, string> = {
  QUEUED: "text-muted", GENERATING: "text-signal", VERIFYING: "text-signal",
  READY_FOR_REVIEW: "text-pass", FAILED: "text-fail", DUPLICATE: "text-muted",
};

export function Ingestion() {
  const [topics, setTopics] = useState("");
  const [count, setCount] = useState(10);
  const [diffs, setDiffs] = useState<string[]>(["EASY", "MEDIUM"]);
  const runs = useQuery({ queryKey: ["ingestion-runs"], queryFn: () => api<RunRow[]>("/admin/ingestion/runs") });
  const start = useMutation({
    mutationFn: () => api<{ runId: string; seeds: number }>("/admin/ingestion/runs", {
      method: "POST",
      body: { topics: topics.split(",").map((t) => t.trim()).filter(Boolean), difficulties: diffs, count },
    }),
    onSuccess: () => runs.refetch(),
  });

  return (
    <div>
      <PageTitle>AI ingestion</PageTitle>
      <p className="text-muted mb-6 max-w-[68ch]">
        Each seed asks the model for an original problem on a topic, compares it with similar problems already in the bank
        (retrieval), generates tests from a reference solution, verifies it in the sandbox, and puts it in the review queue.
        Nothing is published without your approval.
      </p>
      <form className="rounded-xl border border-rule p-5 space-y-4 max-w-2xl" onSubmit={(e) => { e.preventDefault(); start.mutate(); }}>
        <Field label="Topics, comma separated (leave empty for the built-in list of 30)" value={topics}
          onChange={(e) => setTopics(e.target.value)} placeholder="sliding window, binary search, graphs BFS" />
        <fieldset>
          <legend className="text-sm text-muted mb-1.5">Difficulties</legend>
          <div className="flex gap-4">
            {["EASY", "MEDIUM", "HARD"].map((d) => (
              <label key={d} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={diffs.includes(d)}
                  onChange={(e) => setDiffs(e.target.checked ? [...diffs, d] : diffs.filter((x) => x !== d))} />
                {d[0] + d.slice(1).toLowerCase()}
              </label>
            ))}
          </div>
        </fieldset>
        <Field label="Number of problems (1 to 200 per run)" type="number" min={1} max={200} value={count}
          onChange={(e) => setCount(Number(e.target.value))} />
        {start.error && <ErrorNote error={start.error} />}
        {start.data && <p className="text-pass text-sm">Started run {start.data.runId} with {start.data.seeds} seeds.</p>}
        <Button disabled={start.isPending || diffs.length === 0}>Start ingestion run</Button>
      </form>

      <h2 className="font-display font-semibold text-xl mt-10 mb-3">Runs</h2>
      {runs.isLoading && <Skeleton className="h-24" />}
      {runs.data?.length === 0 && <p className="text-muted">No runs yet.</p>}
      <ul className="divide-y divide-rule border-y border-rule">
        {runs.data?.map((r) => (
          <li key={r.runId}>
            <Link to={`/admin/ingestion/${r.runId}`} className="flex gap-4 py-3 hover:bg-panel/50 px-2 -mx-2 rounded">
              <span className="font-mono text-sm flex-1">{r.runId}</span>
              <span className="text-sm text-muted">{new Date(r.createdAt).toLocaleString()}</span>
              <span className="text-sm w-24 text-right">{r.total} seeds</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function IngestionRun() {
  const { runId = "" } = useParams();
  const q = useQuery({ queryKey: ["ingestion-run", runId], queryFn: () => api<RunView>(`/admin/ingestion/runs/${runId}`), refetchInterval: 4000 });
  if (q.error) return <ErrorNote error={q.error} />;
  if (!q.data) return <Skeleton className="h-60" />;
  return (
    <div>
      <PageTitle>Run {runId.slice(-8)}</PageTitle>
      <p className="text-sm text-muted mb-4">
        {Object.entries(q.data.stages).map(([k, v]) => `${v} ${k.replace(/_/g, " ").toLowerCase()}`).join(", ")}
      </p>
      <table className="w-full text-sm">
        <thead className="text-left text-muted"><tr><th className="py-2 font-medium">Seed</th><th className="font-medium">Topic</th><th className="font-medium">Difficulty</th><th className="font-medium">Stage</th><th className="font-medium">Result</th></tr></thead>
        <tbody>
          {q.data.seeds.map((s) => (
            <tr key={s.id} className="border-t border-rule align-top">
              <td className="py-2 tabular-nums">{s.id}</td>
              <td>{s.topic}</td>
              <td>{s.difficulty.toLowerCase()}</td>
              <td className={STAGE_COLOR[s.stage] ?? ""}>{s.stage.replace(/_/g, " ").toLowerCase()}</td>
              <td className="max-w-[28rem]">
                {s.slug && <Link className="text-signal hover:underline" to={`/admin/review/${s.slug}`}>{s.slug}</Link>}
                {s.error && <span className="text-fail break-words">{s.error}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
```

**File:** `frontend/src/admin/NewProblem.tsx`

```tsx
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
```

**File:** `frontend/src/admin/Overview.tsx`

```tsx
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

interface OverviewData {
  activeUsers: number;
  submissions: number;
  acceptanceRate: number;
  byLanguage: Record<string, number>;
  hardestProblems: { slug: string; title: string; submits: number; acceptance: number }[];
}

export function Overview() {
  const q = useQuery({ queryKey: ["admin-overview"], queryFn: () => api<OverviewData>("/admin/analytics/overview") });
  return (
    <div>
      <PageTitle>Overview</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {!q.data ? <Skeleton className="h-40" /> : (
        <div className="space-y-8">
          <dl className="grid grid-cols-3 gap-6">
            {[["People with submissions", q.data.activeUsers], ["Submissions", q.data.submissions], ["Acceptance", `${q.data.acceptanceRate}%`]].map(([k, v]) => (
              <div key={k}><dt className="text-sm text-muted">{k}</dt><dd className="font-display font-extrabold text-3xl">{v}</dd></div>
            ))}
          </dl>
          <section>
            <h2 className="font-display font-semibold text-lg mb-2">Lowest acceptance</h2>
            <table className="w-full text-sm">
              <tbody>
                {q.data.hardestProblems.map((p) => (
                  <tr key={p.slug} className="border-t border-rule">
                    <td className="py-2">{p.title}</td>
                    <td className="text-right text-muted tabular-nums">{p.submits} submissions</td>
                    <td className="text-right tabular-nums w-24">{p.acceptance}%</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </div>
      )}
    </div>
  );
}
```

**File:** `frontend/src/admin/Review.tsx`

```tsx
import { useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import ReactMarkdown from "react-markdown";
import rehypeSanitize from "rehype-sanitize";
import { api } from "../api/client";
import type { Difficulty, Example } from "../api/types";
import { Button, DifficultyTag, ErrorNote, PageTitle, Skeleton } from "../components/ui";

interface Row { slug: string; title: string; difficulty: Difficulty; status: string; number: number; testsVersion: number; source: string; updatedAt: string }
interface Detail {
  record: { doc: { slug: string; title: string; difficulty: Difficulty; tags: string[]; statement: string; constraints: string[];
    examples: Example[]; signature: { function: string; params: { name: string; type: string }[]; returns: string; compare: string } };
    status: string; number: number; testsVersion: number; source: string };
  starters: Record<string, string>;
}

const STATUSES = ["IN_REVIEW", "DRAFT", "PUBLISHED", "REJECTED", "RETIRED"];

export function ReviewQueue() {
  const [params, setParams] = useSearchParams();
  const status = params.get("status") ?? "IN_REVIEW";
  const q = useQuery({ queryKey: ["admin-problems", status], queryFn: () => api<Row[]>(`/admin/problems?status=${status}`) });
  return (
    <div>
      <PageTitle aside={<select value={status} onChange={(e) => setParams({ status: e.target.value })} aria-label="Status"
        className="h-9 rounded-md bg-panel border border-rule px-2 text-sm">
        {STATUSES.map((s) => <option key={s} value={s}>{s.replace("_", " ").toLowerCase()}</option>)}
      </select>}>Problems</PageTitle>
      {q.error && <ErrorNote error={q.error} />}
      {q.isLoading && <Skeleton className="h-40" />}
      {q.data?.length === 0 && <p className="text-muted">Nothing here. Generated problems land in “in review” after an AI ingestion run.</p>}
      <ul className="divide-y divide-rule border-y border-rule">
        {q.data?.map((r) => (
          <li key={r.slug}>
            <Link to={`/admin/review/${r.slug}`} className="flex items-center gap-4 py-3 hover:bg-panel/50 px-2 -mx-2 rounded">
              <span className="flex-1 font-medium">{r.title}</span>
              <DifficultyTag value={r.difficulty} />
              <span className="text-sm text-muted w-40 truncate">{r.source}</span>
              <span className="text-sm text-muted w-20">tests v{r.testsVersion}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ReviewDetail() {
  const { slug = "" } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [lang, setLang] = useState("java");
  const q = useQuery({ queryKey: ["admin-problem", slug], queryFn: () => api<Detail>(`/admin/problems/${slug}`) });
  const act = useMutation({
    mutationFn: (action: "publish" | "reject" | "retire") => api(`/admin/problems/${slug}/${action}`, { method: "POST" }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["admin-problems"] }); navigate("/admin/review"); },
  });
  if (q.error) return <ErrorNote error={q.error} />;
  if (!q.data) return <Skeleton className="h-80" />;
  const d = q.data.record.doc;
  const status = q.data.record.status;

  return (
    <div>
      <PageTitle aside={
        <div className="flex gap-2">
          {status !== "PUBLISHED" && status !== "RETIRED" && <Button onClick={() => act.mutate("publish")} disabled={act.isPending}>Publish</Button>}
          {status === "IN_REVIEW" && <Button variant="danger" onClick={() => act.mutate("reject")} disabled={act.isPending}>Reject</Button>}
          {status === "PUBLISHED" && <Button variant="danger" onClick={() => act.mutate("retire")} disabled={act.isPending}>Retire</Button>}
        </div>
      }>{d.title}</PageTitle>
      {act.error && <ErrorNote error={act.error} />}
      <p className="text-sm text-muted mb-6">
        <DifficultyTag value={d.difficulty} /> <span className="mx-2" />{d.tags.join(", ")}<span className="mx-2" />
        status {status.toLowerCase()}, source {q.data.record.source}, hidden tests v{q.data.record.testsVersion}
      </p>
      <div className="grid xl:grid-cols-2 gap-8">
        <section>
          <div className="statement"><ReactMarkdown rehypePlugins={[rehypeSanitize]}>{d.statement}</ReactMarkdown></div>
          {d.examples.map((e, i) => (
            <pre key={i} className="mt-3 rounded-lg bg-panel border border-rule p-3 font-mono text-[13px] whitespace-pre-wrap">
              Input: {e.input}{"\n"}Output: {e.output}{e.explanation ? `\n${e.explanation}` : ""}
            </pre>
          ))}
          <ul className="mt-4 list-disc pl-5 font-mono text-[13px] text-muted">{d.constraints.map((c) => <li key={c}>{c}</li>)}</ul>
        </section>
        <section>
          <h2 className="font-display font-semibold mb-2">Signature</h2>
          <p className="font-mono text-sm mb-4">
            {d.signature.function}({d.signature.params.map((p) => `${p.name}: ${p.type}`).join(", ")}) → {d.signature.returns}
            <span className="text-muted"> compared as {d.signature.compare}</span>
          </p>
          <div className="flex gap-1 mb-2">
            {Object.keys(q.data.starters).map((l) => (
              <button key={l} onClick={() => setLang(l)}
                className={`h-8 px-3 rounded-md text-sm ${lang === l ? "bg-raised" : "text-muted hover:text-paper"}`}>{l}</button>
            ))}
          </div>
          <pre className="rounded-lg bg-panel border border-rule p-3 font-mono text-[13px] overflow-x-auto">{q.data.starters[lang]}</pre>
          <p className="text-sm text-muted mt-4">
            Before publishing: read the statement for clarity and originality, check the examples by hand, and open the problem
            after publishing to submit a solution in at least one language.
          </p>
        </section>
      </div>
    </div>
  );
}
```

**File:** `frontend/src/admin/Users.tsx`

```tsx
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api/client";
import { useAuth } from "../auth/AuthProvider";
import { ErrorNote, PageTitle, Skeleton } from "../components/ui";

interface UserRow { id: string; email: string; displayName: string; roles: string[]; status: string }

export function Users() {
  const { user } = useAuth();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ["admin-users"], queryFn: () => api<UserRow[]>("/admin/users") });
  const set = useMutation({
    mutationFn: ({ id, admin }: { id: string; admin: boolean }) => api(`/admin/users/${id}/admin`, { method: "POST", body: { admin } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin-users"] }),
  });
  return (
    <div>
      <PageTitle>Users</PageTitle>
      {(q.error || set.error) && <ErrorNote error={q.error ?? set.error} />}
      {q.isLoading && <Skeleton className="h-40" />}
      <table className="w-full text-sm">
        <thead className="text-left text-muted"><tr><th className="py-2 font-medium">Name</th><th className="font-medium">Email</th><th className="font-medium">Role</th><th /></tr></thead>
        <tbody>
          {q.data?.map((u) => {
            const admin = u.roles.includes("ADMIN");
            return (
              <tr key={u.id} className="border-t border-rule">
                <td className="py-2">{u.displayName}</td>
                <td className="text-muted">{u.email}</td>
                <td>{admin ? "Admin" : "User"}</td>
                <td className="text-right">
                  {u.id !== user?.id && (
                    <button className="text-signal hover:underline" disabled={set.isPending}
                      onClick={() => { if (confirm(admin ? `Remove admin rights from ${u.displayName}?` : `Make ${u.displayName} an admin?`)) set.mutate({ id: u.id, admin: !admin }); }}>
                      {admin ? "Remove admin" : "Make admin"}
                    </button>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <p className="text-sm text-muted mt-4">Admins can author and publish problems, run AI ingestion and manage fests. Role changes apply at the person's next token refresh.</p>
    </div>
  );
}
```

## Check

```bash
cd frontend
npm install
npm run build          # type-checks and builds; must finish without errors
npm run dev            # http://localhost:5173 (the backend from chapter 12 must be running)
```

1. Register with the `BOOTSTRAP_ADMIN_EMAIL` address: the Admin tab appears.
2. Register a second account in a private window: it has no Admin tab, and `/admin` redirects to the problem list.
3. Open a problem, switch languages and watch the starter code change; Run, then Submit; the strip fills in.
4. Create a fest starting in one minute (Admin → Fests), register as the second user, submit, and watch the
   leaderboard update within five seconds.
