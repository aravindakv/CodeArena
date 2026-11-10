
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