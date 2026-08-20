import { forwardToBackend } from '../../../../../lib/proxyBackend';

// PUT /api/owner/settings/promos/{promoCode} — save discount amount/minimum-spend; creates the
// Square Customer Group/Discount/Pricing Rule on first save for a business.
export async function PUT(req: Request, { params }: { params: Promise<{ promoCode: string }> }): Promise<Response> {
  const { promoCode } = await params;
  const body = await req.text();
  return forwardToBackend(`/api/owner/settings/promos/${encodeURIComponent(promoCode)}`, 'PUT', body || '{}');
}
