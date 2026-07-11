'use client';

import { useState } from 'react';

/** Copies (or, on mobile, natively shares) a direct link to one specific KB article or SOP —
 * every one now has a stable, permission-checked URL (see /kb/[id] and /sops/[id]), so "share this
 * with a manager" is a single click instead of impossible. Prefers the OS share sheet when
 * available (mobile — lets the sender pick Slack/SMS/email directly); falls back to copying the
 * link to the clipboard, with the same "Copied ✓" feedback VariantLinkButton already uses for
 * marketing deep links, so the interaction is familiar across the app.
 */
export default function ShareLinkButton({
  path,
  title,
  label = 'Share',
  className,
}: {
  /** Site-relative path, e.g. `/kb/42` — resolved against the current origin at click time. */
  path: string;
  /** Passed to the native share sheet when available; ignored by the clipboard fallback. */
  title?: string;
  label?: string;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);

  async function share(e: React.MouseEvent) {
    e.stopPropagation();
    const url = `${window.location.origin}${path}`;

    if (navigator.share) {
      try {
        await navigator.share({ url, title });
      } catch {
        // User cancelled or the share sheet failed — respect that rather than also copying.
      }
      return;
    }

    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard blocked (e.g. insecure context) — nothing more to do.
    }
  }

  return (
    <button
      type="button"
      onClick={share}
      title="Copy a shareable link"
      className={
        className ??
        'inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-blue-600 ring-1 ring-blue-200 hover:bg-blue-50'
      }
    >
      {copied ? 'Copied ✓' : label}
    </button>
  );
}
