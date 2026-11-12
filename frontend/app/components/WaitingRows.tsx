'use client';

import Link from 'next/link';
import type { Language } from '../lib/types';
import { t, tf } from '../lib/i18n';
import { inBusiness } from '../lib/businessLinks';
import { chatHref, formatWait, leadHref, sinceLabel, SLOW_WAIT_SECONDS } from '../lib/responseTime';

export interface WaitingRow {
  key: string;
  name: string | null;
  last4: string;
  phone: string;
  channel?: string | null;
  businessId: number | null;
  businessName?: string | null;
  preview?: string | null;
  since: string;
  seconds: number;
  leadId: number | null;
  leadStatus?: string | null;
  lastAutoKey?: string | null;
  lastAutoAt?: string | null;
  newLead?: boolean;
}

const ACTION = 'inline-flex min-h-11 items-center justify-center rounded-lg border px-3 text-sm font-medium';

export default function WaitingRows({ rows, language, activeBusinessId, testId }: { rows: WaitingRow[]; language: Language | null; activeBusinessId: number | null | undefined; testId: string }) {
  return (
    <ul className="divide-y divide-current/15">
      {rows.map((w) => {
        const hot = w.seconds > SLOW_WAIT_SECONDS;
        const title = w.name ?? `${t(language, w.newLead ? 'rtLeadNumber' : 'rtThread')} ${w.last4}`;
        const sub = [w.name ? `···${w.last4}` : null, w.channel, w.businessName].filter(Boolean).join(' · ');
        return (
          <li key={w.key} className="py-2" data-testid={`${testId}-row`}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="break-words text-sm font-semibold [overflow-wrap:anywhere]">{title}</p>
                {sub ? <p className="break-words text-xs opacity-80 [overflow-wrap:anywhere]">{sub}</p> : null}
              </div>
              <span className={`shrink-0 rounded-md px-1.5 py-0.5 text-sm font-semibold tabular-nums ${hot ? 'bg-red-600 text-white' : 'bg-white/60'}`}>{formatWait(w.seconds, language)}</span>
            </div>
            {w.preview ? <p className="mt-1 line-clamp-2 break-words text-xs italic opacity-90 [overflow-wrap:anywhere]">&ldquo;{w.preview}&rdquo;</p> : null}
            <p className="mt-1 text-[11px] opacity-80">
              {w.newLead ? tf(language, 'rtLeadCreated', { when: sinceLabel(w.since, language) }) : tf(language, 'rtLastText', { when: sinceLabel(w.since, language) })}
              {' · '}{tf(language, 'rtOnShiftWait', { wait: formatWait(w.seconds, language) })}
            </p>
            {w.lastAutoKey && w.lastAutoAt ? <p className="mt-1 text-[11px] opacity-90" data-testid="waiting-last-auto">{tf(language, 'rtLastAuto', { what: w.lastAutoKey.replace(/_/g, ' '), when: sinceLabel(w.lastAutoAt, language) })}</p> : null}
            <p className="mt-1 text-[11px]">
              {w.leadId != null
                ? <span className="rounded-full bg-white/60 px-2 py-0.5">{tf(language, 'rtLeadStatus', { status: w.leadStatus ?? 'NEW' })}</span>
                : <span className="rounded-full bg-white/60 px-2 py-0.5" data-testid="waiting-no-lead">{t(language, 'rtNoLead')}</span>}
            </p>
            <div className="mt-2 flex flex-wrap gap-2">
              <Link href={inBusiness(chatHref(w.phone), w.businessId, activeBusinessId)} prefetch={false} className={`${ACTION} border-current/30 bg-white/70`} data-testid="waiting-open-chat">{t(language, 'rtOpenChat')}</Link>
              {w.leadId != null ? (
                <Link href={inBusiness(leadHref(w.leadId), w.businessId, activeBusinessId)} prefetch={false} className={`${ACTION} border-current/30 bg-white/70`} data-testid="waiting-open-lead">{t(language, 'rtOpenLead')}</Link>
              ) : null}
            </div>
          </li>
        );
      })}
    </ul>
  );
}
