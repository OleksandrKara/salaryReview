import { redirect } from 'next/navigation';
import { serverApi } from '../../../lib/serverApi';

// Telegram links: "Open lead" (phone) and "Stop funnel" (academy/consult row). Always lands on the lead card
// behind the normal login, so a stop is recorded under a real user.
export default async function OpenLeadPage({ searchParams }: { searchParams: Promise<{ phone?: string; academy?: string; consult?: string }> }) {
  const { phone, academy, consult } = await searchParams;
  const kind = academy ? 'academy' : consult ? 'consult' : null;
  const rowId = Number(academy ?? consult);
  if (kind && Number.isFinite(rowId)) {
    const found = await serverApi.resolveFunnelLead(kind, rowId).catch(() => ({} as { id?: number }));
    redirect(found.id ? `/admin/leads/${found.id}?stop=${kind}` : '/admin/leads');
  }
  const found = phone ? await serverApi.lookupLead(phone) : {};
  redirect(found.id ? `/admin/leads/${found.id}` : '/admin/leads');
}
