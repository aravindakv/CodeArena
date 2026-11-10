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