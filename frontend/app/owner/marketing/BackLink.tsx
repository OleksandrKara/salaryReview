'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';

/** Goes back to wherever the reader came from (Google PPC or Lost leads); the fallback link covers a lead opened directly. */
export default function BackLink({ fallbackHref, children }: { fallbackHref: string; children: React.ReactNode }) {
  const router = useRouter();
  return (
    <Link
      href={fallbackHref}
      onClick={(e) => {
        if (window.history.length > 1) {
          e.preventDefault();
          router.back();
        }
      }}
      className="inline-flex min-h-[44px] items-center text-sm text-zinc-600 underline sm:min-h-0"
    >
      {children}
    </Link>
  );
}
