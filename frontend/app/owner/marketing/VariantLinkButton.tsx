'use client';

import { useState } from 'react';

// Lets the owner copy or open a direct ?v=<key> link to preview one specific marketing variant.
export default function VariantLinkButton({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard blocked (e.g. insecure context) — Open still works.
    }
  }

  return (
    <div className="flex items-center gap-1.5">
      <button
        onClick={copy}
        className="rounded px-2 py-0.5 text-xs font-medium text-blue-600 ring-1 ring-blue-200 hover:bg-blue-50"
      >
        {copied ? 'Copied ✓' : 'Copy link'}
      </button>
      <a
        href={url}
        target="_blank"
        rel="noopener noreferrer"
        className="rounded px-2 py-0.5 text-xs font-medium text-zinc-600 ring-1 ring-zinc-200 hover:bg-zinc-50"
      >
        Open ↗
      </a>
    </div>
  );
}
