import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/cancellations/{bookingId}/clear — body: optional {note}
export async function POST(req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(
    `/api/cancellations/${encodeURIComponent(bookingId)}/clear`,
    'POST',
    body || '{}',
  );
}

// DELETE /api/cancellations/{bookingId}/clear — undo
export async function DELETE(_req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  return forwardToBackend(`/api/cancellations/${encodeURIComponent(bookingId)}/clear`, 'DELETE');
}
