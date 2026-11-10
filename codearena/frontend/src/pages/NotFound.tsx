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