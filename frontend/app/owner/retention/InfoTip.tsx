'use client';

import { useState } from 'react';

// Small "i" affordance next to a column header. Opens on hover (desktop) and on tap/focus (mobile),
// closes on blur/leave/Escape. The bubble text resets the header's uppercase/tracking so it reads
// as a normal sentence.
export default function InfoTip({ text, label }: { text: string; label: string }) {
  const [open, setOpen] = useState(false);

  return (
    <span
      className="relative inline-block align-middle"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
    >
      <button
        type="button"
        aria-label={`What is ${label}?`}
        aria-expanded={open}
        onClick={(e) => {
          e.preventDefault();
          setOpen((o) => !o);
        }}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => e.key === 'Escape' && setOpen(false)}
        className="ml-1 inline-flex h-3.5 w-3.5 items-center justify-center rounded-full bg-zinc-200 text-[9px] font-bold leading-none text-zinc-600 transition-colors hover:bg-zinc-300"
      >
        i
      </button>
      {open ? (
        <span
          role="tooltip"
          className="absolute left-1/2 top-full z-20 mt-1.5 w-52 max-w-[60vw] -translate-x-1/2 rounded-md bg-zinc-800 px-2.5 py-2 text-[11px] font-normal normal-case leading-snug tracking-normal text-white shadow-lg"
        >
          {text}
        </span>
      ) : null}
    </span>
  );
}
