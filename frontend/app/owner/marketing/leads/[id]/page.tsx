import { notFound, redirect } from 'next/navigation';
import { serverApi } from '../../../../lib/serverApi';
import { t } from '../../../../lib/i18n';
import PageHeader from '../../../../components/PageHeader';
import BackLink from '../../BackLink';
import LeadMoney from '../../../../components/LeadMoney';
import { consultationLabel, statusLabel, statusTone } from '../../adLeads';

const TZ = 'America/Los_Angeles';
const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 0, maximumFractionDigits: 2 });
const fmtDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: TZ }) : '-';
const fmtDateTime = (iso: string | null) =>
  iso ? new Date(iso).toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: TZ }) : '-';

const apptStatus: Record<string, string> = {
  ACCEPTED: 'Booked', PENDING: 'Pending', NO_SHOW: 'No-show', CANCELLED_BY_CUSTOMER: 'Cancelled by client',
  CANCELLED_BY_SELLER: 'Cancelled by studio', DECLINED: 'Declined',
};

export default async function AdLeadPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const me = await serverApi.getMe();
  if (me?.role !== 'OWNER' && me?.role !== 'ADS_MANAGER') redirect('/reports');
  const lead = await serverApi.getAdLead(Number(id)).catch(() => null);
  if (!lead) notFound();
  if (lead.id !== Number(id)) redirect(`/owner/marketing/leads/${lead.id}`);

  const lang = me.preferredLanguage;
  const lostLabel = lead.lostReason ? t(lang, `leadLost_${lead.lostReason}` as 'leadLost_PRICE') || lead.lostReason : null;
  const procedures = lead.appointments.filter((a) => !a.consultation);
  const consultations = lead.appointments.filter((a) => a.consultation);
  const nextStep = consultationLabel(
    consultations.length > 0 && procedures.length === 0
      ? consultations[0].status === 'NO_SHOW' ? 'NO_SHOW' : consultations[0].startAt && new Date(consultations[0].startAt) > new Date() ? 'UPCOMING' : 'DONE'
      : null,
  );

  return (
    <main className="mx-auto w-full min-w-0 max-w-3xl p-4 sm:p-8">
      <PageHeader title="Lead" role={me.role} language={lang} activeBusinessId={me.activeBusinessId} businesses={me.businesses} />
      <BackLink fallbackHref="/owner/marketing/google-ppc">Back</BackLink>

      <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1">
        <h1 className="min-w-0 break-words text-xl font-semibold text-zinc-900">{lead.name || 'No name'}</h1>
        <span className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${statusTone(lead.status)}`}>{statusLabel(lead.status)}</span>
      </div>
      <p className="mt-1 text-sm text-zinc-500">
        Lead since {fmtDate(lead.createdAt)}
      </p>
      {lead.status === 'LOST' && (
        <p className="mt-3 rounded-lg bg-zinc-100 p-3 text-sm text-zinc-700">
          Lost{lostLabel ? `: ${lostLabel}` : ''}{lead.lostComment ? `. ${lead.lostComment}` : ''}
        </p>
      )}

      <div className="mt-4"><LeadMoney outlook={lead.outlook} language={lang} /></div>

      <Section title="How they found us">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Fact label="Search query" value={lead.searchQuery} empty="Not provided by Google" strong />
          <Fact label="Campaign" value={lead.campaign} />
          <Fact label="Source" value={lead.source} />
          <Fact label="Landing page" value={lead.landing ?? lead.firstVisit?.landing ?? null} />
          <Fact label="First visit" value={lead.firstVisit ? fmtDateTime(lead.firstVisit.at) : null} />
          <Fact label="Device" value={lead.firstVisit?.device ?? null} />
        </dl>
        {!lead.searchQuery && (
          <p className="mt-3 text-xs text-zinc-500">
            Performance Max and some Google clicks do not pass the search words to the site, so the query stays empty for them.
          </p>
        )}
      </Section>

      <Section title="Consultation and procedure">
        {lead.appointments.length === 0 ? (
          <p className="text-sm text-zinc-500">No appointment booked yet.</p>
        ) : (
          <>
            {nextStep && <p className="mb-3 text-sm font-medium text-zinc-800">{nextStep}</p>}
            <ul className="divide-y divide-zinc-100 rounded-lg ring-1 ring-zinc-200">
              {lead.appointments.map((a, i) => (
                <li key={i} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 px-3 py-2.5 text-sm">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="break-words font-medium text-zinc-900">{a.services ?? 'Appointment'}</span>
                      <span className={`rounded-full px-2 py-0.5 text-xs ${a.consultation ? 'bg-blue-50 text-blue-800' : 'bg-emerald-50 text-emerald-800'}`}>
                        {a.consultation ? 'Consultation' : 'Procedure'}
                      </span>
                    </div>
                    <div className="mt-0.5 text-xs text-zinc-500">
                      {fmtDateTime(a.startAt)}{a.technician ? ` | ${a.technician}` : ''}{a.status ? ` | ${apptStatus[a.status] ?? a.status}` : ''}
                    </div>
                  </div>
                  {a.paid != null && a.paid > 0 && <span className="text-sm font-semibold tabular-nums text-zinc-900">{usd.format(a.paid)}</span>}
                </li>
              ))}
            </ul>
          </>
        )}
      </Section>

      {lead.payments.length > 0 && (
        <Section title="Other payments">
          <ul className="space-y-1.5 text-sm">
            {lead.payments.map((p) => (
              <li key={p.id} className="flex justify-between gap-3">
                <span className="min-w-0 break-words text-zinc-700">{fmtDate(p.at)}{p.description ? ` | ${p.description}` : ''}</span>
                <span className="font-medium tabular-nums text-zinc-900">{usd.format(p.amount)}</span>
              </li>
            ))}
          </ul>
        </Section>
      )}

      {lead.steps.length > 0 && (
        <Section title="Progress">
          <ol className="space-y-1.5 text-sm">
            {lead.steps.map((s, i) => (
              <li key={i} className="flex justify-between gap-3">
                <span className="min-w-0 break-words text-zinc-700">{s.text ?? 'Status changed'}</span>
                <span className="shrink-0 text-xs tabular-nums text-zinc-500">{fmtDate(s.at)}</span>
              </li>
            ))}
          </ol>
        </Section>
      )}

      <Section title="Contact">
        <dl className="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2">
          <Fact label="Phone" value={lead.phone} />
          <Fact label="Email" value={lead.email} />
        </dl>
      </Section>
    </main>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="mt-6">
      <h2 className="mb-2 text-sm font-semibold text-zinc-900">{title}</h2>
      {children}
    </section>
  );
}

function Fact({ label, value, empty = '-', strong }: { label: string; value: string | null; empty?: string; strong?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-zinc-500">{label}</dt>
      <dd className={`break-words text-sm ${value ? (strong ? 'font-semibold text-zinc-900' : 'text-zinc-800') : 'text-zinc-400'}`}>{value || empty}</dd>
    </div>
  );
}
