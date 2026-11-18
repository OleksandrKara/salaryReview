'use client';

import type { Language } from '../../lib/types';
import { badgeInfo, it, type IntakeSummary } from '../../lib/intake';

export default function IntakeBadge({ form, language, showAttention = true }: {
  form: IntakeSummary;
  language: Language | null;
  showAttention?: boolean;
}) {
  const b = badgeInfo(form, language);
  return (
    <span className="inline-flex flex-wrap items-center gap-1">
      <span data-testid="intake-badge" className={`inline-flex items-center whitespace-nowrap rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ${b.className}`}>
        {b.label}
      </span>
      {showAttention && form.status === 'SIGNED' && form.needsAttention && (
        form.attentionLevel === 'CRITICAL' ? (
          <span data-testid="intake-critical" className="inline-flex items-center whitespace-nowrap rounded-full bg-red-700 px-2 py-0.5 text-[11px] font-bold text-white ring-1 ring-red-900">
            {it(language, 'critical')}
          </span>
        ) : (
          <span data-testid="intake-attention" className="inline-flex items-center whitespace-nowrap rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-semibold text-amber-900 ring-1 ring-amber-300">
            {it(language, 'attention')}
          </span>
        )
      )}
    </span>
  );
}
