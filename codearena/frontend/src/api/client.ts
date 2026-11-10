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