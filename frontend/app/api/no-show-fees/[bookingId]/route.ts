import { forwardToBackend } from '../../../lib/proxyBackend';

// DELETE /api/no-show-fees/{bookingId} — un-do a prior override. Next 16: route params are async.
export async function DELETE(_req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  return forwardToBackend(`/api/no-show-fees/${encodeURIComponent(bookingId)}`, 'DELETE');
}
