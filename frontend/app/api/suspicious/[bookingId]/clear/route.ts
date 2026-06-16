import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/suspicious/{bookingId}/clear — body: optional {note}
export async function POST(req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  const body = await req.text();
  return forwardToBackend(
    `/api/suspicious/${encodeURIComponent(bookingId)}/clear`,
    'POST',
    body || '{}',
  );
}

// DELETE /api/suspicious/{bookingId}/clear — undo
export async function DELETE(_req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  return forwardToBackend(`/api/suspicious/${encodeURIComponent(bookingId)}/clear`, 'DELETE');
}
