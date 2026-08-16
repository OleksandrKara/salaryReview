import Link from 'next/link';

// A page-content empty state for "this business hasn't finished a setup step yet" — swapped in by
// a page's own catch block (see /reports, /admin/messages) instead of letting the raw error
// (GlobalExceptionHandler's BusinessSetupIncompleteException, or SmsBusinessScopeFilter's 403)
// crash the page. Not a modal/gate like OnboardingGate — this is scoped to whichever single page
// hit the missing-setup error, not the whole app.
export default function SetupRequiredNotice({
  title,
  message,
  ctaHref,
  ctaLabel,
}: {
  title: string;
  message: string;
  ctaHref?: string;
  ctaLabel?: string;
}) {
  return (
    <div className="mx-auto mt-10 max-w-md rounded-xl p-6 text-center ring-1 ring-zinc-200">
      <h2 className="text-base font-medium text-zinc-800">{title}</h2>
      <p className="mt-2 text-sm text-zinc-500">{message}</p>
      {ctaHref && ctaLabel && (
        <Link
          href={ctaHref}
          className="mt-4 inline-block rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-800"
        >
          {ctaLabel}
        </Link>
      )}
    </div>
  );
}
