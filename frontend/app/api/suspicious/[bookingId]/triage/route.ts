import { forwardToBackend } from '../../../../lib/proxyBackend';

// POST /api/suspicious/{bookingId}/triage?year=&month=
// Owner clicks the Explain button — proxy to the backend AI triage endpoint, forward year/month
// query params verbatim. Backend returns the TriageResult as JSON (no streaming — see design.md D8
// → dropped during Chunk B because streaming structured-JSON tokens is the wrong UX).
export async function POST(req: Request, ctx: { params: Promise<{ bookingId: string }> }): Promise<Response> {
  const { bookingId } = await ctx.params;
  const url = new URL(req.url);
  const year = url.searchParams.get('year');
  const month = url.searchParams.get('month');
  if (!year || !month) {
    return new Response(JSON.stringify({ error: 'year and month query params are required' }), {
      status: 400,
      headers: { 'Content-Type': 'application/json' },
    });
  }
  const qs = `?year=${encodeURIComponent(year)}&month=${encodeURIComponent(month)}`;
  return forwardToBackend(
    `/api/suspicious/${encodeURIComponent(bookingId)}/triage${qs}`,
    'POST',
    '{}',
  );
}
