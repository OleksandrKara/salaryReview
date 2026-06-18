'use client';

/** Inline 3-point sparkle icon (heroicons-style) — universal "AI" visual cue. */
function SparklesIcon({ className }: { className?: string }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      strokeWidth={1.75}
      stroke="currentColor"
      className={className ?? 'h-3.5 w-3.5'}
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 00-2.456 2.456zM16.894 20.567L16.5 21.75l-.394-1.183a2.25 2.25 0 00-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 001.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 001.423 1.423l1.183.394-1.183.394a2.25 2.25 0 00-1.423 1.423z"
      />
    </svg>
  );
}

/**
 * AI-styled trigger button — sparkle icon + violet gradient. Lives in the row's action bar
 * alongside Clear so the owner can fire either action with the same reach. Shown when no triage
 * exists yet; hidden once a triage has been generated (the result panel replaces it).
 */
export default function ExplainButton({
  bookingId,
  onClick,
  loading,
}: {
  bookingId: string;
  onClick: () => void;
  loading?: boolean;
}) {
  return (
    <button
      data-testid={`triage-explain-${bookingId}`}
      onClick={onClick}
      disabled={loading}
      title="Ask the AI to explain this booking"
      className="inline-flex items-center gap-1.5 rounded border border-amber-300 bg-amber-50 px-3 py-1.5 text-xs font-medium text-amber-800 shadow-sm hover:bg-amber-100 hover:border-amber-400 focus:outline-none focus:ring-2 focus:ring-amber-200 disabled:opacity-60 disabled:cursor-wait"
    >
      <SparklesIcon className="h-3.5 w-3.5 text-amber-600" />
      {loading ? 'Asking the AI…' : 'Explain with AI'}
    </button>
  );
}
