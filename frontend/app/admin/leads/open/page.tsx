import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';
import { enterBusinessUrl, needsSwitch, parseBusinessParam } from '../../../lib/businessLinks';

// Telegram links: "Open lead" (phone) and "Stop funnel" (academy/consult row). Always lands on the lead card
// behind the normal login, so a stop is recorded under a real user. The lookup runs in the session's
// business, so a link naming another business switches to it first.
export default async function OpenLeadPage({ searchParams }: { searchParams: Promise<{ phone?: string; academy?: string; consult?: string; business?: string }> }) {
  const { phone, academy, consult, business } = await searchParams;
  const wanted = parseBusinessParam(business);
  if (wanted !== null) {
    const me = await serverApi.getMe();
    if (needsSwitch(wanted, me.activeBusinessId)) {
      const q = new URLSearchParams();
      if (phone) q.set('phone', phone);
      if (academy) q.set('academy', academy);
      if (consult) q.set('consult', consult);
      redirect(enterBusinessUrl(wanted, `/admin/leads/open?${q.toString()}`));
    }
  }
  const kind = academy ? 'academy' : consult ? 'consult' : null;
  const rowId = Number(academy ?? consult);
  const biz = wanted !== null ? `business=${wanted}` : '';
  const withBiz = (path: string, extra = '') => {
    const q = [extra, biz].filter(Boolean).join('&');
    return q ? `${path}?${q}` : path;
  };
  if (kind && Number.isFinite(rowId)) {
    const found = await serverApi.resolveFunnelLead(kind, rowId).catch(() => ({} as { id?: number }));
    redirect(found.id ? withBiz(`/admin/leads/${found.id}`, `stop=${kind}`) : '/admin/leads');
  }
  const found = phone ? await serverApi.lookupLead(phone) : {};
  redirect(found.id ? withBiz(`/admin/leads/${found.id}`) : '/admin/leads');
}
