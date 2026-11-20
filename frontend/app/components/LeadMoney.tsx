import type { LeadOutlook } from '../lib/types';

const TZ = 'America/Los_Angeles';

const TEXT = {
  EN: {
    collected: 'Collected', left: 'Left to collect', ahead: 'Booked ahead', expected: 'Expected', prepaid: 'Prepaid',
    of: 'of', nothing: 'Nothing booked ahead', unpriced: 'Some booked services have no price yet, so the total may be higher.',
    paid: 'Paid in full', procedure: 'Procedure',
  },
  RU: {
    collected: 'Получено', left: 'Осталось получить', ahead: 'Записано вперёд', expected: 'Ожидается', prepaid: 'Предоплата',
    of: 'из', nothing: 'Впереди записей нет', unpriced: 'У части услуг нет цены, итог может быть больше.',
    paid: 'Оплачено полностью', procedure: 'Процедура',
  },
} as const;

/** Collected so far, what is booked ahead and what is still left to collect (e.g. $100 deposit, $400 to pay on the day). */
export default function LeadMoney({ outlook, language }: { outlook: LeadOutlook | null | undefined; language?: 'EN' | 'RU' | null }) {
  if (!outlook || (outlook.collected <= 0 && outlook.upcoming.length === 0)) return null;
  const L = TEXT[language === 'RU' ? 'RU' : 'EN'];
  const locale = language === 'RU' ? 'ru-RU' : 'en-US';
  const money = (n: number) => `$${n.toLocaleString(locale, { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;
  const when = (iso: string) => new Date(iso).toLocaleString(locale, { weekday: 'short', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: TZ });
  const ahead = outlook.upcoming.length > 0;
  const pct = outlook.estimate > 0 ? Math.min(100, Math.round((outlook.prepaid / outlook.estimate) * 100)) : 0;

  return (
    <section aria-label={L.ahead} className="rounded-xl bg-white p-3 ring-1 ring-zinc-200 sm:p-4">
      <dl className="grid grid-cols-2 gap-3">
        <div className="min-w-0">
          <dt className="text-[11px] font-medium uppercase tracking-wide text-zinc-500">{L.collected}</dt>
          <dd className="mt-0.5 text-xl font-semibold tabular-nums text-zinc-900 sm:text-2xl">{money(outlook.collected)}</dd>
        </div>
        <div className="min-w-0">
          <dt className="text-[11px] font-medium uppercase tracking-wide text-zinc-500">{ahead ? L.left : L.ahead}</dt>
          {ahead ? (
            <dd className={`mt-0.5 text-xl font-semibold tabular-nums sm:text-2xl ${outlook.remaining > 0 ? 'text-amber-700' : 'text-emerald-700'}`}>
              {outlook.remaining > 0 ? money(outlook.remaining) : L.paid}
            </dd>
          ) : (
            <dd className="mt-1 text-sm text-zinc-500">{L.nothing}</dd>
          )}
        </div>
      </dl>

      {ahead && outlook.estimate > 0 && (
        <div className="mt-3">
          <div className="h-2 overflow-hidden rounded-full bg-zinc-100" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} aria-label={L.prepaid}>
            <div className="h-full rounded-full bg-emerald-600" style={{ width: `${pct}%` }} />
          </div>
          <p className="mt-1 text-xs tabular-nums text-zinc-500">
            {L.prepaid} {money(outlook.prepaid)} {L.of} {money(outlook.estimate)}
          </p>
        </div>
      )}

      {ahead && (
        <ul className="mt-3 divide-y divide-zinc-100 border-t border-zinc-100">
          {outlook.upcoming.map((u, i) => (
            <li key={i} className="flex items-start justify-between gap-3 py-2">
              <div className="min-w-0">
                <div className="break-words text-sm font-medium text-zinc-900">{u.services ?? L.procedure}</div>
                <div className="text-xs text-zinc-500">{when(u.startAt)}{u.technician ? ` | ${u.technician}` : ''}</div>
              </div>
              <div className="shrink-0 text-sm font-semibold tabular-nums text-zinc-900">{u.price != null ? money(u.price) : '-'}</div>
            </li>
          ))}
        </ul>
      )}
      {outlook.partial && <p className="mt-2 text-xs text-zinc-500">{L.unpriced}</p>}
    </section>
  );
}
