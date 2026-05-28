'use client';

import { useState } from 'react';

// Shows the copy-pasteable #salary block (matching the salon's manual format) with a Copy button.
export default function SalaryCopyButton({ message }: { message: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(message);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard blocked (e.g. insecure context) — the text is shown below to copy manually.
    }
  }

  return (
    <div className="rounded-lg bg-zinc-50 ring-1 ring-zinc-200">
      <div className="flex items-center justify-between border-b border-zinc-200 px-3 py-1.5">
        <span className="text-xs font-medium text-zinc-500">#salary</span>
        <button
          onClick={copy}
          className="rounded px-2 py-0.5 text-xs font-medium text-blue-600 ring-1 ring-blue-200 hover:bg-blue-50"
        >
          {copied ? 'Copied ✓' : 'Copy'}
        </button>
      </div>
      <pre className="overflow-x-auto whitespace-pre-wrap px-3 py-2 font-mono text-xs text-zinc-700">{message}</pre>
    </div>
  );
}
