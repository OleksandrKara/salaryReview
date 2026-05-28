// Presentational spinner + full-screen loading state. Server-component safe (no client hooks), so
// it can back route-level loading.tsx files (Next shows these via Suspense while a server component
// fetches) as well as inline button busy states.

export function Spinner({ className }: { className?: string }) {
  return (
    <svg className={`animate-spin ${className ?? 'h-5 w-5'}`} viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 0 1 8-8v4a4 4 0 0 0-4 4H4z" />
    </svg>
  );
}

export function LoadingScreen({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex min-h-[60vh] items-center justify-center gap-3 text-zinc-500">
      <Spinner className="h-6 w-6 text-zinc-400" />
      <span className="text-sm">{label}</span>
    </div>
  );
}
